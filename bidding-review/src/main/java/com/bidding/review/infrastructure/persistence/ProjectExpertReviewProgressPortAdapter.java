package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.ProjectExpertReviewProgressPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 项目专家评审进度查询（供项目列表/详情派生 workflowStatus）。
 */
@Component
public class ProjectExpertReviewProgressPortAdapter implements ProjectExpertReviewProgressPort {

    private final JdbcTemplate jdbcTemplate;

    public ProjectExpertReviewProgressPortAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Summary findLatestActiveByProject(long orgId, long projectId) {
        List<Summary> rows = jdbcTemplate.query(
                "SELECT id, status, expert_count, submitted_expert_count "
                        + "FROM biz_expert_review "
                        + "WHERE org_id = ? AND project_id = ? AND status IN (?, ?) "
                        + "ORDER BY version DESC, id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Summary summary = new Summary();
                    summary.setReviewId(rs.getLong("id"));
                    summary.setStatus(rs.getString("status"));
                    summary.setExpertCount(rs.getInt("expert_count"));
                    summary.setSubmittedExpertCount(rs.getInt("submitted_expert_count"));
                    return summary;
                },
                orgId,
                projectId,
                STATUS_RUNNING,
                STATUS_SUCCESS
        );
        return rows.isEmpty() ? null : rows.get(0);
    }
}
