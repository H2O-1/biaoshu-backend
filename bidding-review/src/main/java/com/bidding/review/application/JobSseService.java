package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.JobPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务进度 SSE：连接建立后读库推 snapshot，再按间隔 diff 推 progress；
 * 终态推 done/failed 后关闭。不替代 GET /api/jobs/{id} 轮询。
 * <p>
 * 鉴权与轮询一致（Bearer + 本组织）；推送线程不再依赖 UserContext，仅用连接时锁定的 orgId。
 */
@Service
public class JobSseService {

    private static final Logger log = LoggerFactory.getLogger(JobSseService.class);

    private final JobPort jobPort;
    private final JobAppService jobAppService;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long pollMs;
    private final long heartbeatMs;
    private final long timeoutMs;
    private final ScheduledExecutorService scheduler;

    public JobSseService(
            JobPort jobPort,
            JobAppService jobAppService,
            ObjectMapper objectMapper,
            @Value("${bidding.job.sse.enabled:true}") boolean enabled,
            @Value("${bidding.job.sse.poll-ms:1000}") long pollMs,
            @Value("${bidding.job.sse.heartbeat-ms:15000}") long heartbeatMs,
            @Value("${bidding.job.sse.timeout-ms:7200000}") long timeoutMs) {
        this.jobPort = jobPort;
        this.jobAppService = jobAppService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.pollMs = pollMs < 200L ? 200L : pollMs;
        this.heartbeatMs = heartbeatMs < 1000L ? 1000L : heartbeatMs;
        this.timeoutMs = timeoutMs < 10000L ? 10000L : timeoutMs;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "job-sse");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 打开任务事件流。调用方须已登录招标方。
     */
    public SseEmitter openStream(long jobId) {
        if (!enabled) {
            throw new BizException(ErrorCode.BAD_REQUEST, "任务 SSE 未启用");
        }
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可订阅任务进度");
        JobAppService.JobSummary first = jobAppService.getJob(jobId);
        long orgId = operator.getOrgId();

        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<String> lastFingerprint = new AtomicReference<>("");
        AtomicLong lastHeartbeatAt = new AtomicLong(System.currentTimeMillis());
        AtomicReference<ScheduledFuture<?>> pollFutureRef = new AtomicReference<>();

        Runnable closeQuietly = () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> future = pollFutureRef.get();
            if (future != null) {
                future.cancel(false);
            }
        };

        emitter.onCompletion(closeQuietly);
        emitter.onTimeout(() -> {
            closeQuietly.run();
            log.debug("job SSE 超时 jobId={}", jobId);
        });
        emitter.onError(ex -> {
            closeQuietly.run();
            log.debug("job SSE 异常断开 jobId={}: {}", jobId, ex == null ? "" : ex.getMessage());
        });

        try {
            sendEvent(emitter, eventNameFor(first.getStatus(), true), first);
            lastFingerprint.set(fingerprint(first));
            if (isTerminal(first.getStatus())) {
                emitter.complete();
                closeQuietly.run();
                return emitter;
            }
        } catch (Exception ex) {
            // 无 Servlet 异步上下文时 send 可能失败；建连阶段直接结束
            closeQuietly.run();
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // 已无法写入
            }
            return emitter;
        }

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> pollOnce(jobId, orgId, emitter, closed, lastFingerprint, lastHeartbeatAt, closeQuietly),
                pollMs,
                pollMs,
                TimeUnit.MILLISECONDS
        );
        pollFutureRef.set(future);
        return emitter;
    }

    private void pollOnce(
            long jobId,
            long orgId,
            SseEmitter emitter,
            AtomicBoolean closed,
            AtomicReference<String> lastFingerprint,
            AtomicLong lastHeartbeatAt,
            Runnable closeQuietly) {
        if (closed.get()) {
            return;
        }
        try {
            Optional<JobPort.JobRef> optional = jobPort.findById(jobId);
            if (optional.isEmpty() || optional.get().getOrgId() != orgId) {
                sendNamed(emitter, "failed", errorPayload(jobId, "任务不存在"));
                emitter.complete();
                closeQuietly.run();
                return;
            }
            JobAppService.JobSummary summary = jobAppService.toSummary(optional.get());
            String fp = fingerprint(summary);
            String prev = lastFingerprint.get();
            if (!fp.equals(prev)) {
                lastFingerprint.set(fp);
                sendEvent(emitter, eventNameFor(summary.getStatus(), false), summary);
                lastHeartbeatAt.set(System.currentTimeMillis());
                if (isTerminal(summary.getStatus())) {
                    emitter.complete();
                    closeQuietly.run();
                }
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatAt.get() >= heartbeatMs) {
                // SSE 注释行作心跳，避免代理掐空闲连接
                emitter.send(SseEmitter.event().comment("ping"));
                lastHeartbeatAt.set(now);
            }
        } catch (IOException ex) {
            closeQuietly.run();
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // 已断开
            }
        } catch (Exception ex) {
            log.warn("job SSE 轮询失败 jobId={}: {}", jobId, ex.getMessage());
            closeQuietly.run();
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // 已断开
            }
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, JobAppService.JobSummary summary)
            throws IOException {
        String json = objectMapper.writeValueAsString(summary);
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(json, MediaType.APPLICATION_JSON));
    }

    private void sendNamed(SseEmitter emitter, String eventName, Object payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(json, MediaType.APPLICATION_JSON));
    }

    private static Map<String, Object> errorPayload(long jobId, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", jobId);
        map.put("status", JobPort.STATUS_FAILED);
        map.put("errorMessage", message);
        return map;
    }

    /**
     * 首包用 snapshot；进行中用 progress；终态用 done/failed。
     */
    static String eventNameFor(String status, boolean firstEvent) {
        if (firstEvent) {
            if (JobPort.STATUS_SUCCESS.equals(status)) {
                return "done";
            }
            if (JobPort.STATUS_FAILED.equals(status) || JobPort.STATUS_CANCELLED.equals(status)) {
                return "failed";
            }
            return "snapshot";
        }
        if (JobPort.STATUS_SUCCESS.equals(status)) {
            return "done";
        }
        if (JobPort.STATUS_FAILED.equals(status) || JobPort.STATUS_CANCELLED.equals(status)) {
            return "failed";
        }
        return "progress";
    }

    static boolean isTerminal(String status) {
        return JobPort.STATUS_SUCCESS.equals(status)
                || JobPort.STATUS_FAILED.equals(status)
                || JobPort.STATUS_CANCELLED.equals(status);
    }

    static String fingerprint(JobAppService.JobSummary s) {
        if (s == null) {
            return "";
        }
        return String.valueOf(s.getStatus())
                + '|' + s.getProgress()
                + '|' + nullToEmpty(s.getMessage())
                + '|' + nullToEmpty(s.getErrorMessage())
                + '|' + nullToEmpty(s.getPayloadJson())
                + '|' + String.valueOf(s.getChecklistId())
                + '|' + String.valueOf(s.getReviewId());
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
