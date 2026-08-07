package com.bidding.common.port;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回：按项目范围检索相似切块（实现可换：内存余弦 / pgvector 等）。
 * 召回列表不入库；业务侧须打日志。
 */
public interface VectorRecallPort {

    /**
     * 在指定组织+项目下，用 query 向量召回 topK 切块。
     * 可选 packageIds / documentIds 做文件区分过滤；不传则搜项目下全部已索引文档。
     */
    List<RecallHit> recall(RecallQuery query);

    /**
     * 多 query 召回：范围内切块向量只加载并解析一次，再对每个 query 做内存余弦 topK。
     * 返回与 {@link RecallMultiQuery#getQueryEmbeddings()} 等长的列表。
     */
    RecallMultiResult recallMulti(RecallMultiQuery query);

    /** 单 query 召回请求。 */
    class RecallQuery {
        private long orgId;
        private long projectId;
        private float[] queryEmbedding;
        private int topK;
        private List<Long> packageIds;
        private List<Long> documentIds;

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public float[] getQueryEmbedding() {
            return queryEmbedding;
        }

        public void setQueryEmbedding(float[] queryEmbedding) {
            this.queryEmbedding = queryEmbedding;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public List<Long> getPackageIds() {
            return packageIds;
        }

        public void setPackageIds(List<Long> packageIds) {
            this.packageIds = packageIds;
        }

        public List<Long> getDocumentIds() {
            return documentIds;
        }

        public void setDocumentIds(List<Long> documentIds) {
            this.documentIds = documentIds;
        }
    }

    /** 多 query 召回请求（queryEmbeddings 已由调用方批量 embed）。 */
    class RecallMultiQuery {
        private long orgId;
        private long projectId;
        private List<float[]> queryEmbeddings;
        private int topK;
        private List<Long> packageIds;
        private List<Long> documentIds;

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public List<float[]> getQueryEmbeddings() {
            return queryEmbeddings;
        }

        public void setQueryEmbeddings(List<float[]> queryEmbeddings) {
            this.queryEmbeddings = queryEmbeddings;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public List<Long> getPackageIds() {
            return packageIds;
        }

        public void setPackageIds(List<Long> packageIds) {
            this.packageIds = packageIds;
        }

        public List<Long> getDocumentIds() {
            return documentIds;
        }

        public void setDocumentIds(List<Long> documentIds) {
            this.documentIds = documentIds;
        }
    }

    /** 多 query 召回结果（含加载/打分耗时，便于运维）。 */
    class RecallMultiResult {
        private List<List<RecallHit>> perQueryHits = new ArrayList<>();
        private int candidateCount;
        private long loadMs;
        private long scoreMs;

        public List<List<RecallHit>> getPerQueryHits() {
            return perQueryHits;
        }

        public void setPerQueryHits(List<List<RecallHit>> perQueryHits) {
            this.perQueryHits = perQueryHits == null ? new ArrayList<>() : perQueryHits;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public void setCandidateCount(int candidateCount) {
            this.candidateCount = candidateCount;
        }

        public long getLoadMs() {
            return loadMs;
        }

        public void setLoadMs(long loadMs) {
            this.loadMs = loadMs;
        }

        public long getScoreMs() {
            return scoreMs;
        }

        public void setScoreMs(long scoreMs) {
            this.scoreMs = scoreMs;
        }
    }

    /** 单条召回命中（含文件区分字段）。 */
    class RecallHit {
        private long chunkId;
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private long documentId;
        private String fileName;
        private long packageId;
        private String packageType;
        private String packageName;
        private float score;

        public long getChunkId() {
            return chunkId;
        }

        public void setChunkId(long chunkId) {
            this.chunkId = chunkId;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public Integer getPageNo() {
            return pageNo;
        }

        public void setPageNo(Integer pageNo) {
            this.pageNo = pageNo;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public String getPackageType() {
            return packageType;
        }

        public void setPackageType(String packageType) {
            this.packageType = packageType;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public float getScore() {
            return score;
        }

        public void setScore(float score) {
            this.score = score;
        }
    }

    /**
     * 工具：复制可选 ID 列表，避免外部改动影响内部。
     */
    static List<Long> copyIds(List<Long> source) {
        List<Long> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (int i = 0; i < source.size(); i++) {
            Long id = source.get(i);
            if (id != null) {
                copy.add(id);
            }
        }
        return copy;
    }
}
