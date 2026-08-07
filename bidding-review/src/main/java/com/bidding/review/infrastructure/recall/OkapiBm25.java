package com.bidding.review.infrastructure.recall;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经典 Okapi BM25（k1=1.2, b=0.75），语料为当前召回范围内的切块。
 */
final class OkapiBm25 {

    private static final float K1 = 1.2f;
    private static final float B = 0.75f;

    private final int docCount;
    private final float avgdl;
    /** token → 含该 token 的文档数 */
    private final Map<String, Integer> df;
    private final List<Map<String, Integer>> docTfs;
    private final int[] docLens;

    private OkapiBm25(int docCount,
                      float avgdl,
                      Map<String, Integer> df,
                      List<Map<String, Integer>> docTfs,
                      int[] docLens) {
        this.docCount = docCount;
        this.avgdl = avgdl;
        this.df = df;
        this.docTfs = docTfs;
        this.docLens = docLens;
    }

    static OkapiBm25 index(List<String> documents) {
        int n = documents == null ? 0 : documents.size();
        List<Map<String, Integer>> tfs = new java.util.ArrayList<>(n);
        int[] lens = new int[n];
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;
        for (int i = 0; i < n; i++) {
            List<String> tokens = SimpleTextTokenizer.tokenize(documents.get(i));
            Map<String, Integer> tf = new HashMap<>();
            for (int t = 0; t < tokens.size(); t++) {
                String tok = tokens.get(t);
                Integer c = tf.get(tok);
                tf.put(tok, c == null ? 1 : c + 1);
            }
            tfs.add(tf);
            lens[i] = tokens.size();
            totalLen += tokens.size();
            for (String tok : tf.keySet()) {
                Integer d = df.get(tok);
                df.put(tok, d == null ? 1 : d + 1);
            }
        }
        float avgdl = n == 0 ? 0f : (float) totalLen / (float) n;
        return new OkapiBm25(n, avgdl, df, tfs, lens);
    }

    float score(int docIndex, List<String> queryTokens) {
        if (docIndex < 0 || docIndex >= docCount || queryTokens == null || queryTokens.isEmpty()) {
            return 0f;
        }
        Map<String, Integer> tf = docTfs.get(docIndex);
        int dl = docLens[docIndex];
        float score = 0f;
        Map<String, Boolean> seen = new HashMap<>();
        for (int i = 0; i < queryTokens.size(); i++) {
            String q = queryTokens.get(i);
            if (seen.containsKey(q)) {
                continue;
            }
            seen.put(q, Boolean.TRUE);
            Integer dfCount = df.get(q);
            if (dfCount == null || dfCount == 0) {
                continue;
            }
            Integer fObj = tf.get(q);
            int f = fObj == null ? 0 : fObj;
            if (f == 0) {
                continue;
            }
            float idf = (float) Math.log(1.0 + (docCount - dfCount + 0.5) / (dfCount + 0.5));
            float denom = f + K1 * (1f - B + B * (avgdl <= 0f ? 0f : (dl / avgdl)));
            score += idf * (f * (K1 + 1f)) / denom;
        }
        return score;
    }
}
