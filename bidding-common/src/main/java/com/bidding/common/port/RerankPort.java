package com.bidding.common.port;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本重排（实现可在 ai 模块：百炼 qwen3-rerank 等）。
 * 重排结果不入库；业务侧须打日志。
 */
public interface RerankPort {

    /**
     * 按 query 对候选文档重排，返回 topN（按相关分降序）。
     * 每条结果的 index 对应入参 documents 下标。
     */
    List<RerankHit> rerank(String query, List<String> documents, int topN, String instruct);

    /** 单条重排命中。 */
    class RerankHit {
        private int index;
        private float score;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public float getScore() {
            return score;
        }

        public void setScore(float score) {
            this.score = score;
        }
    }

    static List<String> copyDocuments(List<String> source) {
        List<String> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (int i = 0; i < source.size(); i++) {
            String text = source.get(i);
            copy.add(text == null ? "" : text);
        }
        return copy;
    }
}
