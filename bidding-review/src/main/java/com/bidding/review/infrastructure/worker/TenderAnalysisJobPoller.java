package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.TenderAnalysisJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 轮询认领 TENDER_ANALYSIS 任务。默认关闭：仅在配置
 * bidding.tender-analysis.worker-enabled=true 的进程中加载。
 */
@Component
@ConditionalOnProperty(
        prefix = "bidding.tender-analysis",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class TenderAnalysisJobPoller {

    private static final Logger log = LoggerFactory.getLogger(TenderAnalysisJobPoller.class);

    private final TenderAnalysisJobExecutor executor;
    private final String workerId;

    public TenderAnalysisJobPoller(
            TenderAnalysisJobExecutor executor,
            @Value("${bidding.tender-analysis.worker-id:}") String configuredWorkerId) {
        this.executor = executor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "tender-analysis-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        log.info("TENDER_ANALYSIS Worker 已启用，workerId={}", this.workerId);
    }

    @Scheduled(fixedDelayString = "${bidding.tender-analysis.poll-ms:3000}")
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
            log.error("TENDER_ANALYSIS Worker 轮询异常 workerId={}", workerId, ex);
        }
    }
}
