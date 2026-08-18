package com.bidding.common.port;

/**
 * 项目侧清单/审查作废（实现在 review 模块）。
 * 删投标包、以及项目下上传/复制/删除招标或投标文件后调用，
 * 使 workflowStatus 回到待审查（或待补文件）。OBSOLETE 记录保留，详情仍可按 id 查看。
 */
public interface ProjectArtifactInvalidationPort {

    /**
     * 项目下是否有进行中的 AI 相关任务或 BUILDING 清单/审查。
     * 含 INDEX / BUILD_CHECKLIST / REVIEW / AI_REVIEW_PIPELINE 的 PENDING|RUNNING。
     */
    boolean hasActiveAiWork(long projectId);

    /**
     * 是否有进行中的建清单 / 审查 / 一键审查（不含 INDEX）。
     * 改文件时用：向量化进行中仍允许继续传/删文件。
     */
    boolean hasActiveChecklistOrReviewWork(long projectId);

    /**
     * 将项目下 SUCCESS/FAILED/BUILDING 的审查标记为 OBSOLETE（不再参与 workflow 判定）。
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
     * 将项目下 SUCCESS/FAILED/BUILDING 的要求清单标记为 OBSOLETE。
     *
     * @return 更新行数
     */
    int obsoleteChecklistsByProjectId(long projectId, String reason);

    /**
     * 项目删除时清理专家评审记录和临时专家账号。
     *
     * @return 删除的专家评审主记录数
     */
    int deleteExpertReviewsByProjectId(long orgId, long projectId);
}
