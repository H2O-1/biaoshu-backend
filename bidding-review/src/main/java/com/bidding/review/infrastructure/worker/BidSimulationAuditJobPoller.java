package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.BidSimulationAuditJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 轮询认领 BID_SIMULATION_AUDIT 任务。默认关闭：仅在配置
 * bidding.bid-simulation-audit.worker-enabled=true 的进程中加载。
 */
@Component
@ConditionalOnProperty(
        prefix = "bidding.bid-simulation-audit",
        name = "worker-enabled",
        havingValue = "true",
        matchIfMissing = false)
public class BidSimulationAuditJobPoller {

    private static final Logger log = LoggerFactory.getLogger(BidSimulationAuditJobPoller.class);

    private final BidSimulationAuditJobExecutor executor;
    private final String workerId;

    public BidSimulationAuditJobPoller(
            BidSimulationAuditJobExecutor executor,
            @Value("${bidding.bid-simulation-audit.worker-id:}") String configuredWorkerId) {
        this.executor = executor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "bid-simulation-audit-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        log.info("BID_SIMULATION_AUDIT Worker 已启用，workerId={}", this.workerId);
    }

    @Scheduled(fixedDelayString = "${bidding.bid-simulation-audit.poll-ms:3000}")
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
            log.error("BID_SIMULATION_AUDIT Worker 轮询异常 workerId={}", workerId, ex);
        }
    }
}
