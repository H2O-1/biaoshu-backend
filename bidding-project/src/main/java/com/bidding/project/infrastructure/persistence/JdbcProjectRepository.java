package com.bidding.project.infrastructure.persistence;

import com.bidding.project.domain.model.Project;
import com.bidding.project.domain.port.ProjectRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 项目表 JDBC 实现。
 */
@Repository
public class JdbcProjectRepository implements ProjectRepository {

    private static final String PROJECT_COLUMNS =
            "id, org_id, name, agency, project_type, leader_name, status, "
                    + "created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Project> projectRowMapper = (rs, rowNum) -> new Project(
            rs.getLong("id"),
            rs.getLong("org_id"),
            rs.getString("name"),
            rs.getString("agency"),
            rs.getString("project_type"),
            rs.getString("leader_name"),
            rs.getString("status"),
            rs.getLong("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    @Override
    public Optional<Project> findById(long projectId) {
        List<Project> list = jdbcTemplate.query(
                "SELECT " + PROJECT_COLUMNS + " FROM biz_project WHERE id = ?",
                projectRowMapper,
                projectId
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    @Override
    public List<Project> searchByOrgId(long orgId, String keyword, String status,
                                       int offset, int limit) {
        String kw = keyword == null ? "" : keyword;
        String statusKw = status == null ? "" : status;
        String like = "%" + kw + "%";
        return jdbcTemplate.query(
                "SELECT " + PROJECT_COLUMNS + " FROM biz_project "
                        + "WHERE org_id = ? AND status <> 'DELETED' "
                        + "AND (? = '' OR name LIKE ? OR agency LIKE ? OR leader_name LIKE ?) "
                        + "AND (? = '' OR status = ?) "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?",
                projectRowMapper,
                orgId,
                kw, like, like, like,
                statusKw, statusKw,
                limit, offset
        );
    }

    @Override
    public long countSearchByOrgId(long orgId, String keyword, String status) {
        String kw = keyword == null ? "" : keyword;
        String statusKw = status == null ? "" : status;
        String like = "%" + kw + "%";
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_project "
                        + "WHERE org_id = ? AND status <> 'DELETED' "
                        + "AND (? = '' OR name LIKE ? OR agency LIKE ? OR leader_name LIKE ?) "
                        + "AND (? = '' OR status = ?)",
                Long.class,
                orgId,
                kw, like, like, like,
                statusKw, statusKw
        );
        return count == null ? 0 : count;
    }

    @Override
    public boolean existsByOrgIdAndName(long orgId, String name, Long excludeProjectId) {
        Long count;
        if (excludeProjectId == null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM biz_project "
                            + "WHERE org_id = ? AND name = ? AND status <> 'DELETED'",
                    Long.class,
                    orgId, name
            );
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM biz_project "
                            + "WHERE org_id = ? AND name = ? AND status <> 'DELETED' AND id <> ?",
                    Long.class,
                    orgId, name, excludeProjectId
            );
        }
        return count != null && count > 0;
    }

    @Override
    public long insert(long orgId,
                       String name,
                       String agency,
                       String projectType,
                       String leaderName,
                       String status,
                       long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO biz_project(org_id, name, agency, project_type, leader_name, status, "
                            + "created_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            ps.setString(2, name);
            setNullableString(ps, 3, agency);
            setNullableString(ps, 4, projectType);
            setNullableString(ps, 5, leaderName);
            ps.setString(6, status);
            ps.setLong(7, createdBy);
            return ps;
        }, keyHolder);

        Number key = null;
        if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
            key = (Number) keyHolder.getKeys().get("id");
        }
        if (key == null) {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("插入项目未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateBasicInfo(long projectId,
                                String name,
                                String agency,
                                String projectType,
                                String leaderName) {
        jdbcTemplate.update(
                "UPDATE biz_project SET name = ?, agency = ?, project_type = ?, leader_name = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                name, agency, projectType, leaderName, projectId
        );
    }

    @Override
    public void updateStatus(long projectId, String status) {
        jdbcTemplate.update(
                "UPDATE biz_project SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, projectId
        );
    }

    @Override
    public long countByOrgId(long orgId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_project WHERE org_id = ? AND status <> 'DELETED'",
                Long.class,
                orgId
        );
        return count == null ? 0 : count;
    }

    private static void setNullableString(PreparedStatement ps, int index, String value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
