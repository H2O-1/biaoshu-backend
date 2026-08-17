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
    /** 独立招标文件解析：INDEX → 基础信息 / 资格要求 / 评分标准抽取。 */
    String TYPE_TENDER_ANALYSIS = "TENDER_ANALYSIS";
    /** 投标模拟审核：INDEX → 招标要求抽取 → 投标响应审查 → 投标文件问题识别。 */
    String TYPE_BID_SIMULATION_AUDIT = "BID_SIMULATION_AUDIT";
    String STATUS_PENDING = "PENDING";
    String STATUS_RUNNING = "RUNNING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    String STATUS_CANCELLED = "CANCELLED";

    String PHASE_INDEX_SUBMIT = "INDEX_SUBMIT";
    String PHASE_INDEX_WAIT = "INDEX_WAIT";
    String PHASE_CHECKLIST_SUBMIT = "CHECKLIST_SUBMIT";
    String PHASE_CHECKLIST_WAIT = "CHECKLIST_WAIT";
    String PHASE_REVIEW_SUBMIT = "REVIEW_SUBMIT";
    String PHASE_REVIEW_WAIT = "REVIEW_WAIT";
    /** 流水线终态阶段：成功（与总任务状态 SUCCESS 对应）。 */
    String PHASE_SUCCESS = "SUCCESS";
    /** 流水线终态阶段：失败（与总任务状态 FAILED 对应）。 */
    String PHASE_FAILED = "FAILED";

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

    /**
     * 认领一条 PENDING 或当前 Worker 已持有的 RUNNING 任务。
     * <p>
     * 用于轻量编排任务：每次轮询只检查一次状态，不 sleep；若仍需等待，保持 RUNNING，
     * 下次由同一 workerId 继续检查。
     */
    Optional<JobRef> claimNextPendingOrOwnedRunning(String type, String workerId);

    /** 领取到期的流水线任务；过期租约可由其他实例接管。 */
    Optional<JobRef> claimNextRunnablePipeline(String type, String workerId, int leaseSeconds);

    /**
     * 以任务、Worker 和当前阶段为条件推进状态，并释放本次租约。
     * <p>
     * 条件：id + status=RUNNING + worker_id + phase=expectedPhase
     * + lease_expires_at &gt; 当前时间（租约必须仍然有效）。
     * 命中 1 行返回 true；否则说明租约已过期或易主，调用方必须停止处理，
     * 不得覆盖其他 Worker 的结果。
     * checklistId / reviewId / payloadJson 传 null 表示保持原值；nextPhase 与当前阶段相同
     * 时保留 phase_started_at（用于等待阶段计时），不同则重置为当前时间。
     */
    boolean advancePipeline(long jobId, String workerId, String expectedPhase, String nextPhase,
                            int progress, String message, Instant nextRunAt,
                            Long checklistId, Long reviewId, String payloadJson);

    /**
     * 瞬时错误重试：retry_count + 1、记录错误信息并推迟 next_run_at，释放本次租约。
     * 条件与返回值语义同 {@link #advancePipeline}。
     */
    boolean retryPipeline(long jobId, String workerId, String expectedPhase,
                          String errorMessage, Instant nextRunAt);

    /**
     * 条件标记总任务成功（终态 SUCCESS）：写入 message 与最终 payload，释放租约与 Worker。
     * 条件与返回值语义同 {@link #advancePipeline}。
     */
    boolean succeedPipeline(long jobId, String workerId, String expectedPhase,
                            String message, String payloadJson);

    /**
     * 条件标记总任务失败（终态 FAILED）：errorMessage 最多保留 2000 字符，释放租约与 Worker。
     * 条件与返回值语义同 {@link #advancePipeline}。
     */
    boolean failPipeline(long jobId, String workerId, String expectedPhase, String errorMessage);

    void markRunningProgress(long jobId, int progress, String message);

    void markSuccess(long jobId, String message);

    void markFailed(long jobId, String errorMessage);

    /**
     * 将指定类型且仍为 RUNNING 的任务全部标为 FAILED（服务重启后回收僵尸任务）。
     *
     * @return 更新行数
     */
    int failRunningByTypes(String reason, String... types);

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
        private String phase;
        private Instant nextRunAt;
        private Instant leaseExpiresAt;
        private Instant phaseStartedAt;
        private Long checklistId;
        private Long reviewId;
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

        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = phase; }
        public Instant getNextRunAt() { return nextRunAt; }
        public void setNextRunAt(Instant nextRunAt) { this.nextRunAt = nextRunAt; }
        public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
        public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
        public Instant getPhaseStartedAt() { return phaseStartedAt; }
        public void setPhaseStartedAt(Instant phaseStartedAt) { this.phaseStartedAt = phaseStartedAt; }
        public Long getChecklistId() { return checklistId; }
        public void setChecklistId(Long checklistId) { this.checklistId = checklistId; }
        public Long getReviewId() { return reviewId; }
        public void setReviewId(Long reviewId) { this.reviewId = reviewId; }

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
