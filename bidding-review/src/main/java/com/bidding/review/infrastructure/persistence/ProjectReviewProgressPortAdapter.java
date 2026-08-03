package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.JobPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.port.ReviewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目审查进度查询（供项目列表派生 workflowStatus）。
 */
@Component
public class ProjectReviewProgressPortAdapter implements ProjectReviewProgressPort {

    private final JdbcTemplate jdbcTemplate;

    public ProjectReviewProgressPortAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean hasInProgressReview(long projectId) {
        Long jobCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_job "
                        + "WHERE project_id = ? AND type IN (?, ?) "
                        + "AND status IN (?, ?)",
                Long.class,
                projectId,
                JobPort.TYPE_REVIEW,
                JobPort.TYPE_AI_REVIEW_PIPELINE,
                JobPort.STATUS_PENDING,
                JobPort.STATUS_RUNNING
        );
        if (jobCount != null && jobCount > 0) {
            return true;
        }
        Long buildingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_review WHERE project_id = ? AND status = ?",
                Long.class,
                projectId,
                ReviewPort.STATUS_BUILDING
        );
        return buildingCount != null && buildingCount > 0;
    }

    @Override
    public String findLatestReviewStatus(long projectId) {
        List<String> list = jdbcTemplate.query(
                "SELECT status FROM biz_review WHERE project_id = ? "
                        + "ORDER BY version DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("status"),
                projectId
        );
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public Long findLatestAiReviewPipelineJobId(long projectId) {
        List<Long> list = jdbcTemplate.query(
                "SELECT id FROM biz_job WHERE project_id = ? AND type = ? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                projectId,
                JobPort.TYPE_AI_REVIEW_PIPELINE
        );
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }
}
