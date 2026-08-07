package com.bidding.review.infrastructure.recall;

import com.bidding.common.port.VectorRecallPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：按名次融合多路召回，避免向量分与 BM25 分不可比。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /**
     * @param lists 各路已按相关度降序的命中列表
     * @param rrfK  平滑常数，常用 60
     * @param topN  ≤0 表示不截断
     */
    public static List<VectorRecallPort.RecallHit> fuse(
            List<List<VectorRecallPort.RecallHit>> lists,
            int rrfK,
            int topN) {
        if (lists == null || lists.isEmpty()) {
            return new ArrayList<>();
        }
        int k = rrfK < 1 ? 60 : rrfK;
        Map<Long, Float> scores = new HashMap<>();
        Map<Long, VectorRecallPort.RecallHit> byId = new HashMap<>();
        for (int li = 0; li < lists.size(); li++) {
            List<VectorRecallPort.RecallHit> list = lists.get(li);
            if (list == null) {
                continue;
            }
            for (int rank = 0; rank < list.size(); rank++) {
                VectorRecallPort.RecallHit hit = list.get(rank);
                if (hit == null) {
                    continue;
                }
                long id = hit.getChunkId();
                float add = 1f / (k + rank + 1);
                Float old = scores.get(id);
                scores.put(id, old == null ? add : old + add);
                if (!byId.containsKey(id)) {
                    byId.put(id, hit);
                }
            }
        }
        List<VectorRecallPort.RecallHit> fused = new ArrayList<>();
        for (Map.Entry<Long, Float> e : scores.entrySet()) {
            VectorRecallPort.RecallHit src = byId.get(e.getKey());
            if (src == null) {
                continue;
            }
            VectorRecallPort.RecallHit copy = copyHit(src);
            copy.setScore(e.getValue());
            fused.add(copy);
        }
        Collections.sort(fused, new Comparator<VectorRecallPort.RecallHit>() {
            @Override
            public int compare(VectorRecallPort.RecallHit a, VectorRecallPort.RecallHit b) {
                return Float.compare(b.getScore(), a.getScore());
            }
        });
        if (topN > 0 && fused.size() > topN) {
            return new ArrayList<>(fused.subList(0, topN));
        }
        return fused;
    }

    /** 两路便捷入口。 */
    public static List<VectorRecallPort.RecallHit> fuse(
            List<VectorRecallPort.RecallHit> a,
            List<VectorRecallPort.RecallHit> b,
            int rrfK,
            int topN) {
        List<List<VectorRecallPort.RecallHit>> lists = new ArrayList<>();
        lists.add(a == null ? new ArrayList<>() : a);
        lists.add(b == null ? new ArrayList<>() : b);
        return fuse(lists, rrfK, topN);
    }

    private static VectorRecallPort.RecallHit copyHit(VectorRecallPort.RecallHit src) {
        VectorRecallPort.RecallHit h = new VectorRecallPort.RecallHit();
        h.setChunkId(src.getChunkId());
        h.setChunkIndex(src.getChunkIndex());
        h.setPageNo(src.getPageNo());
        h.setContent(src.getContent());
        h.setDocumentId(src.getDocumentId());
        h.setFileName(src.getFileName());
        h.setPackageId(src.getPackageId());
        h.setPackageType(src.getPackageType());
        h.setPackageName(src.getPackageName());
        h.setScore(src.getScore());
        return h;
    }
}
