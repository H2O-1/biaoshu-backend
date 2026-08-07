package com.bidding.review.infrastructure.recall;

import com.bidding.common.port.VectorRecallPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bm25AndRrfTest {

    @Test
    void tokenizerEmitsCjkUnigramsAndBigrams() {
        List<String> tokens = SimpleTextTokenizer.tokenize("二级资质 ISO9001");
        assertTrue(tokens.contains("二"));
        assertTrue(tokens.contains("二级"));
        assertTrue(tokens.contains("iso9001"));
    }

    @Test
    void bm25RanksExactKeywordHigher() {
        List<String> docs = new ArrayList<>();
        docs.add("本公司经营范围包括软件开发与系统集成");
        docs.add("投标人须具备建筑工程施工总承包二级资质");
        docs.add("项目进度计划与人员安排说明");
        OkapiBm25 index = OkapiBm25.index(docs);
        List<String> q = SimpleTextTokenizer.tokenize("二级资质");
        float s0 = index.score(0, q);
        float s1 = index.score(1, q);
        float s2 = index.score(2, q);
        assertTrue(s1 > s0);
        assertTrue(s1 > s2);
    }

    @Test
    void rrfPrefersChunkInBothLists() {
        VectorRecallPort.RecallHit a1 = hit(1L, 0.9f);
        VectorRecallPort.RecallHit a2 = hit(2L, 0.8f);
        VectorRecallPort.RecallHit b1 = hit(2L, 10f);
        VectorRecallPort.RecallHit b2 = hit(3L, 9f);
        List<VectorRecallPort.RecallHit> vector = new ArrayList<>();
        vector.add(a1);
        vector.add(a2);
        List<VectorRecallPort.RecallHit> bm25 = new ArrayList<>();
        bm25.add(b1);
        bm25.add(b2);
        List<VectorRecallPort.RecallHit> fused = RrfFusion.fuse(vector, bm25, 60, 3);
        assertEquals(3, fused.size());
        assertEquals(2L, fused.get(0).getChunkId());
    }

    private static VectorRecallPort.RecallHit hit(long id, float score) {
        VectorRecallPort.RecallHit h = new VectorRecallPort.RecallHit();
        h.setChunkId(id);
        h.setContent("c" + id);
        h.setScore(score);
        return h;
    }
}
