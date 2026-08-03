package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.DocumentIndexStorePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 切块 + 向量 JDBC 落库。
 */
@Repository
public class JdbcDocumentIndexStore implements DocumentIndexStorePort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcDocumentIndexStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void replaceAll(long orgId,
                           Long projectId,
                           long packageId,
                           long documentId,
                           String modelName,
                           List<IndexedChunk> chunks) {
        deleteByDocumentId(documentId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String safeModel = modelName;
        if (safeModel == null || safeModel.trim().isEmpty()) {
            safeModel = "unknown";
        }
        for (int i = 0; i < chunks.size(); i++) {
            IndexedChunk chunk = chunks.get(i);
            long chunkId = insertChunk(orgId, projectId, packageId, documentId, chunk);
            insertVector(orgId, projectId, documentId, chunkId, safeModel, chunk.getEmbedding());
        }
    }

    @Override
    @Transactional
    public void deleteByDocumentId(long documentId) {
        // 先删向量再删切块（无外键时也保持顺序清晰）
        jdbcTemplate.update("DELETE FROM biz_document_vector WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM biz_document_chunk WHERE document_id = ?", documentId);
    }

    private long insertChunk(long orgId,
                             Long projectId,
                             long packageId,
                             long documentId,
                             IndexedChunk chunk) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO biz_document_chunk (org_id, project_id, package_id, document_id, "
                            + "chunk_index, page_no, content, token_estimate, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            if (projectId == null) {
                ps.setObject(2, null);
            } else {
                ps.setLong(2, projectId);
            }
            ps.setLong(3, packageId);
            ps.setLong(4, documentId);
            ps.setInt(5, chunk.getChunkIndex());
            if (chunk.getPageNo() == null) {
                ps.setObject(6, null);
            } else {
                ps.setInt(6, chunk.getPageNo());
            }
            ps.setString(7, chunk.getContent());
            ps.setInt(8, chunk.getTokenEstimate());
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
            throw new IllegalStateException("插入切块未返回主键");
        }
        return key.longValue();
    }

    private void insertVector(long orgId,
                              Long projectId,
                              long documentId,
                              long chunkId,
                              String modelName,
                              float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        String json = toJsonArray(embedding);
        jdbcTemplate.update(
                "INSERT INTO biz_document_vector (org_id, project_id, document_id, chunk_id, "
                        + "embedding_dim, embedding_json, model_name, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                orgId,
                projectId,
                documentId,
                chunkId,
                embedding.length,
                json,
                modelName
        );
    }

    private static String toJsonArray(float[] embedding) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
