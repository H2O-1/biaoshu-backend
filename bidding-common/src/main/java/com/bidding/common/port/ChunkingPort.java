package com.bidding.common.port;

import java.util.List;

/**
 * 文本切块（实现可在 ai 模块：Fake 或章节策略）。
 */
public interface ChunkingPort {

    /**
     * 将抽取结果切成若干块（可利用 Outline / 分页信息）。
     */
    List<ChunkDraft> chunk(DocumentTextExtractPort.ExtractResult source);

    /**
     * 仅有纯文本时的便捷入口。
     */
    default List<ChunkDraft> chunk(String sourceText) {
        DocumentTextExtractPort.ExtractResult result = new DocumentTextExtractPort.ExtractResult();
        result.setFullText(sourceText);
        return chunk(result);
    }

    /** 切块草稿（尚未落库）。 */
    class ChunkDraft {
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private int tokenEstimate;

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
    }
}
