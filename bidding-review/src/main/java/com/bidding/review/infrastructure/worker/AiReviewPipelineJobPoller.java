package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.AiReviewPipelineJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 定时认领并推进 AI_REVIEW_PIPELINE 总任务。
 * <p>
 * 状态机每次 {@code claimAndRunOnce} 只推进一个阶段、执行时间很短，
 * 不再需要独立线程或忙标志；单次调度内可连续处理多条任务，
 * 但设有批量上限（batch-size），避免调度线程被长期占用。
 * 默认关闭：API 进程不配或配 false 时不加载；Worker 进程配 true 开启。
 */
@Component
@ConditionalOnProperty(
        prefix = "bidding.ai-review-pipeline",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AiReviewPipelineJobPoller {

    private static final Logger log = LoggerFactory.getLogger(AiReviewPipelineJobPoller.class);

    private final AiReviewPipelineJobExecutor executor;
    private final String workerId;
    private final int batchSize;

    public AiReviewPipelineJobPoller(
            AiReviewPipelineJobExecutor executor,
            @Value("${bidding.ai-review-pipeline.worker-id:}") String configuredWorkerId,
            @Value("${bidding.ai-review-pipeline.batch-size:3}") int batchSize) {
        this.executor = executor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "ai-pipe-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        this.batchSize = Math.max(batchSize, 1);
        log.info("AI_REVIEW_PIPELINE Worker 已启用，workerId={} batchSize={}", this.workerId, this.batchSize);
    }

    @Scheduled(fixedDelayString = "${bidding.ai-review-pipeline.poll-ms:5000}")
    public void poll() {
        try {
            for (int i = 0; i < batchSize; i++) {
                if (!executor.claimAndRunOnce(workerId)) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("AI_REVIEW_PIPELINE Worker 轮询异常 workerId={}", workerId, ex);
        }
    }
}
