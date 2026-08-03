package com.bidding.common.port;

/**
 * 项目审查进度查询（供项目列表派生 workflowStatus；实现在 review 模块）。
 */
public interface ProjectReviewProgressPort {

    /**
     * 是否有进行中的审查：REVIEW 任务 PENDING/RUNNING，或最新审查行仍为 BUILDING。
     */
    boolean hasInProgressReview(long projectId);

    /**
     * 最新一版审查的 status（按 version 倒序）；无审查记录返回 null。
     */
    String findLatestReviewStatus(long projectId);

    /**
     * 项目下最新一条一键审查总任务（AI_REVIEW_PIPELINE）的 jobId；无则 null。
     * 供详情页退出再进后继续轮询 GET /api/jobs/{jobId}。
     */
    Long findLatestAiReviewPipelineJobId(long projectId);
}
