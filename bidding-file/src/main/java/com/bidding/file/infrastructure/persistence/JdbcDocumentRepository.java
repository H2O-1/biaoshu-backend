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
            "id, org_id, project_id, package_id, file_name, content_type, file_ext, size_bytes, "
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
    public long insert(long orgId,
                       Long projectId,
                       long packageId,
                       String fileName,
                       String contentType,
                       String fileExt,
                       long sizeBytes,
                       String objectKey,
                       String indexStatus,
                       String status,
                       long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO biz_document(org_id, project_id, package_id, file_name, content_type, "
                            + "file_ext, size_bytes, object_key, index_status, status, created_by, "
                            + "created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
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
            ps.setLong(7, sizeBytes);
            ps.setString(8, objectKey);
            ps.setString(9, indexStatus);
            ps.setString(10, status);
            ps.setLong(11, createdBy);
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

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
