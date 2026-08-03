package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.ReviewJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 同进程轮询认领 REVIEW 任务。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.review", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class ReviewJobPoller {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobPoller.class);

    private final ReviewJobExecutor executor;
    private final String workerId;

    public ReviewJobPoller(
            ReviewJobExecutor executor,
            @Value("${bidding.review.worker-id:}") String configuredWorkerId) {
        this.executor = executor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "review-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        log.info("REVIEW Worker 已启用，workerId={}", this.workerId);
    }

    @Scheduled(fixedDelayString = "${bidding.review.poll-ms:3000}")
    public void poll() {
        try {
            int maxBatch = 3;
            for (int i = 0; i < maxBatch; i++) {
                boolean ran = executor.claimAndRunOnce(workerId);
                if (!ran) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("REVIEW Worker 轮询异常", ex);
        }
    }
}
