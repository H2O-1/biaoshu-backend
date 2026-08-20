package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.review.infrastructure.logging.AiPipelineLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一键审查总任务执行器：PostgreSQL 驱动的持久化状态机。
 * <p>
 * 阶段：INDEX_SUBMIT → INDEX_WAIT → CHECKLIST_SUBMIT → CHECKLIST_WAIT
 * → REVIEW_SUBMIT → REVIEW_WAIT → SUCCESS / FAILED。
 * 每次 {@link #claimAndRunOnce} 认领一条到期任务、最多推进一个阶段并立即返回；
 * 等待子任务只设置 next_run_at 推迟，不做任何 Thread.sleep / 轮询等待。
 * <p>
 * 并发与幂等约定：
 * <ul>
 *   <li>认领基于 FOR UPDATE SKIP LOCKED + 租约；阶段推进以
 *       jobId + workerId + expectedPhase + status=RUNNING 为条件，更新行数不为 1
 *       说明租约易主，必须停止且不得覆盖其他 Worker 的结果；</li>
 *   <li>SUBMIT 阶段的「创建子任务 + 保存子任务 ID + 推进父任务」在同一数据库事务，
 *       提交前崩溃整体回滚，重启后按当前阶段续跑；</li>
 *   <li>重入时复用已完成产物（SUCCESS 文件索引跳过、PROCESSING 不重复提交、
 *       最新非空 SUCCESS 清单直接复用），不无条件从头执行。</li>
 * </ul>
 */
@Component
public class AiReviewPipelineJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(AiReviewPipelineJobExecutor.class);

    private final JobPort jobPort;
    private final JobAppService jobAppService;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;
    private final RequirementChecklistPort checklistPort;
    private final ReviewPort reviewPort;
    private final ChecklistAppService checklistAppService;
    private final ReviewAppService reviewAppService;
    private final TransactionTemplate transactionTemplate;
    private final int leaseSeconds;
    private final long waitIntervalMs;
    private final long indexWaitTimeoutMs;
    private final long childWaitTimeoutMs;
    private final int maxRetries;
    private final long retryBackoffMs;

    public AiReviewPipelineJobExecutor(
            JobPort jobPort,
            JobAppService jobAppService,
            PackageLookupPort packageLookupPort,
            DocumentAccessPort documentAccessPort,
            RequirementChecklistPort checklistPort,
            ReviewPort reviewPort,
            ChecklistAppService checklistAppService,
            ReviewAppService reviewAppService,
            PlatformTransactionManager transactionManager,
            @Value("${bidding.ai-review-pipeline.lease-seconds:60}") int leaseSeconds,
            @Value("${bidding.ai-review-pipeline.wait-poll-ms:3000}") long waitIntervalMs,
            @Value("${bidding.ai-review-pipeline.index-wait-timeout-ms:7200000}") long indexWaitTimeoutMs,
            @Value("${bidding.ai-review-pipeline.child-wait-timeout-ms:7200000}") long childWaitTimeoutMs,
            @Value("${bidding.ai-review-pipeline.max-retries:3}") int maxRetries,
            @Value("${bidding.ai-review-pipeline.retry-backoff-ms:10000}") long retryBackoffMs) {
        this.jobPort = jobPort;
        this.jobAppService = jobAppService;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
        this.checklistPort = checklistPort;
        this.reviewPort = reviewPort;
        this.checklistAppService = checklistAppService;
        this.reviewAppService = reviewAppService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.leaseSeconds = Math.max(leaseSeconds, 10);
        this.waitIntervalMs = Math.max(waitIntervalMs, 500);
        this.indexWaitTimeoutMs = Math.max(indexWaitTimeoutMs, 60000);
        this.childWaitTimeoutMs = Math.max(childWaitTimeoutMs, 60000);
        this.maxRetries = Math.max(maxRetries, 0);
        this.retryBackoffMs = Math.max(retryBackoffMs, 1000);
    }

    /**
     * 认领一条到期的总任务并最多推进一个阶段，随后立即返回；无任务返回 false。
     * 单次执行时间应很短（SUBMIT 只建子任务，WAIT 只查一次状态）。
     */
    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional = jobPort.claimNextRunnablePipeline(
                JobPort.TYPE_AI_REVIEW_PIPELINE, workerId, leaseSeconds);
        if (optional.isEmpty()) {
            return false;
        }
        JobPort.JobRef job = optional.get();
        try {
            advanceOnePhase(job, workerId);
        } catch (LeaseLostException ex) {
            log.info("pipeline 租约已易主，停止本次处理 pipelineJobId={} projectId={} workerId={}",
                    job.getId(), job.getProjectId(), workerId);
        } catch (PipelineFatalException ex) {
            failPipeline(job, workerId, ex.getMessage());
        } catch (BizException ex) {
            // 参数缺失、文件不存在、子任务明确失败等业务错误：直接终态失败，不计重试
            failPipeline(job, workerId, ex.getMessage());
        } catch (Exception ex) {
            retryOrFail(job, workerId, ex);
        }
        return true;
    }

    private void advanceOnePhase(JobPort.JobRef job, String workerId) {
        String phase = phaseOf(job);
        switch (phase) {
            case JobPort.PHASE_INDEX_SUBMIT ->
                    transactionTemplate.executeWithoutResult(tx -> submitIndex(job, workerId));
            case JobPort.PHASE_INDEX_WAIT -> checkIndex(job, workerId);
            case JobPort.PHASE_CHECKLIST_SUBMIT ->
                    transactionTemplate.executeWithoutResult(tx -> submitChecklist(job, workerId));
            case JobPort.PHASE_CHECKLIST_WAIT -> checkChecklist(job, workerId);
            case JobPort.PHASE_REVIEW_SUBMIT ->
                    transactionTemplate.executeWithoutResult(tx -> submitReview(job, workerId));
            case JobPort.PHASE_REVIEW_WAIT -> checkReview(job, workerId);
            default -> throw new PipelineFatalException("未知流水线阶段: " + phase);
        }
    }

    /**
     * INDEX_SUBMIT：为 PENDING/FAILED 文件创建 INDEX 子任务；
     * SUCCESS 跳过、PROCESSING 不重复提交（重入幂等），随后推进 INDEX_WAIT。
     */
    private void submitIndex(JobPort.JobRef job, String workerId) {
        long projectId = requireProjectId(job);
        List<DocumentAccessPort.DocumentRef> allDocs = listProjectDocs(job.getOrgId(), projectId);
        if (allDocs.isEmpty()) {
            throw new PipelineFatalException("项目下没有可解析的文件");
        }

        int enqueued = 0;
        for (DocumentAccessPort.DocumentRef doc : allDocs) {
            String status = doc.getIndexStatus();
            if (DocumentAccessPort.INDEX_SUCCESS.equals(status)
                    || DocumentAccessPort.INDEX_PROCESSING.equals(status)) {
                continue;
            }
            jobAppService.createIndexJob(job.getOrgId(), projectId, doc.getId(), job.getCreatedBy());
            enqueued++;
            AiPipelineLog.info(projectId, job.getId(), "INDEX",
                    "入队 documentId=" + doc.getId() + " fileName=" + doc.getFileName());
        }
        AiPipelineLog.info(projectId, job.getId(), "INDEX",
                "待解析入队=" + enqueued + " 总文件=" + allDocs.size());
        advanceOrThrow(job, workerId, JobPort.PHASE_INDEX_WAIT, 10,
                "向量化解析中", nextRunFromNow(waitIntervalMs), null, null, null);
    }

    /**
     * INDEX_WAIT：只检查一次文件状态。全部 SUCCESS 推进 CHECKLIST_SUBMIT（立即可运行）；
     * 存在 FAILED 整单失败；仍在处理则推迟 next_run_at（不计重试）；超过阶段时限失败。
     */
    private void checkIndex(JobPort.JobRef job, String workerId) {
        long projectId = requireProjectId(job);
        List<DocumentAccessPort.DocumentRef> docs = listProjectDocs(job.getOrgId(), projectId);
        if (docs.isEmpty()) {
            throw new PipelineFatalException("项目下没有可解析的文件");
        }

        int success = 0;
        int inFlight = 0;
        DocumentAccessPort.DocumentRef failedDoc = null;
        for (DocumentAccessPort.DocumentRef doc : docs) {
            String status = doc.getIndexStatus();
            if (DocumentAccessPort.INDEX_SUCCESS.equals(status)) {
                success++;
            } else if (DocumentAccessPort.INDEX_FAILED.equals(status)) {
                failedDoc = doc;
                break;
            } else {
                // PENDING / PROCESSING / 未知状态：视为未完成
                inFlight++;
            }
        }
        if (failedDoc != null) {
            throw new PipelineFatalException("文件解析失败：" + failedDoc.getFileName());
        }
        if (inFlight == 0 && success == docs.size()) {
            advanceOrThrow(job, workerId, JobPort.PHASE_CHECKLIST_SUBMIT, 35,
                    "向量化完成", Instant.now(), null, null, null);
            return;
        }
        if (phaseTimedOut(job, indexWaitTimeoutMs)) {
            throw new PipelineFatalException("等待文件向量化超时");
        }
        advanceOrThrow(job, workerId, JobPort.PHASE_INDEX_WAIT, 10 + Math.min(20, success),
                "文件解析中",
                nextRunFromNow(waitIntervalMs), null, null, null);
    }

    /**
     * CHECKLIST_SUBMIT：优先复用项目最新且非空的 SUCCESS 清单（保存 checklist_id 后直接
     * 推进 REVIEW_SUBMIT）；否则在同一事务内创建清单 + BUILD_CHECKLIST 子任务并推进 CHECKLIST_WAIT。
     */
    private void submitChecklist(JobPort.JobRef job, String workerId) {
        long projectId = requireProjectId(job);
        RequirementChecklistPort.ChecklistDetail existing =
                checklistPort.findLatestSuccess(job.getOrgId(), projectId);
        if (existing != null && existing.getItems() != null && !existing.getItems().isEmpty()) {
            AiPipelineLog.info(projectId, job.getId(), "CHECKLIST",
                    "复用清单 checklistId=" + existing.getId() + " version=" + existing.getVersion());
            advanceOrThrow(job, workerId, JobPort.PHASE_REVIEW_SUBMIT, 55,
                    "复用清单", Instant.now(),
                    existing.getId(), null, checklistPayload(projectId, existing.getId(), true));
            return;
        }

        ChecklistAppService.CreateResult created =
                checklistAppService.createInternal(job.getOrgId(), projectId, job.getCreatedBy(), null, null);
        AiPipelineLog.info(projectId, job.getId(), "CHECKLIST",
                "新建清单 checklistId=" + created.getChecklistId()
                        + " childJobId=" + created.getJobId());
        advanceOrThrow(job, workerId, JobPort.PHASE_CHECKLIST_WAIT, 45,
                "建清单中", nextRunFromNow(waitIntervalMs),
                created.getChecklistId(), null, checklistPayload(projectId, created.getChecklistId(), false));
    }

    /**
     * CHECKLIST_WAIT：只检查一次 checklist_id 对应清单。SUCCESS 且有条目推进 REVIEW_SUBMIT；
     * FAILED / OBSOLETE 失败；仍在构建推迟 next_run_at；超过阶段时限失败。
     */
    private void checkChecklist(JobPort.JobRef job, String workerId) {
        long projectId = requireProjectId(job);
        Long checklistId = job.getChecklistId();
        if (checklistId == null) {
            throw new PipelineFatalException("CHECKLIST_WAIT 缺少 checklistId");
        }
        RequirementChecklistPort.ChecklistDetail detail =
                checklistPort.findDetail(job.getOrgId(), projectId, checklistId);
        if (detail == null) {
            throw new PipelineFatalException("清单不存在 checklistId=" + checklistId);
        }
        String status = detail.getStatus();
        if (RequirementChecklistPort.STATUS_SUCCESS.equals(status)) {
            if (detail.getItems() == null || detail.getItems().isEmpty()) {
                throw new PipelineFatalException("清单成功但无条目 checklistId=" + checklistId);
            }
            advanceOrThrow(job, workerId, JobPort.PHASE_REVIEW_SUBMIT, 55,
                    "清单完成", Instant.now(), null, null, null);
            return;
        }
        if (RequirementChecklistPort.STATUS_FAILED.equals(status)) {
            throw new PipelineFatalException("建清单失败: " + safeError(detail.getErrorMessage()));
        }
        if (RequirementChecklistPort.STATUS_OBSOLETE.equals(status)) {
            throw new PipelineFatalException("清单已作废 checklistId=" + checklistId);
        }
        if (phaseTimedOut(job, childWaitTimeoutMs)) {
            throw new PipelineFatalException("等待建清单超时 checklistId=" + checklistId);
        }
        advanceOrThrow(job, workerId, JobPort.PHASE_CHECKLIST_WAIT, 50,
                "建清单中", nextRunFromNow(waitIntervalMs), null, null, null);
    }

    /**
     * REVIEW_SUBMIT：使用 checklist_id 在同一事务内创建审查 + REVIEW 子任务，
     * 保存 review_id 并推进 REVIEW_WAIT。
     */
    private void submitReview(JobPort.JobRef job, String workerId) {
        long projectId = requireProjectId(job);
        Long checklistId = job.getChecklistId();
        if (checklistId == null) {
            throw new PipelineFatalException("REVIEW_SUBMIT 缺少 checklistId");
        }
        ReviewAppService.CreateResult created = reviewAppService.createInternal(
                job.getOrgId(), projectId, job.getCreatedBy(), checklistId, null);
        AiPipelineLog.info(projectId, job.getId(), "REVIEW",
                "已创建审查 reviewId=" + created.getReviewId() + " childJobId=" + created.getJobId());
        advanceOrThrow(job, workerId, JobPort.PHASE_REVIEW_WAIT, 70,
                "AI审查中", nextRunFromNow(waitIntervalMs),
                null, created.getReviewId(), null);
    }

    /**
     * REVIEW_WAIT：只检查一次 review_id 对应审查。SUCCESS 写兼容 payload 并结束总任务；
     * FAILED / OBSOLETE 失败；仍在构建推迟 next_run_at；超过阶段时限失败。
     */
    private void checkReview(JobPort.JobRef job, String workerId) {
        long projectId = requireProjectId(job);
        Long reviewId = job.getReviewId();
        if (reviewId == null) {
            throw new PipelineFatalException("REVIEW_WAIT 缺少 reviewId");
        }
        ReviewPort.ReviewDetail detail = reviewPort.findDetail(job.getOrgId(), projectId, reviewId);
        if (detail == null) {
            throw new PipelineFatalException("审查不存在 reviewId=" + reviewId);
        }
        String status = detail.getStatus();
        if (ReviewPort.STATUS_SUCCESS.equals(status)) {
            String payload = "{\"projectId\":" + projectId
                    + ",\"phase\":\"DONE\""
                    + ",\"checklistId\":" + job.getChecklistId()
                    + ",\"reviewId\":" + reviewId
                    + ",\"reusedChecklist\":" + reusedChecklist(job)
                    + "}";
            String message = "审查完成";
            boolean updated = jobPort.succeedPipeline(
                    job.getId(), workerId, phaseOf(job), message, payload);
            if (!updated) {
                throw new LeaseLostException();
            }
            AiPipelineLog.info(projectId, job.getId(), "DONE", message);
            return;
        }
        if (ReviewPort.STATUS_FAILED.equals(status)) {
            throw new PipelineFatalException("审查失败: " + safeError(detail.getErrorMessage()));
        }
        if (ReviewPort.STATUS_OBSOLETE.equals(status)) {
            throw new PipelineFatalException("审查已作废 reviewId=" + reviewId);
        }
        if (phaseTimedOut(job, childWaitTimeoutMs)) {
            throw new PipelineFatalException("等待审查超时 reviewId=" + reviewId);
        }
        advanceOrThrow(job, workerId, JobPort.PHASE_REVIEW_WAIT, 80,
                "AI审查中", nextRunFromNow(waitIntervalMs), null, null, null);
    }

    /**
     * 以 jobId + workerId + expectedPhase 为条件推进阶段并释放本次租约；
     * 未命中（租约易主）抛 {@link LeaseLostException}，SUBMIT 阶段据此整体回滚。
     */
    private void advanceOrThrow(JobPort.JobRef job, String workerId, String nextPhase,
                                int progress, String message, Instant nextRunAt,
                                Long checklistId, Long reviewId, String payloadJson) {
        boolean updated = jobPort.advancePipeline(job.getId(), workerId, phaseOf(job), nextPhase,
                progress, message, nextRunAt, checklistId, reviewId, payloadJson);
        if (!updated) {
            throw new LeaseLostException();
        }
    }

    /**
     * 终态失败（参数缺失、文件不存在、子任务明确失败等）；
     * 租约易主时不再覆盖其他 Worker 的结果。
     */
    private void failPipeline(JobPort.JobRef job, String workerId, String message) {
        long projectId = job.getProjectId() == null ? 0L : job.getProjectId();
        AiPipelineLog.error(projectId, job.getId(), phaseOf(job), message);
        boolean updated = jobPort.failPipeline(job.getId(), workerId, phaseOf(job), message);
        if (!updated) {
            log.info("标记失败未命中（租约易主） pipelineJobId={} projectId={} workerId={}",
                    job.getId(), job.getProjectId(), workerId);
        }
    }

    /**
     * 瞬时错误（网络超时、限流、数据库抖动等）：retry_count + 1 并指数退避；
     * 达到最大重试次数转终态失败。正常等待子任务不走此方法，不占重试次数。
     */
    private void retryOrFail(JobPort.JobRef job, String workerId, Exception ex) {
        String detail = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        int nextRetry = job.getRetryCount() + 1;
        if (nextRetry > maxRetries) {
            failPipeline(job, workerId, "重试 " + maxRetries + " 次后仍失败: " + detail);
            return;
        }
        long backoffMs = Math.min(retryBackoffMs * (1L << Math.min(nextRetry - 1, 10)),
                retryBackoffMs * 60);
        log.warn("pipeline 阶段异常，{}ms 后第 {}/{} 次重试 pipelineJobId={} projectId={} phase={} workerId={} err={}",
                backoffMs, nextRetry, maxRetries, job.getId(), job.getProjectId(), phaseOf(job), workerId,
                abbreviate(detail));
        boolean updated = jobPort.retryPipeline(job.getId(), workerId, phaseOf(job),
                "一键审查异常: " + detail, Instant.now().plusMillis(backoffMs));
        if (!updated) {
            log.info("重试登记未命中（租约易主） pipelineJobId={} projectId={} workerId={}",
                    job.getId(), job.getProjectId(), workerId);
        }
    }

    /** 当前阶段；认领时已把空阶段初始化为 INDEX_SUBMIT，这里再兜底一次。 */
    private static String phaseOf(JobPort.JobRef job) {
        return job.getPhase() == null ? JobPort.PHASE_INDEX_SUBMIT : job.getPhase();
    }

    /** 等待阶段是否超过配置时限（自 phase_started_at 起算；为空视为未超时）。 */
    private boolean phaseTimedOut(JobPort.JobRef job, long timeoutMs) {
        Instant phaseStartedAt = job.getPhaseStartedAt();
        if (phaseStartedAt == null) {
            return false;
        }
        return Instant.now().isAfter(phaseStartedAt.plusMillis(timeoutMs));
    }

    private static long requireProjectId(JobPort.JobRef job) {
        Long projectId = job.getProjectId();
        if (projectId == null) {
            throw new PipelineFatalException("总任务缺少 projectId");
        }
        return projectId;
    }

    private static Instant nextRunFromNow(long delayMs) {
        return Instant.now().plusMillis(delayMs);
    }

    /** CHECKLIST_SUBMIT 写入的兼容 payload，记录清单来源供最终 DONE payload 还原。 */
    private static String checklistPayload(long projectId, long checklistId, boolean reused) {
        return "{\"projectId\":" + projectId
                + ",\"phase\":\"CHECKLIST\""
                + ",\"checklistId\":" + checklistId
                + ",\"reusedChecklist\":" + reused
                + "}";
    }

    /** 从当前 payload 还原 reusedChecklist 标记（CHECKLIST_SUBMIT 时写入）。 */
    private static boolean reusedChecklist(JobPort.JobRef job) {
        return job.getPayloadJson() != null && job.getPayloadJson().contains("\"reusedChecklist\":true");
    }

    private static String safeError(String errorMessage) {
        return errorMessage == null ? "" : errorMessage;
    }

    /** 日志用错误摘要，避免超长堆栈刷屏。 */
    private static String abbreviate(String detail) {
        return detail != null && detail.length() > 300 ? detail.substring(0, 300) : detail;
    }

    /** 项目全部有效招标与投标文件（本组织）。 */
    private List<DocumentAccessPort.DocumentRef> listProjectDocs(long orgId, long projectId) {
        List<DocumentAccessPort.DocumentRef> result = new ArrayList<>();
        PackageLookupPort.PackageRef tender =
                packageLookupPort.findActiveByProjectIdAndType(
                        projectId, AiReviewPipelineAppService.PACKAGE_TYPE_TENDER);
        if (tender != null && tender.getOrgId() == orgId) {
            appendDocs(result, tender.getId(), orgId, projectId);
        }
        List<PackageLookupPort.PackageRef> bids =
                packageLookupPort.listActiveByProjectIdAndType(
                        projectId, AiReviewPipelineAppService.PACKAGE_TYPE_BID);
        if (bids != null) {
            for (PackageLookupPort.PackageRef bid : bids) {
                if (bid.getOrgId() != orgId) {
                    continue;
                }
                appendDocs(result, bid.getId(), orgId, projectId);
            }
        }
        return result;
    }

    private void appendDocs(List<DocumentAccessPort.DocumentRef> out,
                            long packageId,
                            long orgId,
                            long projectId) {
        List<DocumentAccessPort.DocumentRef> docs =
                documentAccessPort.listActiveByPackageId(packageId);
        if (docs == null) {
            return;
        }
        for (DocumentAccessPort.DocumentRef doc : docs) {
            if (doc.getOrgId() != orgId) {
                continue;
            }
            if (doc.getProjectId() != null && doc.getProjectId() != projectId) {
                continue;
            }
            out.add(doc);
        }
    }

    /** 租约已易主：条件更新未命中，当前 Worker 必须停止处理。 */
    private static class LeaseLostException extends RuntimeException {
        private LeaseLostException() {
            super("pipeline 租约已易主");
        }
    }

    /** 终态业务失败：不计重试，直接 FAILED。 */
    private static class PipelineFatalException extends RuntimeException {
        private PipelineFatalException(String message) {
            super(message);
        }
    }
}
