package com.bidding.review.infrastructure.recall;

import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 embedding_json 的应用层余弦召回（暂不依赖 pgvector）。
 * 范围：org + project；可选 packageIds / documentIds 过滤。
 * 多 query 时候选只加载解析一次，再在内存打分。
 */
@Component
public class JdbcCosineVectorRecallPort implements VectorRecallPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcCosineVectorRecallPort.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcCosineVectorRecallPort(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<RecallHit> recall(RecallQuery query) {
        List<RecallHit> empty = new ArrayList<>();
        if (query == null || query.getQueryEmbedding() == null || query.getQueryEmbedding().length == 0) {
            return empty;
        }
        int topK = query.getTopK() < 1 ? 1 : query.getTopK();
        List<Candidate> candidates = loadCandidates(
                query.getOrgId(),
                query.getProjectId(),
                VectorRecallPort.copyIds(query.getPackageIds()),
                VectorRecallPort.copyIds(query.getDocumentIds()));
        return rankTopK(query.getQueryEmbedding(), candidates, topK);
    }

    @Override
    public RecallMultiResult recallMulti(RecallMultiQuery query) {
        RecallMultiResult result = new RecallMultiResult();
        List<List<RecallHit>> perQuery = new ArrayList<>();
        result.setPerQueryHits(perQuery);
        if (query == null) {
            return result;
        }
        List<float[]> embeddings = query.getQueryEmbeddings();
        if (embeddings == null || embeddings.isEmpty()) {
            return result;
        }
        int topK = query.getTopK() < 1 ? 1 : query.getTopK();

        long t0 = System.currentTimeMillis();
        List<Candidate> candidates = loadCandidates(
                query.getOrgId(),
                query.getProjectId(),
                VectorRecallPort.copyIds(query.getPackageIds()),
                VectorRecallPort.copyIds(query.getDocumentIds()));
        long loadMs = System.currentTimeMillis() - t0;
        result.setCandidateCount(candidates.size());
        result.setLoadMs(loadMs);

        t0 = System.currentTimeMillis();
        for (int i = 0; i < embeddings.size(); i++) {
            float[] qv = embeddings.get(i);
            if (qv == null || qv.length == 0) {
                perQuery.add(new ArrayList<>());
                continue;
            }
            perQuery.add(rankTopK(qv, candidates, topK));
        }
        long scoreMs = System.currentTimeMillis() - t0;
        result.setScoreMs(scoreMs);
        log.info("向量多query召回 projectId={} queryCount={} candidates={} loadMs={} scoreMs={}",
                query.getProjectId(), embeddings.size(), candidates.size(), loadMs, scoreMs);
        return result;
    }

    private List<Candidate> loadCandidates(long orgId,
                                           long projectId,
                                           List<Long> packageIds,
                                           List<Long> documentIds) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.id AS chunk_id, c.chunk_index, c.page_no, c.content, ");
        sql.append("c.document_id, c.package_id, ");
        sql.append("d.file_name, p.type AS package_type, p.name AS package_name, ");
        sql.append("v.embedding_json, v.embedding_dim ");
        sql.append("FROM biz_document_chunk c ");
        sql.append("INNER JOIN biz_document_vector v ON v.chunk_id = c.id ");
        sql.append("INNER JOIN biz_document d ON d.id = c.document_id AND d.status <> 'DELETED' ");
        sql.append("INNER JOIN biz_package p ON p.id = c.package_id AND p.status <> 'DELETED' ");
        sql.append("WHERE c.org_id = ? AND c.project_id = ? AND d.index_status = 'SUCCESS'");

        List<Object> args = new ArrayList<>();
        args.add(orgId);
        args.add(projectId);

        if (!packageIds.isEmpty()) {
            sql.append(" AND c.package_id IN (");
            appendPlaceholders(sql, packageIds.size());
            sql.append(")");
            for (int i = 0; i < packageIds.size(); i++) {
                args.add(packageIds.get(i));
            }
        }
        if (!documentIds.isEmpty()) {
            sql.append(" AND c.document_id IN (");
            appendPlaceholders(sql, documentIds.size());
            sql.append(")");
            for (int i = 0; i < documentIds.size(); i++) {
                args.add(documentIds.get(i));
            }
        }

        List<Candidate> raw = jdbcTemplate.query(
                sql.toString(),
                args.toArray(),
                (rs, rowNum) -> {
                    Candidate c = new Candidate();
                    c.chunkId = rs.getLong("chunk_id");
                    c.chunkIndex = rs.getInt("chunk_index");
                    int page = rs.getInt("page_no");
                    if (rs.wasNull()) {
                        c.pageNo = null;
                    } else {
                        c.pageNo = page;
                    }
                    c.content = rs.getString("content");
                    c.documentId = rs.getLong("document_id");
                    c.fileName = rs.getString("file_name");
                    c.packageId = rs.getLong("package_id");
                    c.packageType = rs.getString("package_type");
                    c.packageName = rs.getString("package_name");
                    c.embeddingDim = rs.getInt("embedding_dim");
                    c.embeddingJson = rs.getString("embedding_json");
                    return c;
                }
        );

        List<Candidate> ready = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Candidate c = raw.get(i);
            float[] vec = parseEmbedding(c.embeddingJson, c.embeddingDim);
            if (vec == null) {
                log.warn("跳过无法解析的向量 chunkId={}", c.chunkId);
                continue;
            }
            c.embedding = vec;
            c.embeddingJson = null;
            ready.add(c);
        }
        return ready;
    }

    private List<RecallHit> rankTopK(float[] queryVec, List<Candidate> candidates, int topK) {
        List<RecallHit> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (c.embedding == null || c.embedding.length != queryVec.length) {
                if (c.embedding != null) {
                    log.warn("跳过维度不一致的向量 chunkId={} vecDim={} queryDim={}",
                            c.chunkId, c.embedding.length, queryVec.length);
                }
                continue;
            }
            float score = cosine(queryVec, c.embedding);
            RecallHit hit = new RecallHit();
            hit.setChunkId(c.chunkId);
            hit.setChunkIndex(c.chunkIndex);
            hit.setPageNo(c.pageNo);
            hit.setContent(c.content);
            hit.setDocumentId(c.documentId);
            hit.setFileName(c.fileName);
            hit.setPackageId(c.packageId);
            hit.setPackageType(c.packageType);
            hit.setPackageName(c.packageName);
            hit.setScore(score);
            scored.add(hit);
        }

        Collections.sort(scored, new Comparator<RecallHit>() {
            @Override
            public int compare(RecallHit a, RecallHit b) {
                return Float.compare(b.getScore(), a.getScore());
            }
        });

        List<RecallHit> result = new ArrayList<>();
        int limit = topK;
        if (limit > scored.size()) {
            limit = scored.size();
        }
        for (int i = 0; i < limit; i++) {
            result.add(scored.get(i));
        }
        return result;
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
    }

    private float[] parseEmbedding(String json, int expectedDim) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return null;
            }
            int size = node.size();
            if (expectedDim > 0 && size != expectedDim) {
                // 仍按实际长度解析，调用方再比 query 维
            }
            float[] vector = new float[size];
            for (int i = 0; i < size; i++) {
                vector[i] = (float) node.get(i).asDouble();
            }
            return vector;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 余弦相似度；零向量返回 0。
     */
    static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0f;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private static class Candidate {
        private long chunkId;
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private long documentId;
        private String fileName;
        private long packageId;
        private String packageType;
        private String packageName;
        private int embeddingDim;
        private String embeddingJson;
        private float[] embedding;
    }
}
