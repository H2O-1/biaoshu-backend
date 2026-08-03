package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.AiReviewPipelineJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 同进程轮询认领 AI_REVIEW_PIPELINE 总任务。
 * 总任务会长时间等待子任务，故放到独立线程执行，避免占满调度线程导致 INDEX 等 Poller 饿死。
 */
@Component
@ConditionalOnProperty(
        prefix = "bidding.ai-review-pipeline",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AiReviewPipelineJobPoller {

    private static final Logger log = LoggerFactory.getLogger(AiReviewPipelineJobPoller.class);

    private final AiReviewPipelineJobExecutor executor;
    private final String workerId;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService runner;

    public AiReviewPipelineJobPoller(
            AiReviewPipelineJobExecutor executor,
            @Value("${bidding.ai-review-pipeline.worker-id:}") String configuredWorkerId) {
        this.executor = executor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "ai-pipe-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        this.runner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ai-review-pipeline-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("AI_REVIEW_PIPELINE Worker 已启用，workerId={}", this.workerId);
    }

    @Scheduled(fixedDelayString = "${bidding.ai-review-pipeline.poll-ms:5000}")
    public void poll() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        runner.submit(() -> {
            try {
                executor.claimAndRunOnce(workerId);
            } catch (Exception ex) {
                log.error("AI_REVIEW_PIPELINE Worker 执行异常", ex);
            } finally {
                busy.set(false);
            }
        });
    }
}
