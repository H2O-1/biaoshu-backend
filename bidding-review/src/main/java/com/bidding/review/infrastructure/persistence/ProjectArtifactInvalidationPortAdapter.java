package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.JobPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目清单/审查作废与进行中任务探测。
 */
@Component
public class ProjectArtifactInvalidationPortAdapter implements ProjectArtifactInvalidationPort {

    private final JdbcTemplate jdbcTemplate;

    public ProjectArtifactInvalidationPortAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
}
