package com.bidding.review.infrastructure.recall;

import com.bidding.common.port.VectorRecallPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 范围内切块 BM25 词法召回（与向量召回同过滤条件；不依赖 embedding）。
 */
@Component
public class Bm25LexicalRecall {

    private static final Logger log = LoggerFactory.getLogger(Bm25LexicalRecall.class);

    private final JdbcTemplate jdbcTemplate;

    public Bm25LexicalRecall(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 多 query：候选只加载一次，再分别 BM25 topK。
     */
    public List<List<VectorRecallPort.RecallHit>> searchMulti(
            long orgId,
            long projectId,
            List<String> queryTexts,
            List<Long> packageIds,
            List<Long> documentIds,
            int topK) {
        List<List<VectorRecallPort.RecallHit>> perQuery = new ArrayList<>();
        if (queryTexts == null || queryTexts.isEmpty()) {
            return perQuery;
        }
        int safeTopK = topK < 1 ? 1 : topK;
        long t0 = System.currentTimeMillis();
        List<ChunkRow> rows = loadChunks(orgId, projectId, packageIds, documentIds);
        long loadMs = System.currentTimeMillis() - t0;

        List<String> contents = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            contents.add(rows.get(i).content == null ? "" : rows.get(i).content);
        }
        t0 = System.currentTimeMillis();
        OkapiBm25 index = OkapiBm25.index(contents);
        for (int q = 0; q < queryTexts.size(); q++) {
            perQuery.add(rankTopK(index, rows, queryTexts.get(q), safeTopK));
        }
        long scoreMs = System.currentTimeMillis() - t0;
        log.info("BM25多query召回 projectId={} queryCount={} candidates={} loadMs={} scoreMs={}",
                projectId, queryTexts.size(), rows.size(), loadMs, scoreMs);
        return perQuery;
    }

    public List<VectorRecallPort.RecallHit> search(
            long orgId,
            long projectId,
            String queryText,
            List<Long> packageIds,
            List<Long> documentIds,
            int topK) {
        List<String> texts = new ArrayList<>();
        texts.add(queryText == null ? "" : queryText);
        List<List<VectorRecallPort.RecallHit>> multi =
                searchMulti(orgId, projectId, texts, packageIds, documentIds, topK);
        if (multi.isEmpty()) {
            return new ArrayList<>();
        }
        return multi.get(0);
    }

    private List<VectorRecallPort.RecallHit> rankTopK(
            OkapiBm25 index,
            List<ChunkRow> rows,
            String queryText,
            int topK) {
        List<String> qTokens = SimpleTextTokenizer.tokenize(queryText);
        if (qTokens.isEmpty() || rows.isEmpty()) {
            return new ArrayList<>();
        }
        List<VectorRecallPort.RecallHit> scored = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            float score = index.score(i, qTokens);
            if (score <= 0f) {
                continue;
            }
            ChunkRow row = rows.get(i);
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(row.chunkId);
            hit.setChunkIndex(row.chunkIndex);
            hit.setPageNo(row.pageNo);
            hit.setContent(row.content);
            hit.setDocumentId(row.documentId);
            hit.setFileName(row.fileName);
            hit.setPackageId(row.packageId);
            hit.setPackageType(row.packageType);
            hit.setPackageName(row.packageName);
            hit.setScore(score);
            scored.add(hit);
        }
        Collections.sort(scored, new Comparator<VectorRecallPort.RecallHit>() {
            @Override
            public int compare(VectorRecallPort.RecallHit a, VectorRecallPort.RecallHit b) {
                return Float.compare(b.getScore(), a.getScore());
            }
        });
        int limit = Math.min(topK, scored.size());
        List<VectorRecallPort.RecallHit> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i));
        }
        return result;
    }

    private List<ChunkRow> loadChunks(
            long orgId,
            long projectId,
            List<Long> packageIds,
            List<Long> documentIds) {
        List<Long> pkgs = VectorRecallPort.copyIds(packageIds);
        List<Long> docs = VectorRecallPort.copyIds(documentIds);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.id AS chunk_id, c.chunk_index, c.page_no, c.content, ");
        sql.append("c.document_id, c.package_id, ");
        sql.append("d.file_name, p.type AS package_type, p.name AS package_name ");
        sql.append("FROM biz_document_chunk c ");
        sql.append("INNER JOIN biz_document d ON d.id = c.document_id AND d.status <> 'DELETED' ");
        sql.append("INNER JOIN biz_package p ON p.id = c.package_id AND p.status <> 'DELETED' ");
        sql.append("WHERE c.org_id = ? AND d.index_status = 'SUCCESS'");

        List<Object> args = new ArrayList<>();
        args.add(orgId);
        if (projectId > 0) {
            sql.append(" AND c.project_id = ?");
            args.add(projectId);
        } else if (pkgs.isEmpty() && docs.isEmpty()) {
            return new ArrayList<>();
        }
        if (!pkgs.isEmpty()) {
            sql.append(" AND c.package_id IN (");
            appendPlaceholders(sql, pkgs.size());
            sql.append(")");
            for (int i = 0; i < pkgs.size(); i++) {
                args.add(pkgs.get(i));
            }
        }
        if (!docs.isEmpty()) {
            sql.append(" AND c.document_id IN (");
            appendPlaceholders(sql, docs.size());
            sql.append(")");
            for (int i = 0; i < docs.size(); i++) {
                args.add(docs.get(i));
            }
        }

        return jdbcTemplate.query(sql.toString(), args.toArray(), (rs, rowNum) -> {
            ChunkRow row = new ChunkRow();
            row.chunkId = rs.getLong("chunk_id");
            row.chunkIndex = rs.getInt("chunk_index");
            int page = rs.getInt("page_no");
            row.pageNo = rs.wasNull() ? null : page;
            row.content = rs.getString("content");
            row.documentId = rs.getLong("document_id");
            row.fileName = rs.getString("file_name");
            row.packageId = rs.getLong("package_id");
            row.packageType = rs.getString("package_type");
            row.packageName = rs.getString("package_name");
            return row;
        });
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
    }

    private static final class ChunkRow {
        private long chunkId;
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private long documentId;
        private String fileName;
        private long packageId;
        private String packageType;
        private String packageName;
    }
}
