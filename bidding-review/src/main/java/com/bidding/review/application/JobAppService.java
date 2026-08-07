package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 异步任务：创建 INDEX、查询、认领，以及文档 index_status 回写。
 * <p>
 * 约定（后续建清单/审查）：向量召回列表、重排列表不入库，但必须打日志便于排查。
 */
@Service
public class JobAppService {

    private final JobPort jobPort;
    private final DocumentAccessPort documentAccessPort;
    private final PackageLookupPort packageLookupPort;

    public JobAppService(JobPort jobPort,
                         DocumentAccessPort documentAccessPort,
                         PackageLookupPort packageLookupPort) {
        this.jobPort = jobPort;
        this.documentAccessPort = documentAccessPort;
        this.packageLookupPort = packageLookupPort;
    }

    /**
     * 对指定标包下的文档发起 INDEX 任务（仅招标方；本组织资源）。
     * 若文档已在 PROCESSING，拒绝重复提交。
     */
    @Transactional
    public JobSummary startDocumentIndex(long packageId, long documentId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作索引任务");
        TendererAuth.requireOwnedPackage(packageLookupPort, packageId, operator.getOrgId());

        DocumentAccessPort.DocumentRef doc = documentAccessPort.findActiveById(documentId);
        if (doc == null || doc.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (doc.getPackageId() != packageId) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (DocumentAccessPort.INDEX_PROCESSING.equals(doc.getIndexStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "索引任务进行中，请勿重复提交");
        }

        long jobId = createIndexJob(
                operator.getOrgId(),
                doc.getProjectId(),
                documentId,
                operator.getUserId()
        );
        Optional<JobPort.JobRef> optional = jobPort.findById(jobId);
        if (optional.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "创建任务后查询失败");
        }
        return toSummary(optional.get());
    }

    /**
     * 查询任务详情（仅本组织；招标方）。
     * <p>
     * 前端既可短轮询本接口，也可改用 {@code GET /api/jobs/{jobId}/events}（SSE）收进度；
     * 刷新/断线后仍建议先调本接口拿当前快照。
     */
    @Transactional(readOnly = true)
    public JobSummary getJob(long jobId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作索引任务");
        Optional<JobPort.JobRef> optional = jobPort.findById(jobId);
        if (optional.isEmpty() || optional.get().getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return toSummary(optional.get());
    }

    /**
     * 写入一条 INDEX 任务（PENDING），并把文档 index_status 置为 PROCESSING。
     *
     * @return jobId
     */
    @Transactional
    public long createIndexJob(long orgId, Long projectId, long documentId, long createdBy) {
        String payload = "{\"documentId\":" + documentId + "}";
        long jobId = jobPort.insert(
                orgId,
                projectId,
                JobPort.TYPE_INDEX,
                JobPort.STATUS_PENDING,
                payload,
                createdBy
        );
        documentAccessPort.updateIndexStatus(documentId, DocumentAccessPort.INDEX_PROCESSING);
        return jobId;
    }

    @Transactional(readOnly = true)
    public Optional<JobPort.JobRef> findJob(long jobId) {
        return jobPort.findById(jobId);
    }

    /**
     * 认领下一条 INDEX 任务。
     */
    public Optional<JobPort.JobRef> claimNextIndex(String workerId) {
        return jobPort.claimNextPending(JobPort.TYPE_INDEX, workerId);
    }

    public void markIndexSuccess(long jobId, long documentId, String message) {
        jobPort.markSuccess(jobId, message);
        documentAccessPort.updateIndexStatus(documentId, DocumentAccessPort.INDEX_SUCCESS);
    }

    public void markIndexFailed(long jobId, long documentId, String errorMessage) {
        jobPort.markFailed(jobId, errorMessage);
        documentAccessPort.updateIndexStatus(documentId, DocumentAccessPort.INDEX_FAILED);
    }

    /** 仅失败任务（文档 ID 未知时用）。 */
    public void markJobFailed(long jobId, String errorMessage) {
        jobPort.markFailed(jobId, errorMessage);
    }

    public void markRunningProgress(long jobId, int progress, String message) {
        jobPort.markRunningProgress(jobId, progress, message);
    }

    /** 供 SSE 等同包组件复用摘要映射。 */
    JobSummary toSummary(JobPort.JobRef job) {
        JobSummary summary = new JobSummary();
        summary.setId(job.getId());
        summary.setOrgId(job.getOrgId());
        summary.setProjectId(job.getProjectId());
        summary.setType(job.getType());
        summary.setStatus(job.getStatus());
        summary.setProgress(job.getProgress());
        summary.setMessage(job.getMessage());
        summary.setPayloadJson(job.getPayloadJson());
        summary.setErrorMessage(job.getErrorMessage());
        summary.setRetryCount(job.getRetryCount());
        summary.setCreatedBy(job.getCreatedBy());
        summary.setCreatedAt(job.getCreatedAt());
        summary.setStartedAt(job.getStartedAt());
        summary.setFinishedAt(job.getFinishedAt());
        summary.setUpdatedAt(job.getUpdatedAt());
        // 一键审查成功后从 payload 抽出结果 id，方便前端直接读
        if (JobPort.TYPE_AI_REVIEW_PIPELINE.equals(job.getType())) {
            summary.setChecklistId(extractJsonLong(job.getPayloadJson(), "checklistId"));
            summary.setReviewId(extractJsonLong(job.getPayloadJson(), "reviewId"));
        }
        return summary;
    }

    /** 简易抽取 JSON 数字字段（不引入额外依赖）。 */
    static Long extractJsonLong(String json, String field) {
        if (json == null || field == null || field.isEmpty()) {
            return null;
        }
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '-' || (c >= '0' && c <= '9')) {
                end++;
            } else {
                break;
            }
        }
        if (end <= start) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 任务摘要（前端轮询用）。 */
    public static class JobSummary {
        private long id;
        private long orgId;
        private Long projectId;
        private String type;
        private String status;
        private int progress;
        private String message;
        private String payloadJson;
        private String errorMessage;
        private int retryCount;
        private long createdBy;
        private Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private Instant updatedAt;
        /** 一键审查结果：清单 id（可能为空） */
        private Long checklistId;
        /** 一键审查结果：审查 id（可能为空） */
        private Long reviewId;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getProgress() {
            return progress;
        }

        public void setProgress(int progress) {
            this.progress = progress;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getPayloadJson() {
            return payloadJson;
        }

        public void setPayloadJson(String payloadJson) {
            this.payloadJson = payloadJson;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }

        public Instant getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public Long getChecklistId() {
            return checklistId;
        }

        public void setChecklistId(Long checklistId) {
            this.checklistId = checklistId;
        }

        public Long getReviewId() {
            return reviewId;
        }

        public void setReviewId(Long reviewId) {
            this.reviewId = reviewId;
        }
    }
}
