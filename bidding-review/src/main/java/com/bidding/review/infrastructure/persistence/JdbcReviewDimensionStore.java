package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.ReviewDimensionPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局审查维度 JDBC 实现（维度主键 id；query 挂 dimension_id）。
 */
@Repository
public class JdbcReviewDimensionStore implements ReviewDimensionPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcReviewDimensionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Dimension> listAll(boolean onlyEnabled) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT d.id, d.code, d.name, d.default_selected, d.enabled, d.sort_order, ");
        sql.append("q.query_text ");
        sql.append("FROM biz_review_dimension d ");
        sql.append("LEFT JOIN biz_review_dimension_query q ON q.dimension_id = d.id ");
        if (onlyEnabled) {
            sql.append("WHERE d.enabled = TRUE ");
        }
        sql.append("ORDER BY d.sort_order ASC, d.id ASC, q.sort_order ASC, q.id ASC");

        List<Dimension> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapJoinRow(rs));
        return mergeById(rows);
    }

    @Override
    @Transactional(readOnly = true)
    public Dimension findById(long id) {
        String sql = "SELECT d.id, d.code, d.name, d.default_selected, d.enabled, d.sort_order, "
                + "q.query_text "
                + "FROM biz_review_dimension d "
                + "LEFT JOIN biz_review_dimension_query q ON q.dimension_id = d.id "
                + "WHERE d.id = ? "
                + "ORDER BY q.sort_order ASC, q.id ASC";
        List<Dimension> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapJoinRow(rs), id);
        List<Dimension> merged = mergeById(rows);
        if (merged.isEmpty()) {
            return null;
        }
        return merged.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Dimension findByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String sql = "SELECT d.id, d.code, d.name, d.default_selected, d.enabled, d.sort_order, "
                + "q.query_text "
                + "FROM biz_review_dimension d "
                + "LEFT JOIN biz_review_dimension_query q ON q.dimension_id = d.id "
                + "WHERE d.code = ? "
                + "ORDER BY q.sort_order ASC, q.id ASC";
        List<Dimension> rows = jdbcTemplate.query(sql, (rs, rowNum) -> mapJoinRow(rs), code.trim());
        List<Dimension> merged = mergeById(rows);
        if (merged.isEmpty()) {
            return null;
        }
        return merged.get(0);
    }

    @Override
    @Transactional
    public void update(long id,
                       String name,
                       boolean defaultSelected,
                       boolean enabled,
                       int sortOrder,
                       List<String> queries) {
        int updated = jdbcTemplate.update(
                "UPDATE biz_review_dimension SET name = ?, default_selected = ?, enabled = ?, "
                        + "sort_order = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                name,
                defaultSelected,
                enabled,
                sortOrder,
                id
        );
        if (updated != 1) {
            throw new IllegalStateException("维度不存在: id=" + id);
        }

        jdbcTemplate.update("DELETE FROM biz_review_dimension_query WHERE dimension_id = ?", id);
        if (queries == null || queries.isEmpty()) {
            return;
        }
        int order = 10;
        for (int i = 0; i < queries.size(); i++) {
            String text = queries.get(i);
            if (text == null) {
                continue;
            }
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO biz_review_dimension_query (dimension_id, query_text, sort_order) VALUES (?, ?, ?)",
                    id,
                    trimmed,
                    order
            );
            order += 10;
        }
    }

    private Dimension mapJoinRow(ResultSet rs) throws SQLException {
        Dimension dim = new Dimension();
        dim.setId(rs.getLong("id"));
        dim.setCode(rs.getString("code"));
        dim.setName(rs.getString("name"));
        dim.setDefaultSelected(rs.getBoolean("default_selected"));
        dim.setEnabled(rs.getBoolean("enabled"));
        dim.setSortOrder(rs.getInt("sort_order"));
        String queryText = rs.getString("query_text");
        List<String> queries = new ArrayList<>();
        if (queryText != null && !queryText.isBlank()) {
            queries.add(queryText);
        }
        dim.setQueries(queries);
        return dim;
    }

    private List<Dimension> mergeById(List<Dimension> rows) {
        Map<Long, Dimension> map = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Dimension row = rows.get(i);
            Dimension existing = map.get(row.getId());
            if (existing == null) {
                map.put(row.getId(), row);
            } else if (row.getQueries() != null && !row.getQueries().isEmpty()) {
                existing.getQueries().add(row.getQueries().get(0));
            }
        }
        List<Dimension> result = new ArrayList<>();
        for (Dimension value : map.values()) {
            result.add(value);
        }
        return result;
    }
}
