package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.ReviewStatusCountPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 组织详情用的审查状态计数实现。
 */
@Component
public class ReviewStatusCountPortAdapter implements ReviewStatusCountPort {

    private final JdbcTemplate jdbcTemplate;

    public ReviewStatusCountPortAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long countByOrgIdAndStatus(long orgId, String status) {
        String safeStatus = status == null ? "" : status.trim();
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_review WHERE org_id = ? AND status = ?",
                Long.class,
                orgId,
                safeStatus
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countByStatus(String status) {
        String safeStatus = status == null ? "" : status.trim();
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_review WHERE status = ?",
                Long.class,
                safeStatus
        );
        return count == null ? 0 : count;
    }
}
