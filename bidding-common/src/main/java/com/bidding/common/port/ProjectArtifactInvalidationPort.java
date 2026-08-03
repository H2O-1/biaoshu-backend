package com.bidding.common.port;

/**
 * 项目侧清单/审查作废（实现在 review 模块）。
 * 删投标包、删招标文件后调用，使 workflowStatus 回到待审查（或待补文件）。
 */
public interface ProjectArtifactInvalidationPort {

    /**
     * 项目下是否有进行中的 AI 相关任务或 BUILDING 清单/审查。
     * 含 INDEX / BUILD_CHECKLIST / REVIEW / AI_REVIEW_PIPELINE 的 PENDING|RUNNING。
     */
    boolean hasActiveAiWork(long projectId);

    /**
     * 将项目下 SUCCESS/BUILDING 的审查标记为 OBSOLETE（不再参与 workflow 判定）。
     *
     * @return 更新行数
     */
    int obsoleteReviewsByProjectId(long projectId, String reason);

    /**
     * 删除项目下指定投标包相关的审查条目。
     *
     * @return 删除行数
     */
    int deleteReviewItemsByPackageId(long projectId, long packageId);

    /**
     * 将项目下 SUCCESS/BUILDING 的要求清单标记为 OBSOLETE。
     *
     * @return 更新行数
     */
    int obsoleteChecklistsByProjectId(long projectId, String reason);
}
