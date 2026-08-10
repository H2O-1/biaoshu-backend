package com.bidding.identity.infrastructure.persistence;

import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.port.OrgRepository;
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
 * 组织表 JDBC 实现。
 */
@Repository
public class JdbcOrgRepository implements OrgRepository {

    private static final String ORG_COLUMNS =
            "id, code, name, city, status, created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcOrgRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Org> orgRowMapper = (rs, rowNum) -> {
        long createdByRaw = rs.getLong("created_by");
        Long createdBy = rs.wasNull() ? null : createdByRaw;
        return new Org(
                rs.getLong("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("status"),
                createdBy,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    };

    @Override
    public Optional<Org> findById(long orgId) {
        List<Org> list = jdbcTemplate.query(
                "SELECT " + ORG_COLUMNS + " FROM sys_org WHERE id = ?",
                orgRowMapper,
                orgId
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    @Override
    public Optional<Long> findSystemOrgId() {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE code = 'SYSTEM' LIMIT 1",
                (rs, rowNum) -> rs.getLong(1)
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ids.get(0));
    }

    @Override
    public boolean existsById(long orgId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org WHERE id = ?",
                Long.class,
                orgId
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByName(String name) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org WHERE name = ? AND status <> 'DELETED'",
                Long.class,
                name
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByNameExceptOrgId(String name, long orgId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org WHERE name = ? AND status <> 'DELETED' AND id <> ?",
                Long.class,
                name,
                orgId
        );
        return count != null && count > 0;
    }

    @Override
    public boolean existsByCode(String code) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org WHERE code = ?",
                Long.class,
                code
        );
        return count != null && count > 0;
    }

    @Override
    public List<Org> search(String nameKeyword, String city, String status, int offset, int limit) {
        String nameKw = nameKeyword == null ? "" : nameKeyword;
        String cityKw = city == null ? "" : city;
        String statusKw = status == null ? "" : status;
        String nameLike = "%" + nameKw + "%";
        String cityLike = "%" + cityKw + "%";
        return jdbcTemplate.query(
                "SELECT " + ORG_COLUMNS + " FROM sys_org o "
                        + "WHERE o.status <> 'DELETED' "
                        + "AND (? = '' OR o.name LIKE ? OR o.city LIKE ? "
                        + "  OR EXISTS ("
                        + "    SELECT 1 FROM sys_user u "
                        + "    WHERE u.org_id = o.id AND u.status <> 'DELETED' "
                        + "    AND (u.display_name LIKE ? OR u.phone LIKE ? OR u.username LIKE ? OR u.email LIKE ?)"
                        + "  )"
                        + ") "
                        + "AND (? = '' OR o.city LIKE ?) "
                        + "AND (? = '' OR o.status = ?) "
                        + "ORDER BY o.id DESC LIMIT ? OFFSET ?",
                orgRowMapper,
                nameKw, nameLike, nameLike, nameLike, nameLike, nameLike, nameLike,
                cityKw, cityLike,
                statusKw, statusKw,
                limit, offset
        );
    }

    @Override
    public long countSearch(String nameKeyword, String city, String status) {
        String nameKw = nameKeyword == null ? "" : nameKeyword;
        String cityKw = city == null ? "" : city;
        String statusKw = status == null ? "" : status;
        String nameLike = "%" + nameKw + "%";
        String cityLike = "%" + cityKw + "%";
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org o "
                        + "WHERE o.status <> 'DELETED' "
                        + "AND (? = '' OR o.name LIKE ? OR o.city LIKE ? "
                        + "  OR EXISTS ("
                        + "    SELECT 1 FROM sys_user u "
                        + "    WHERE u.org_id = o.id AND u.status <> 'DELETED' "
                        + "    AND (u.display_name LIKE ? OR u.phone LIKE ? OR u.username LIKE ? OR u.email LIKE ?)"
                        + "  )"
                        + ") "
                        + "AND (? = '' OR o.city LIKE ?) "
                        + "AND (? = '' OR o.status = ?)",
                Long.class,
                nameKw, nameLike, nameLike, nameLike, nameLike, nameLike, nameLike,
                cityKw, cityLike,
                statusKw, statusKw
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countByStatus(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org WHERE status = ?",
                Long.class,
                status
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countPendingConfig() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org o "
                        + "WHERE o.status <> 'DELETED' "
                        + "AND NOT EXISTS ("
                        + "  SELECT 1 FROM sys_user u "
                        + "  WHERE u.org_id = o.id AND u.status <> 'DELETED'"
                        + ")",
                Long.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countTendererOrgs() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_org WHERE status <> 'DELETED' AND code <> 'SYSTEM'",
                Long.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public long insert(String code, String name, String city, String status, Long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sys_org(code, name, city, status, created_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, city);
            ps.setString(4, status);
            if (createdBy == null) {
                ps.setNull(5, Types.BIGINT);
            } else {
                ps.setLong(5, createdBy);
            }
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
            throw new IllegalStateException("插入组织未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateStatus(long orgId, String status) {
        jdbcTemplate.update(
                "UPDATE sys_org SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status,
                orgId
        );
    }

    @Override
    public void updateNameAndCity(long orgId, String name, String city) {
        jdbcTemplate.update(
                "UPDATE sys_org SET name = ?, city = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                name,
                city,
                orgId
        );
    }

    @Override
    public long countUsersByOrgId(long orgId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE org_id = ? AND status <> 'DELETED'",
                Long.class,
                orgId
        );
        return count == null ? 0 : count;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
