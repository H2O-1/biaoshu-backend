package com.bidding.common.port;

import java.util.List;

/**
 * 文档索引产物落库：切块 + 向量（实现在 review 模块）。
 */
public interface DocumentIndexStorePort {

    /**
     * 覆盖写入某文档的切块与向量（先删旧再插新）。
     *
     * @param modelName 向量模型名
     */
    void replaceAll(long orgId,
                    Long projectId,
                    long packageId,
                    long documentId,
                    String modelName,
                    List<IndexedChunk> chunks);

    /**
     * 按文档删除切块与向量。
     */
    void deleteByDocumentId(long documentId);

    /**
     * 按本组织文档读取已生成的切块，供招标解析等后续 LLM 任务使用。
     */
    List<ChunkView> listChunksByDocumentIds(long orgId, List<Long> documentIds);

    /** 一条切块及其向量。 */
    class IndexedChunk {
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private int tokenEstimate;
        private float[] embedding;

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

        public int getTokenEstimate() {
            return tokenEstimate;
        }

        public void setTokenEstimate(int tokenEstimate) {
            this.tokenEstimate = tokenEstimate;
        }

        public float[] getEmbedding() {
            return embedding;
        }

        public void setEmbedding(float[] embedding) {
            this.embedding = embedding;
        }
    }

    /** 已落库切块视图。 */
    class ChunkView {
        private long chunkId;
        private long documentId;
        private String fileName;
        private int chunkIndex;
        private Integer pageNo;
        private String content;

        public long getChunkId() {
            return chunkId;
        }

        public void setChunkId(long chunkId) {
            this.chunkId = chunkId;
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
    }
}
