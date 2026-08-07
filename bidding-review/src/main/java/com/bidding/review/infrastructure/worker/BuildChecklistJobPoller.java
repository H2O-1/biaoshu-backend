package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.BuildChecklistJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 轮询认领 BUILD_CHECKLIST 任务。默认关闭：仅在配置 bidding.checklist.worker-enabled=true
 * 的进程（bidding-worker 或单进程本地调试）中加载。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.checklist", name = "worker-enabled", havingValue = "true", matchIfMissing = false)
public class BuildChecklistJobPoller {

    private static final Logger log = LoggerFactory.getLogger(BuildChecklistJobPoller.class);

    private final BuildChecklistJobExecutor executor;
    private final String workerId;

    public BuildChecklistJobPoller(
            BuildChecklistJobExecutor executor,
            @Value("${bidding.checklist.worker-id:}") String configuredWorkerId) {
        this.executor = executor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "checklist-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        log.info("BUILD_CHECKLIST Worker 已启用，workerId={}", this.workerId);
    }

    @Scheduled(fixedDelayString = "${bidding.checklist.poll-ms:3000}")
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
            log.error("BUILD_CHECKLIST Worker 轮询异常", ex);
        }
    }
}
