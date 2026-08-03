package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.review.infrastructure.logging.AiPipelineLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 一键审查 Worker：INDEX（子任务）→ 建清单 → REVIEW。
 * 索引失败整单失败；PROCESSING 不等待重复入队；有 SUCCESS 清单则复用。
 */
@Component
public class AiReviewPipelineJobExecutor {

    private final JobPort jobPort;
    private final JobAppService jobAppService;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;
    private final RequirementChecklistPort checklistPort;
    private final ReviewPort reviewPort;
    private final ChecklistAppService checklistAppService;
    private final ReviewAppService reviewAppService;
    private final long waitPollMs;
    private final long indexWaitTimeoutMs;
    private final long childWaitTimeoutMs;

    public AiReviewPipelineJobExecutor(
            JobPort jobPort,
            JobAppService jobAppService,
            PackageLookupPort packageLookupPort,
            DocumentAccessPort documentAccessPort,
            RequirementChecklistPort checklistPort,
            ReviewPort reviewPort,
            ChecklistAppService checklistAppService,
            ReviewAppService reviewAppService,
            @Value("${bidding.ai-review-pipeline.wait-poll-ms:3000}") long waitPollMs,
            @Value("${bidding.ai-review-pipeline.index-wait-timeout-ms:7200000}") long indexWaitTimeoutMs,
            @Value("${bidding.ai-review-pipeline.child-wait-timeout-ms:7200000}") long childWaitTimeoutMs) {
        this.jobPort = jobPort;
        this.jobAppService = jobAppService;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
        this.checklistPort = checklistPort;
        this.reviewPort = reviewPort;
        this.checklistAppService = checklistAppService;
        this.reviewAppService = reviewAppService;
        this.waitPollMs = waitPollMs < 500 ? 500 : waitPollMs;
        this.indexWaitTimeoutMs = indexWaitTimeoutMs < 60000 ? 60000 : indexWaitTimeoutMs;
        this.childWaitTimeoutMs = childWaitTimeoutMs < 60000 ? 60000 : childWaitTimeoutMs;
    }

