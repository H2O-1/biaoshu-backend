package com.bidding.review.infrastructure.persistence;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ExpertAccountPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目清单/审查作废与进行中任务探测。
 */
@Component
public class ProjectArtifactInvalidationPortAdapter implements ProjectArtifactInvalidationPort {

    private static final String EXPERT_REVIEW_STATUS_DELETED = "DELETED";
    private static final String EXPERT_MEMBER_STATUS_DELETED = "DELETED";

    private final JdbcTemplate jdbcTemplate;
    private final ExpertAccountPort expertAccountPort;

    public ProjectArtifactInvalidationPortAdapter(
            JdbcTemplate jdbcTemplate,
            ExpertAccountPort expertAccountPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.expertAccountPort = expertAccountPort;
    }

    @Override
    public boolean hasActiveAiWork(long projectId) {
        Long jobCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_job "
                        + "WHERE project_id = ? AND type IN (?, ?, ?, ?) "
                        + "AND status IN (?, ?)",
                Long.class,
                projectId,
                JobPort.TYPE_INDEX,
                JobPort.TYPE_BUILD_CHECKLIST,
                JobPort.TYPE_REVIEW,
                JobPort.TYPE_AI_REVIEW_PIPELINE,
                JobPort.STATUS_PENDING,
                JobPort.STATUS_RUNNING
        );
        if (jobCount != null && jobCount > 0) {
            return true;
        }
        return hasBuildingChecklistOrReview(projectId);
    }

    @Override
    public boolean hasActiveChecklistOrReviewWork(long projectId) {
        Long jobCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_job "
                        + "WHERE project_id = ? AND type IN (?, ?, ?) "
                        + "AND status IN (?, ?)",
                Long.class,
                projectId,
                JobPort.TYPE_BUILD_CHECKLIST,
                JobPort.TYPE_REVIEW,
                JobPort.TYPE_AI_REVIEW_PIPELINE,
                JobPort.STATUS_PENDING,
                JobPort.STATUS_RUNNING
        );
        if (jobCount != null && jobCount > 0) {
            return true;
        }
        return hasBuildingChecklistOrReview(projectId);
    }

    private boolean hasBuildingChecklistOrReview(long projectId) {
        Long buildingReview = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_review WHERE project_id = ? AND status = ?",
                Long.class,
                projectId,
                ReviewPort.STATUS_BUILDING
        );
        if (buildingReview != null && buildingReview > 0) {
            return true;
        }
        Long buildingChecklist = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_requirement_checklist "
                        + "WHERE project_id = ? AND status = ?",
                Long.class,
                projectId,
                RequirementChecklistPort.STATUS_BUILDING
        );
        return buildingChecklist != null && buildingChecklist > 0;
    }

    @Override
    @Transactional
    public int obsoleteReviewsByProjectId(long projectId, String reason) {
        String msg = reason == null ? "" : reason.trim();
        return jdbcTemplate.update(
                "UPDATE biz_review SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE project_id = ? AND status IN (?, ?, ?)",
                ReviewPort.STATUS_OBSOLETE,
                msg.isEmpty() ? null : msg,
                projectId,
                ReviewPort.STATUS_SUCCESS,
                ReviewPort.STATUS_FAILED,
                ReviewPort.STATUS_BUILDING
        );
    }

    @Override
    @Transactional
    public int deleteReviewItemsByPackageId(long projectId, long packageId) {
        return jdbcTemplate.update(
                "DELETE FROM biz_review_item WHERE package_id = ? "
                        + "AND review_id IN (SELECT id FROM biz_review WHERE project_id = ?)",
                packageId,
                projectId
        );
    }

    @Override
    @Transactional
    public int obsoleteChecklistsByProjectId(long projectId, String reason) {
        String msg = reason == null ? "" : reason.trim();
        return jdbcTemplate.update(
                "UPDATE biz_requirement_checklist SET status = ?, error_message = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE project_id = ? AND status IN (?, ?, ?)",
                RequirementChecklistPort.STATUS_OBSOLETE,
                msg.isEmpty() ? null : msg,
                projectId,
                RequirementChecklistPort.STATUS_SUCCESS,
                RequirementChecklistPort.STATUS_FAILED,
                RequirementChecklistPort.STATUS_BUILDING
        );
    }

    @Override
    @Transactional
    public int deleteExpertReviewsByProjectId(long orgId, long projectId) {
        long memberCount = countExpertReviewMembersToDelete(orgId, projectId);
        List<Long> userIds = jdbcTemplate.query(
                "SELECT DISTINCT m.user_id FROM biz_expert_review_member m "
                        + "INNER JOIN biz_expert_review r ON r.id = m.review_id "
                        + "WHERE r.org_id = ? AND r.project_id = ? AND m.status <> ? "
                        + "ORDER BY m.user_id",
                (rs, rowNum) -> rs.getLong("user_id"),
                orgId,
                projectId,
                EXPERT_MEMBER_STATUS_DELETED
        );
        for (Long userId : userIds) {
            expertAccountPort.deleteTemporaryExpert(userId);
        }

        int deletedMembers = jdbcTemplate.update(
                "UPDATE biz_expert_review_member m SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "FROM biz_expert_review r "
                        + "WHERE m.review_id = r.id AND r.org_id = ? AND r.project_id = ? "
                        + "AND m.status <> ?",
                EXPERT_MEMBER_STATUS_DELETED,
                orgId,
                projectId,
                EXPERT_MEMBER_STATUS_DELETED
        );
        if (deletedMembers != memberCount) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审成员清理状态已变化，请刷新后重试");
        }

        return jdbcTemplate.update(
                "UPDATE biz_expert_review SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE org_id = ? AND project_id = ? AND status <> ?",
                EXPERT_REVIEW_STATUS_DELETED,
                orgId,
                projectId,
                EXPERT_REVIEW_STATUS_DELETED
        );
    }

    private long countExpertReviewMembersToDelete(long orgId, long projectId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_expert_review_member m "
                        + "INNER JOIN biz_expert_review r ON r.id = m.review_id "
                        + "WHERE r.org_id = ? AND r.project_id = ? AND m.status <> ?",
                Long.class,
                orgId,
                projectId,
                EXPERT_MEMBER_STATUS_DELETED
        );
        if (count == null) {
            throw new IllegalStateException("查询专家评审成员数失败");
        }
        return count;
    }
}
