package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.BidPackageReviewSummaryPort;
import com.bidding.common.port.ReviewPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按最新成功审查聚合投标包列表所需的审查摘要。
 */
@Component
public class BidPackageReviewSummaryPortAdapter implements BidPackageReviewSummaryPort {

    private final JdbcTemplate jdbcTemplate;

    public BidPackageReviewSummaryPortAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<Long, Summary> findLatestSuccessByProjectId(long projectId) {
        List<Summary> rows = jdbcTemplate.query(
                "WITH latest_review AS ("
                        + "SELECT id FROM biz_review WHERE project_id = ? AND status = ? "
                        + "ORDER BY version DESC LIMIT 1"
                        + ") "
                        + "SELECT package_id, "
                        + "SUM(CASE WHEN main_type = ? THEN suggested_score ELSE NULL END) AS suggested_score_total, "
                        + "SUM(CASE WHEN main_type = ? THEN full_score ELSE NULL END) AS full_score_total, "
                        + "SUM(CASE WHEN (main_type IS NULL OR main_type <> ?) AND status = ? THEN 1 ELSE 0 END) AS qualification_match_count, "
                        + "SUM(CASE WHEN (main_type IS NULL OR main_type <> ?) AND status = ? THEN 1 ELSE 0 END) AS qualification_mismatch_count, "
                        + "SUM(CASE WHEN (main_type IS NULL OR main_type <> ?) AND status = ? THEN 1 ELSE 0 END) AS qualification_unknown_count "
                        + "FROM biz_review_item "
                        + "WHERE review_id = (SELECT id FROM latest_review) AND package_id IS NOT NULL "
                        + "GROUP BY package_id",
                (rs, rowNum) -> {
                    Summary summary = new Summary();
                    summary.setPackageId(rs.getLong("package_id"));
                    summary.setSuggestedScoreTotal(rs.getBigDecimal("suggested_score_total"));
                    summary.setFullScoreTotal(rs.getBigDecimal("full_score_total"));
                    summary.setQualificationMatchCount(rs.getInt("qualification_match_count"));
                    summary.setQualificationMismatchCount(rs.getInt("qualification_mismatch_count"));
                    summary.setQualificationUnknownCount(rs.getInt("qualification_unknown_count"));
                    return summary;
                },
                projectId,
                ReviewPort.STATUS_SUCCESS,
                ReviewPort.MAIN_SCORING,
                ReviewPort.MAIN_SCORING,
                ReviewPort.MAIN_SCORING,
                ReviewPort.STATUS_MATCH,
                ReviewPort.MAIN_SCORING,
                ReviewPort.STATUS_MISMATCH,
                ReviewPort.MAIN_SCORING,
                ReviewPort.STATUS_UNKNOWN
        );
        Map<Long, Summary> result = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Summary row = rows.get(i);
            result.put(row.getPackageId(), row);
        }
        return result;
    }
}
