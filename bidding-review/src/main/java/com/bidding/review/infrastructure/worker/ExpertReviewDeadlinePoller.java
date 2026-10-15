package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.ExpertReviewAppService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 轮询收口已到截止时间的专家评审。
 */
@Component
@ConditionalOnProperty(
        prefix = "bidding.expert-review",
        name = "deadline-worker-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ExpertReviewDeadlinePoller {

    private static final Logger log = LoggerFactory.getLogger(ExpertReviewDeadlinePoller.class);

    private final ExpertReviewAppService expertReviewAppService;
    private final int batchSize;

    public ExpertReviewDeadlinePoller(
            ExpertReviewAppService expertReviewAppService,
            @Value("${bidding.expert-review.deadline-batch-size:20}") int batchSize) {
        this.expertReviewAppService = expertReviewAppService;
        this.batchSize = Math.max(batchSize, 1);
        log.info("EXPERT_REVIEW_DEADLINE Worker 已启用 batchSize={}", this.batchSize);
    }

    @Scheduled(fixedDelayString = "${bidding.expert-review.deadline-poll-ms:60000}")
    public void poll() {
        try {
            for (int i = 0; i < batchSize; i++) {
                if (!expertReviewAppService.autoSubmitOneExpiredReview()) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("EXPERT_REVIEW_DEADLINE Worker 轮询异常", ex);
        }
    }
}