    /**
     * 认领并执行一条总任务；无任务返回 false。
     */
    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional = jobPort.claimNextPending(
                JobPort.TYPE_AI_REVIEW_PIPELINE, workerId);
        if (optional.isEmpty()) {
            return false;
        }
        JobPort.JobRef job = optional.get();
        execute(job);
        return true;
    }

    void execute(JobPort.JobRef job) {
        long pipelineJobId = job.getId();
        long orgId = job.getOrgId();
        Long projectIdObj = job.getProjectId();
        if (projectIdObj == null) {
            fail(pipelineJobId, 0L, "INDEX", "总任务缺少 projectId");
            return;
        }
        long projectId = projectIdObj;
        long createdBy = job.getCreatedBy();

        try {
            AiPipelineLog.info(projectId, pipelineJobId, "INDEX", "开始向量化阶段");
            jobPort.markRunningProgress(pipelineJobId, 5, "向量化解析中");
            runIndexPhase(orgId, projectId, createdBy, pipelineJobId);

            AiPipelineLog.info(projectId, pipelineJobId, "CHECKLIST", "开始建清单阶段");
            jobPort.markRunningProgress(pipelineJobId, 40, "建清单中");
            ChecklistOutcome checklistOutcome = runChecklistPhase(
                    orgId, projectId, createdBy, pipelineJobId);

            AiPipelineLog.info(projectId, pipelineJobId, "REVIEW",
                    "开始审查阶段 checklistId=" + checklistOutcome.checklistId
                            + " reused=" + checklistOutcome.reused);
            jobPort.markRunningProgress(pipelineJobId, 70, "AI审查中");
            long reviewId = runReviewPhase(
                    orgId, projectId, createdBy, pipelineJobId, checklistOutcome.checklistId);

            String payload = "{\"projectId\":" + projectId
                    + ",\"phase\":\"DONE\""
                    + ",\"checklistId\":" + checklistOutcome.checklistId
                    + ",\"reviewId\":" + reviewId
                    + ",\"reusedChecklist\":" + checklistOutcome.reused
                    + "}";
            jobPort.updatePayload(pipelineJobId, payload);
            String message = "审查完成 reviewId=" + reviewId
                    + " checklistId=" + checklistOutcome.checklistId;
            jobPort.markSuccess(pipelineJobId, message);
            AiPipelineLog.info(projectId, pipelineJobId, "DONE", message);
        } catch (PipelineFailedException ex) {
            fail(pipelineJobId, projectId, ex.getPhase(), ex.getMessage());
        } catch (BizException ex) {
            fail(pipelineJobId, projectId, "ERROR", ex.getMessage());
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            fail(pipelineJobId, projectId, "ERROR", "一键审查异常: " + msg);
        }
    }

    private void runIndexPhase(long orgId, long projectId, long createdBy, long pipelineJobId) {
        List<DocumentAccessPort.DocumentRef> allDocs = listProjectDocs(orgId, projectId);
        if (allDocs.isEmpty()) {
            throw new PipelineFailedException("INDEX", "项目下没有可解析的文件");
        }

        int enqueued = 0;
        for (int i = 0; i < allDocs.size(); i++) {
            DocumentAccessPort.DocumentRef doc = allDocs.get(i);
            String status = doc.getIndexStatus();
            if (DocumentAccessPort.INDEX_SUCCESS.equals(status)) {
                continue;
            }
            if (DocumentAccessPort.INDEX_PROCESSING.equals(status)) {
                // 已在解析中：不重复入队，等待结束
                continue;
            }
            // PENDING / FAILED / 其它 → 入队 INDEX
            jobAppService.createIndexJob(orgId, projectId, doc.getId(), createdBy);
            enqueued++;
            AiPipelineLog.info(projectId, pipelineJobId, "INDEX",
                    "入队 documentId=" + doc.getId() + " fileName=" + doc.getFileName());
        }
        AiPipelineLog.info(projectId, pipelineJobId, "INDEX",
                "待解析入队=" + enqueued + " 总文件=" + allDocs.size());

        long deadline = System.currentTimeMillis() + indexWaitTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<DocumentAccessPort.DocumentRef> latest = listProjectDocs(orgId, projectId);
            int success = 0;
            int processing = 0;
            DocumentAccessPort.DocumentRef failedDoc = null;
            for (int i = 0; i < latest.size(); i++) {
                DocumentAccessPort.DocumentRef doc = latest.get(i);
                String status = doc.getIndexStatus();
                if (DocumentAccessPort.INDEX_SUCCESS.equals(status)) {
                    success++;
                } else if (DocumentAccessPort.INDEX_PROCESSING.equals(status)
                        || DocumentAccessPort.INDEX_PENDING.equals(status)) {
                    processing++;
                } else if (DocumentAccessPort.INDEX_FAILED.equals(status)) {
                    failedDoc = doc;
                    break;
                } else {
                    // 未知状态当未完成
                    processing++;
                }
            }
            if (failedDoc != null) {
                throw new PipelineFailedException("INDEX",
                        "文件索引失败: " + failedDoc.getFileName()
                                + " (documentId=" + failedDoc.getId() + ")");
            }
            if (processing == 0 && success == latest.size() && success > 0) {
                AiPipelineLog.info(projectId, pipelineJobId, "INDEX",
                        "全部解析成功 files=" + success);
                jobPort.markRunningProgress(pipelineJobId, 35, "向量化完成");
                return;
            }
            jobPort.markRunningProgress(pipelineJobId, 10 + Math.min(20, success),
                    "向量化中 success=" + success + " pending=" + processing);
            sleepQuietly(waitPollMs);
        }
        throw new PipelineFailedException("INDEX", "等待文件向量化超时");
    }

    private ChecklistOutcome runChecklistPhase(long orgId,
                                               long projectId,
                                               long createdBy,
                                               long pipelineJobId) {
        RequirementChecklistPort.ChecklistDetail existing =
                checklistPort.findLatestSuccess(orgId, projectId);
        if (existing != null
                && existing.getItems() != null
                && !existing.getItems().isEmpty()) {
            AiPipelineLog.info(projectId, pipelineJobId, "CHECKLIST",
                    "复用清单 checklistId=" + existing.getId()
                            + " version=" + existing.getVersion());
            ChecklistOutcome outcome = new ChecklistOutcome();
            outcome.checklistId = existing.getId();
            outcome.reused = true;
            return outcome;
        }

        ChecklistAppService.CreateResult created =
                checklistAppService.createInternal(orgId, projectId, createdBy, null, null);
        AiPipelineLog.info(projectId, pipelineJobId, "CHECKLIST",
                "新建清单 checklistId=" + created.getChecklistId()
                        + " childJobId=" + created.getJobId());
        waitChecklistSuccess(orgId, projectId, created.getChecklistId(), pipelineJobId);

        ChecklistOutcome outcome = new ChecklistOutcome();
        outcome.checklistId = created.getChecklistId();
        outcome.reused = false;
        return outcome;
    }

    private void waitChecklistSuccess(long orgId,
                                      long projectId,
                                      long checklistId,
                                      long pipelineJobId) {
        long deadline = System.currentTimeMillis() + childWaitTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RequirementChecklistPort.ChecklistDetail detail =
                    checklistPort.findDetail(orgId, projectId, checklistId);
            if (detail == null) {
                throw new PipelineFailedException("CHECKLIST", "清单不存在 checklistId=" + checklistId);
            }
            if (RequirementChecklistPort.STATUS_SUCCESS.equals(detail.getStatus())) {
                if (detail.getItems() == null || detail.getItems().isEmpty()) {
                    throw new PipelineFailedException("CHECKLIST", "清单成功但无条目");
                }
                return;
            }
            if (RequirementChecklistPort.STATUS_FAILED.equals(detail.getStatus())) {
                String err = detail.getErrorMessage() == null ? "" : detail.getErrorMessage();
                throw new PipelineFailedException("CHECKLIST", "建清单失败: " + err);
            }
            jobPort.markRunningProgress(pipelineJobId, 50, "建清单中 checklistId=" + checklistId);
            sleepQuietly(waitPollMs);
        }
        throw new PipelineFailedException("CHECKLIST", "等待建清单超时");
    }

    private long runReviewPhase(long orgId,
                                long projectId,
                                long createdBy,
                                long pipelineJobId,
                                long checklistId) {
        ReviewAppService.CreateResult created =
                reviewAppService.createInternal(orgId, projectId, createdBy, checklistId, null);
        AiPipelineLog.info(projectId, pipelineJobId, "REVIEW",
                "已创建审查 reviewId=" + created.getReviewId()
                        + " childJobId=" + created.getJobId());
        waitReviewSuccess(orgId, projectId, created.getReviewId(), pipelineJobId);
        return created.getReviewId();
    }

    private void waitReviewSuccess(long orgId,
                                   long projectId,
                                   long reviewId,
                                   long pipelineJobId) {
        long deadline = System.currentTimeMillis() + childWaitTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ReviewPort.ReviewDetail detail =
                    reviewPort.findDetail(orgId, projectId, reviewId);
            if (detail == null) {
                throw new PipelineFailedException("REVIEW", "审查不存在 reviewId=" + reviewId);
            }
            if (ReviewPort.STATUS_SUCCESS.equals(detail.getStatus())) {
                return;
            }
            if (ReviewPort.STATUS_FAILED.equals(detail.getStatus())) {
                String err = detail.getErrorMessage() == null ? "" : detail.getErrorMessage();
                throw new PipelineFailedException("REVIEW", "审查失败: " + err);
            }
            jobPort.markRunningProgress(pipelineJobId, 80, "AI审查中 reviewId=" + reviewId);
            sleepQuietly(waitPollMs);
        }
        throw new PipelineFailedException("REVIEW", "等待审查超时");
    }

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
            for (int i = 0; i < bids.size(); i++) {
                PackageLookupPort.PackageRef bid = bids.get(i);
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
        for (int i = 0; i < docs.size(); i++) {
            DocumentAccessPort.DocumentRef doc = docs.get(i);
            if (doc.getOrgId() != orgId) {
                continue;
            }
            if (doc.getProjectId() != null && doc.getProjectId() != projectId) {
                continue;
            }
            out.add(doc);
        }
    }

    private void fail(long pipelineJobId, long projectId, String phase, String message) {
        AiPipelineLog.error(projectId, pipelineJobId, phase, message);
        jobPort.markFailed(pipelineJobId, "[" + phase + "] " + message);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PipelineFailedException("ERROR", "任务被中断");
        }
    }

    private static class ChecklistOutcome {
        private long checklistId;
        private boolean reused;
    }

    private static class PipelineFailedException extends RuntimeException {
        private final String phase;

        private PipelineFailedException(String phase, String message) {
            super(message);
            this.phase = phase;
        }

        private String getPhase() {
            return phase;
        }
    }
}
