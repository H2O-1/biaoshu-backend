package com.bidding.common.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 异步任务持久化（实现在 review 模块）。
 */
public interface JobPort {

    String TYPE_INDEX = "INDEX";
    /** 按维度建招标要求清单。 */
    String TYPE_BUILD_CHECKLIST = "BUILD_CHECKLIST";
    /** 按清单对照投标。 */
    String TYPE_REVIEW = "REVIEW";
    /** 一键审查总流程：INDEX → 建清单 → REVIEW。 */
    String TYPE_AI_REVIEW_PIPELINE = "AI_REVIEW_PIPELINE";
    String STATUS_PENDING = "PENDING";
    String STATUS_RUNNING = "RUNNING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";

    long insert(long orgId,
                Long projectId,
                String type,
                String status,
                String payloadJson,
                long createdBy);

    Optional<JobRef> findById(long jobId);

    /**
     * 认领一条 PENDING 任务；成功返回任务，无可用任务返回 empty。
     */
    Optional<JobRef> claimNextPending(String type, String workerId);

    void markRunningProgress(long jobId, int progress, String message);

    void markSuccess(long jobId, String message);

    void markFailed(long jobId, String errorMessage);

    /** 更新 payload（流水线写入 phase / reviewId 等）。 */
    void updatePayload(long jobId, String payloadJson);

    /**
     * 项目下是否存在指定类型且状态为 PENDING/RUNNING 的任务（防双开）。
     */
    boolean existsActiveByProjectIdAndType(long projectId, String type);

    class JobRef {
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
        private String workerId;
        private Instant createdAt;
        private Instant startedAt;
        private Instant finishedAt;
        private Instant updatedAt;

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

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
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
    }
}
