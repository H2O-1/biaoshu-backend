package com.bidding.review.infrastructure.worker;

import com.bidding.common.port.JobPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 【临时管理功能，默认禁用，仅限人工显式开启】僵尸子任务回收。
 * <p>
 * 普通 INDEX / BUILD_CHECKLIST / REVIEW 子任务暂无租约与心跳机制，
 * 按 locked_at / updated_at 早于阈值（默认 30 分钟）判定僵尸任务，
 * <b>可能把仍在正常执行的长任务误杀</b>。因此在子任务统一租约化之前，
 * 本功能在 API 与 Worker 进程一律默认关闭（bidding.recovery.enabled=false），
 * 不允许任何实例在启动时自动执行全局恢复；仅运维确认需要时人工显式开启，
 * 开启后启动日志会打印高可见度警告。
 * <p>
 * AI_REVIEW_PIPELINE 总任务永远不属于本类处理范围：RUNNING 总任务由
 * claimNextRunnablePipeline 在租约过期后自动接管续跑。
 * <p>
 * 剩余风险（已记录，后续任务处理）：普通子任务尚未统一租约化，Worker 异常
 * 中断留下的 RUNNING 子任务不会被自动回收，需要人工核对后处理；
 * 不要在本次改造中顺便重构全部子任务。
 */
@Component
@ConditionalOnProperty(
        prefix = "bidding.recovery",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class StaleAiWorkRecovery {

    private static final Logger log = LoggerFactory.getLogger(StaleAiWorkRecovery.class);

    private static final String REASON = "服务重启，任务中断，请重新发起";

    private final JdbcTemplate jdbcTemplate;
    private final long staleMinutes;

    public StaleAiWorkRecovery(JdbcTemplate jdbcTemplate,
                               @Value("${bidding.recovery.stale-minutes:30}") long staleMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.staleMinutes = Math.max(staleMinutes, 1);
        log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        log.warn("!!! 僵尸子任务回收已【人工显式开启】：启动时将把锁定/更新早于 {} 分钟的", this.staleMinutes);
        log.warn("!!! RUNNING 子任务、BUILDING 清单/审查、PROCESSING 文档标记为 FAILED，");
        log.warn("!!! 可能中断其他 Worker 仍在正常执行的长任务。确认无误请保持，否则请关闭");
        log.warn("!!! bidding.recovery.enabled。该功能为临时管理功能，子任务租约化后移除。");
        log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        Instant staleBefore = Instant.now().minusSeconds(staleMinutes * 60);
        Timestamp threshold = Timestamp.from(staleBefore);

        int jobs = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'FAILED', message = '失败', error_message = ?, "
                        + "finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE status = ? AND type IN (?, ?, ?) "
                        + "AND (locked_at IS NULL OR locked_at < ?)",
                REASON,
                JobPort.STATUS_RUNNING,
                JobPort.TYPE_INDEX,
                JobPort.TYPE_BUILD_CHECKLIST,
                JobPort.TYPE_REVIEW,
                threshold
        );
        int reviews = jdbcTemplate.update(
                "UPDATE biz_review SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE status = ? AND updated_at < ?",
                ReviewPort.STATUS_FAILED,
                REASON,
                ReviewPort.STATUS_BUILDING,
                threshold
        );
        int checklists = jdbcTemplate.update(
                "UPDATE biz_requirement_checklist SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE status = ? AND updated_at < ?",
                RequirementChecklistPort.STATUS_FAILED,
                REASON,
                RequirementChecklistPort.STATUS_BUILDING,
                threshold
        );
        // 被掐断的 INDEX 可能把文档留在 PROCESSING，回写 FAILED 便于重新解析
        int docs = jdbcTemplate.update(
                "UPDATE biz_document SET index_status = 'FAILED', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE status <> 'DELETED' AND index_status = 'PROCESSING' AND updated_at < ?",
                threshold
        );
        if (jobs > 0 || reviews > 0 || checklists > 0 || docs > 0) {
            log.warn("启动回收僵尸 AI 工作（早于 {}）: runningJobs={} buildingReviews={} buildingChecklists={} processingDocs={}",
                    staleBefore, jobs, reviews, checklists, docs);
        } else {
            log.info("启动回收：无早于 {} 的 RUNNING/BUILDING/PROCESSING 任务", staleBefore);
        }
    }
}
