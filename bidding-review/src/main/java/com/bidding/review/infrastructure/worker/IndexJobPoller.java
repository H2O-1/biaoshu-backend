package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.IndexJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 同进程轮询认领 INDEX 任务（M3；后续可拆独立 bidding-worker）。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.index", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class IndexJobPoller {

    private static final Logger log = LoggerFactory.getLogger(IndexJobPoller.class);

    private final IndexJobExecutor indexJobExecutor;
    private final String workerId;

    public IndexJobPoller(IndexJobExecutor indexJobExecutor,
                          @Value("${bidding.index.worker-id:}") String configuredWorkerId) {
        this.indexJobExecutor = indexJobExecutor;
        if (configuredWorkerId != null && !configuredWorkerId.trim().isEmpty()) {
            this.workerId = configuredWorkerId.trim();
        } else {
            this.workerId = "api-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        log.info("INDEX Worker 已启用，workerId={}", this.workerId);
    }

    @Scheduled(fixedDelayString = "${bidding.index.poll-ms:2000}")
    public void poll() {
        try {
            // 一次最多连跑若干条，避免长时间占满调度线程
            int maxBatch = 5;
            for (int i = 0; i < maxBatch; i++) {
                boolean ran = indexJobExecutor.claimAndRunOnce(workerId);
                if (!ran) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.error("INDEX Worker 轮询异常", ex);
        }
    }
}
