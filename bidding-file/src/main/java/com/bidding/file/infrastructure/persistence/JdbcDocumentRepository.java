package com.bidding.file.infrastructure.persistence;

import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
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
 * 文档表 JDBC 实现。
 */
@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private static final String DOC_COLUMNS =
            "id, org_id, project_id, package_id, file_name, content_type, file_ext, file_type, size_bytes, "
                    + "object_key, index_status, status, created_by, created_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Document> rowMapper = (rs, rowNum) -> new Document(
            rs.getLong("id"),
            rs.getLong("org_id"),
            rs.getObject("project_id", Long.class),
            rs.getLong("package_id"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getString("file_ext"),
            rs.getString("file_type"),
            rs.getLong("size_bytes"),
            rs.getString("object_key"),
            rs.getString("index_status"),
            rs.getString("status"),
            rs.getLong("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    @Override
    public Optional<Document> findById(long documentId) {
        List<Document> list = jdbcTemplate.query(
                "SELECT " + DOC_COLUMNS + " FROM biz_document WHERE id = ?",
                rowMapper,
                documentId
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    @Override
    public List<Document> listByPackageId(long packageId) {
        return jdbcTemplate.query(
                "SELECT " + DOC_COLUMNS + " FROM biz_document "
                        + "WHERE package_id = ? AND status <> 'DELETED' ORDER BY id DESC",
                rowMapper,
                packageId
        );
    }

    @Override
    public long countActiveByPackageId(long packageId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_document WHERE package_id = ? AND status <> 'DELETED'",
                Long.class,
                packageId
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveByOrgId(long orgId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_document WHERE org_id = ? AND status <> 'DELETED'",
                Long.class,
                orgId
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveByOrgIdAndIndexStatus(long orgId, String indexStatus) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_document WHERE org_id = ? AND status <> 'DELETED' AND index_status = ?",
                Long.class,
                orgId,
                indexStatus
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveByIndexStatus(String indexStatus) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_document WHERE status <> 'DELETED' AND index_status = ?",
                Long.class,
                indexStatus
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveByProjectIdAndPackageType(long projectId, String packageType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_document d "
                        + "INNER JOIN biz_package p ON p.id = d.package_id "
                        + "WHERE d.project_id = ? AND d.status <> 'DELETED' "
                        + "AND p.status <> 'DELETED' AND p.type = ?",
                Long.class,
                projectId,
                packageType
        );
        return count == null ? 0 : count;
    }

    @Override
    public long countActiveNotSuccessIndexByProjectIdAndPackageType(long projectId, String packageType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_document d "
                        + "INNER JOIN biz_package p ON p.id = d.package_id "
                        + "WHERE d.project_id = ? AND d.status <> 'DELETED' "
                        + "AND p.status <> 'DELETED' AND p.type = ? "
                        + "AND d.index_status <> 'SUCCESS'",
                Long.class,
                projectId,
                packageType
        );
        return count == null ? 0 : count;
    }

    @Override
    public Optional<String> findActiveFileNameByProjectIdAndPackageTypeAndFileType(
            long projectId, String packageType, String fileType) {
        List<String> list = jdbcTemplate.query(
                "SELECT d.file_name FROM biz_document d "
                        + "INNER JOIN biz_package p ON p.id = d.package_id "
                        + "WHERE d.project_id = ? AND d.status <> 'DELETED' "
                        + "AND p.status <> 'DELETED' AND p.type = ? "
                        + "AND d.file_type = ? "
                        + "ORDER BY d.id DESC LIMIT 1",
                (rs, rowNum) -> rs.getString("file_name"),
                projectId,
                packageType,
                fileType
        );
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(list.get(0));
    }

    @Override
    public long countPackagesWithActiveFilesByProjectIdAndPackageType(long projectId, String packageType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_package p "
                        + "WHERE p.project_id = ? AND p.type = ? AND p.status <> 'DELETED' "
                        + "AND EXISTS ("
                        + "  SELECT 1 FROM biz_document d "
                        + "  WHERE d.package_id = p.id AND d.status <> 'DELETED'"
                        + ")",
                Long.class,
                projectId,
                packageType
        );
        return count == null ? 0 : count;
    }

    @Override
    public String resolveIndexStatusByPackageId(long packageId) {
        List<String> statuses = jdbcTemplate.query(
                "SELECT DISTINCT index_status FROM biz_document "
                        + "WHERE package_id = ? AND status <> 'DELETED'",
                (rs, rowNum) -> rs.getString(1),
                packageId
        );
        if (statuses == null || statuses.isEmpty()) {
            return null;
        }
        boolean hasFailed = false;
        boolean hasProcessing = false;
        boolean hasPending = false;
        boolean hasSuccess = false;
        for (int i = 0; i < statuses.size(); i++) {
            String s = statuses.get(i);
            if (s == null) {
                continue;
            }
            if ("FAILED".equals(s)) {
                hasFailed = true;
            } else if ("PROCESSING".equals(s)) {
                hasProcessing = true;
            } else if ("PENDING".equals(s)) {
                hasPending = true;
            } else if ("SUCCESS".equals(s)) {
                hasSuccess = true;
            }
        }
        // 包级展示取最差状态，便于前端提示解析进度
        if (hasFailed) {
            return "FAILED";
        }
        if (hasProcessing) {
            return "PROCESSING";
        }
        if (hasPending) {
            return "PENDING";
        }
        if (hasSuccess) {
            return "SUCCESS";
        }
        return null;
    }

    @Override
    public long insert(long orgId,
                       Long projectId,
                       long packageId,
                       String fileName,
                       String contentType,
                       String fileExt,
                       String fileType,
                       long sizeBytes,
                       String objectKey,
                       String indexStatus,
                       String status,
                       long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO biz_document(org_id, project_id, package_id, file_name, content_type, "
                            + "file_ext, file_type, size_bytes, object_key, index_status, status, created_by, "
                            + "created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            if (projectId == null) {
                ps.setNull(2, Types.BIGINT);
            } else {
                ps.setLong(2, projectId);
            }
            ps.setLong(3, packageId);
            ps.setString(4, fileName);
            if (contentType == null) {
                ps.setNull(5, Types.VARCHAR);
            } else {
                ps.setString(5, contentType);
            }
            ps.setString(6, fileExt);
            ps.setString(7, fileType);
            ps.setLong(8, sizeBytes);
            ps.setString(9, objectKey);
            ps.setString(10, indexStatus);
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
            throw new IllegalStateException("插入文档未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void markDeleted(long documentId) {
        jdbcTemplate.update(
                "UPDATE biz_document SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                documentId
        );
    }

    @Override
    public void updateIndexStatus(long documentId, String indexStatus) {
        jdbcTemplate.update(
                "UPDATE biz_document SET index_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                indexStatus,
                documentId
        );
    }

    @Override
    public int bindProjectIdByPackageId(long packageId, long projectId) {
        return jdbcTemplate.update(
                "UPDATE biz_document SET project_id = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE package_id = ? AND project_id IS NULL AND status <> 'DELETED'",
                projectId,
                packageId
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
