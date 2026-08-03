package com.bidding.project.infrastructure.persistence;

import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.port.PackageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 标包表 JDBC 实现。
 */
@Repository
public class JdbcPackageRepository implements PackageRepository {

    private static final String PACKAGE_COLUMNS =
            "id, org_id, project_id, type, name, contact_name, contact_phone, "
                    + "company_address, price_with_tax, tax_rate, price_without_tax, status, "
                    + "created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcPackageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<BizPackage> packageRowMapper = (rs, rowNum) -> {
        Long projectId = rs.getObject("project_id", Long.class);
        return new BizPackage(
                rs.getLong("id"),
                rs.getLong("org_id"),
                projectId,
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("contact_name"),
                rs.getString("contact_phone"),
                rs.getString("company_address"),
                rs.getBigDecimal("price_with_tax"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("price_without_tax"),
                rs.getString("status"),
                rs.getLong("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    };

    @Override
    public Optional<BizPackage> findById(long packageId) {
        List<BizPackage> list = jdbcTemplate.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM biz_package WHERE id = ?",
                packageRowMapper,
                packageId
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    @Override
    public List<BizPackage> searchByOrgId(long orgId,
                                          String type,
                                          String keyword,
                                          boolean unboundOnly,
                                          int offset,
                                          int limit) {
        String typeKw = type == null ? "" : type;
        String kw = keyword == null ? "" : keyword;
        String like = "%" + kw + "%";
        // unboundFlag：1=只查未绑定；用数字兼容 H2 / Postgres
        int unboundFlag = unboundOnly ? 1 : 0;
        return jdbcTemplate.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM biz_package "
                        + "WHERE org_id = ? AND status <> 'DELETED' "
                        + "AND (? = '' OR type = ?) "
                        + "AND (? = '' OR name LIKE ?) "
                        + "AND (? = 0 OR project_id IS NULL) "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?",
                packageRowMapper,
                orgId,
                typeKw, typeKw,
                kw, like,
                unboundFlag,
                limit, offset
        );
    }

    @Override
    public long countSearchByOrgId(long orgId, String type, String keyword, boolean unboundOnly) {
        String typeKw = type == null ? "" : type;
        String kw = keyword == null ? "" : keyword;
        String like = "%" + kw + "%";
        int unboundFlag = unboundOnly ? 1 : 0;
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_package "
                        + "WHERE org_id = ? AND status <> 'DELETED' "
                        + "AND (? = '' OR type = ?) "
                        + "AND (? = '' OR name LIKE ?) "
                        + "AND (? = 0 OR project_id IS NULL)",
                Long.class,
                orgId,
                typeKw, typeKw,
                kw, like,
                unboundFlag
        );
        return count == null ? 0 : count;
    }

    @Override
    public boolean existsByOrgIdAndTypeAndName(long orgId, String type, String name,
                                               Long excludePackageId) {
        Long count;
        if (excludePackageId == null) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM biz_package "
                            + "WHERE org_id = ? AND type = ? AND name = ? AND status <> 'DELETED'",
                    Long.class,
                    orgId, type, name
            );
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM biz_package "
                            + "WHERE org_id = ? AND type = ? AND name = ? AND status <> 'DELETED' "
                            + "AND id <> ?",
                    Long.class,
                    orgId, type, name, excludePackageId
            );
        }
        return count != null && count > 0;
    }

    @Override
    public long insert(long orgId,
                       Long projectId,
                       String type,
                       String name,
                       String contactName,
                       String contactPhone,
                       String companyAddress,
                       BigDecimal priceWithTax,
                       BigDecimal taxRate,
                       BigDecimal priceWithoutTax,
                       String status,
                       long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO biz_package(org_id, project_id, type, name, contact_name, contact_phone, "
                            + "company_address, price_with_tax, tax_rate, price_without_tax, "
                            + "status, created_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            if (projectId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, projectId);
            }
            ps.setString(3, type);
            ps.setString(4, name);
            setNullableString(ps, 5, contactName);
            setNullableString(ps, 6, contactPhone);
            setNullableString(ps, 7, companyAddress);
            setNullableDecimal(ps, 8, priceWithTax);
            setNullableDecimal(ps, 9, taxRate);
            setNullableDecimal(ps, 10, priceWithoutTax);
            ps.setString(11, status);
            ps.setLong(12, createdBy);
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
            throw new IllegalStateException("插入标包未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void updateName(long packageId, String name) {
        jdbcTemplate.update(
                "UPDATE biz_package SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                name, packageId
        );
    }

    @Override
    public void updateBidInfo(long packageId,
                              String name,
                              String contactName,
                              String contactPhone,
                              String companyAddress,
                              BigDecimal priceWithTax,
                              BigDecimal taxRate,
                              BigDecimal priceWithoutTax) {
        jdbcTemplate.update(
                "UPDATE biz_package SET name = ?, contact_name = ?, contact_phone = ?, "
                        + "company_address = ?, price_with_tax = ?, tax_rate = ?, price_without_tax = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                name, contactName, contactPhone,
                companyAddress, priceWithTax, taxRate, priceWithoutTax,
                packageId
        );
    }

    @Override
    public void updateStatus(long packageId, String status) {
        jdbcTemplate.update(
                "UPDATE biz_package SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, packageId
        );
    }

    @Override
    public int bindToProjectIfUnbound(long packageId, long projectId, String type) {
        return jdbcTemplate.update(
                "UPDATE biz_package SET project_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND type = ? AND project_id IS NULL AND status <> 'DELETED'",
                projectId, packageId, type
        );
    }

    @Override
    public Optional<BizPackage> findByProjectIdAndType(long projectId, String type) {
        List<BizPackage> list = jdbcTemplate.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM biz_package "
                        + "WHERE project_id = ? AND type = ? AND status <> 'DELETED' "
                        + "ORDER BY id ASC LIMIT 1",
                packageRowMapper,
                projectId, type
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    @Override
    public List<BizPackage> searchByProjectIdAndType(long projectId, String type,
                                                     int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM biz_package "
                        + "WHERE project_id = ? AND type = ? AND status <> 'DELETED' "
                        + "ORDER BY id DESC LIMIT ? OFFSET ?",
                packageRowMapper,
                projectId, type, limit, offset
        );
    }

    @Override
    public long countByProjectIdAndType(long projectId, String type) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_package "
                        + "WHERE project_id = ? AND type = ? AND status <> 'DELETED'",
                Long.class,
                projectId, type
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

    private static void setNullableDecimal(PreparedStatement ps, int index, BigDecimal value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
