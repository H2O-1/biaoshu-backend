package com.bidding.review.application;

import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RerankPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审查管线：按投标包全量对照（包 × 清单条目）。
 * 召回仅用 bidSearch（无则不召回）；产品向 LLM（review-v2）落库；无项目级 overallVerdict。
 */
@Service
public class ReviewComparePipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewComparePipeline.class);

    private final EmbeddingPort embeddingPort;
    private final VectorRecallPort vectorRecallPort;
    private final RerankPort rerankPort;
    private final LlmPort llmPort;
    private final PackageLookupPort packageLookupPort;
    private final ObjectMapper objectMapper;
    private final int defaultRecallTopK;
    private final int bidRerankTopN;
    private final String bidInstruct;

    public ReviewComparePipeline(
            EmbeddingPort embeddingPort,
            VectorRecallPort vectorRecallPort,
            RerankPort rerankPort,
            LlmPort llmPort,
            PackageLookupPort packageLookupPort,
            ObjectMapper objectMapper,
            @Value("${bidding.review.recall-top-k:30}") int defaultRecallTopK,
            @Value("${bidding.review.rerank-top-n:${bidding.rerank.bid-top-n:6}}") int bidRerankTopN,
            @Value("${bidding.rerank.bid-instruct:给定一条招标要求，从投标文件中检索对该要求作出响应、举证或说明的相关段落。}")
            String bidInstruct) {
        this.embeddingPort = embeddingPort;
        this.vectorRecallPort = vectorRecallPort;
        this.rerankPort = rerankPort;
        this.llmPort = llmPort;
        this.packageLookupPort = packageLookupPort;
        this.objectMapper = objectMapper;
        this.defaultRecallTopK = defaultRecallTopK < 1 ? 30 : defaultRecallTopK;
        this.bidRerankTopN = bidRerankTopN < 1 ? 6 : bidRerankTopN;
        this.bidInstruct = bidInstruct;
    }

    /**
     * 对每个投标包、清单全部条目做对照，返回可落库条目（数量 ≈ 包数 × 条目数）。
     */
    public PipelineResult run(
            long orgId,
            long projectId,
            List<RequirementChecklistPort.ItemView> requirements,
            List<Long> bidPackageIds,
            int recallTopK,
            int rerankTopN,
            ProgressCallback progress) {
        int safeRecallTopK = recallTopK < 1 ? defaultRecallTopK : recallTopK;
        int safeRerankTopN = rerankTopN < 1 ? bidRerankTopN : rerankTopN;

        Map<Long, String> supplierByPackage = new LinkedHashMap<>();
        int pkgCount = bidPackageIds == null ? 0 : bidPackageIds.size();
        for (int p = 0; p < pkgCount; p++) {
            long packageId = bidPackageIds.get(p);
            PackageLookupPort.PackageRef pkg = packageLookupPort.findActiveById(packageId);
            String name = pkg == null || pkg.getName() == null ? ("包" + packageId) : pkg.getName();
            supplierByPackage.put(packageId, name);
        }

        List<ReviewPort.ItemDraft> drafts = new ArrayList<>();
        int reqCount = requirements == null ? 0 : requirements.size();
        int totalSteps = Math.max(reqCount * pkgCount, 1);
        int done = 0;

        for (int p = 0; p < pkgCount; p++) {
            long packageId = bidPackageIds.get(p);
            String supplier = supplierByPackage.get(packageId);
            for (int i = 0; i < reqCount; i++) {
                RequirementChecklistPort.ItemView req = requirements.get(i);
                if (progress != null) {
                    int pct = 10 + (int) ((75.0 * done) / totalSteps);
                    progress.onProgress(pct, "AI审查中");
                }
                ReviewPort.ItemDraft draft = compareOne(
                        orgId, projectId, packageId, supplier, req, safeRecallTopK, safeRerankTopN);
                draft.setSortOrder(drafts.size());
                drafts.add(draft);
                done++;
            }
        }

        if (progress != null) {
            progress.onProgress(90, "AI审查中");
        }
        PipelineResult result = new PipelineResult();
        result.setItems(drafts);
        result.setOverallVerdict(null);
        return result;
    }

    private ReviewPort.ItemDraft compareOne(
            long orgId,
            long projectId,
            long packageId,
            String supplier,
            RequirementChecklistPort.ItemView req,
            int recallTopK,
            int rerankTopN) {
        ReviewPort.ItemDraft draft = baseDraft(req, packageId);
        String requirementText = req.getText() == null ? "" : req.getText().trim();
        String mainType = resolveMainType(req);
        draft.setMainType(mainType);
        draft.setTitle(req.getTitle());
        draft.setSubType(req.getSubType());
        String requirementCode = req.getRequirementCode();
        if (requirementCode == null || requirementCode.isBlank()) {
            // 旧清单无编号时按主类+条目 id 兜底
            requirementCode = (ReviewPort.MAIN_SCORING.equals(mainType) ? "S" : "Q") + req.getId();
        }
        draft.setRequirementCode(requirementCode);
        draft.setFullScore(req.getScoreTotal());

        if (requirementText.isEmpty()) {
            return fillNoEvidence(draft, mainType, "清单条目文本为空，无法对照");
        }

        List<String> recallQueries = resolveRecallQueries(req);
        if (recallQueries.isEmpty()) {
            return fillNoEvidence(draft, mainType, "无 bidSearch，跳过投标召回");
        }

        List<Long> onlyThisPackage = new ArrayList<>();
        onlyThisPackage.add(packageId);

        long t0 = System.currentTimeMillis();
        List<VectorRecallPort.RecallHit> hits = recallMergeByQueries(
                orgId, projectId, onlyThisPackage, recallQueries, recallTopK);
        long recallMs = System.currentTimeMillis() - t0;
        log.info("审查召回 projectId={} packageId={} reqItemId={} code={} queryCount={} hitCount={} costMs={}",
                projectId, packageId, req.getId(), requirementCode, recallQueries.size(),
                hits == null ? 0 : hits.size(), recallMs);

        if (hits == null || hits.isEmpty()) {
            return fillNoEvidence(draft, mainType, null);
        }

        List<String> docs = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            String c = hits.get(i).getContent();
            docs.add(c == null ? "" : c);
        }
        t0 = System.currentTimeMillis();
        List<RerankPort.RerankHit> ranked =
                rerankPort.rerank(requirementText, docs, rerankTopN, bidInstruct);
        long rerankMs = System.currentTimeMillis() - t0;
        List<VectorRecallPort.RecallHit> top = new ArrayList<>();
        if (ranked != null) {
            for (int i = 0; i < ranked.size(); i++) {
                int idx = ranked.get(i).getIndex();
                if (idx < 0 || idx >= hits.size()) {
                    throw new IllegalStateException("重排 index 越界: " + idx);
                }
                top.add(hits.get(idx));
            }
        }
        log.info("审查重排 projectId={} packageId={} reqItemId={} code={} inDocs={} outTop={} costMs={}",
                projectId, packageId, req.getId(), requirementCode, docs.size(), top.size(), rerankMs);
        if (top.isEmpty()) {
            return fillNoEvidence(draft, mainType, "重排后无候选段落");
        }

        LlmPort.CompareRequest request = new LlmPort.CompareRequest();
        request.setSupplier(supplier);
        request.setRequirementId(requirementCode);
        request.setTitle(req.getTitle());
        request.setMainType(mainType);
        request.setContent(requirementText);
        request.setTenderQuote(req.getSourceExcerpt());
        request.setFullScore(req.getScoreTotal());
        request.setBidChunksText(formatChunks(top));

        t0 = System.currentTimeMillis();
        LlmPort.CompareResult compared = llmPort.compareRequirement(request);
        long llmMs = System.currentTimeMillis() - t0;
        applyCompareResult(draft, compared, top, mainType);
        log.info("审查对照完成 projectId={} packageId={} reqItemId={} code={} recallMs={} rerankMs={} llmMs={} totalMs={}",
                projectId, packageId, req.getId(), requirementCode,
                recallMs, rerankMs, llmMs, recallMs + rerankMs + llmMs);
        return draft;
    }

    private ReviewPort.ItemDraft baseDraft(RequirementChecklistPort.ItemView req, long packageId) {
        ReviewPort.ItemDraft draft = new ReviewPort.ItemDraft();
        draft.setRequirementItemId(req.getId());
        draft.setDimensionCode(req.getDimensionCode());
        draft.setRequirementText(req.getText());
        draft.setMust(req.isMust());
        draft.setPackageId(packageId);
        return draft;
    }

    private static String resolveMainType(RequirementChecklistPort.ItemView req) {
        if (req.getMainType() != null && !req.getMainType().isBlank()) {
            return req.getMainType().trim();
        }
        if (req.getDimensionCode() != null && ReviewPort.MAIN_SCORING.equals(req.getDimensionCode())) {
            return ReviewPort.MAIN_SCORING;
        }
        return ReviewPort.MAIN_QUALIFICATION;
    }

    /** 无召回/无证据时的产品规则落库。 */
    private ReviewPort.ItemDraft fillNoEvidence(ReviewPort.ItemDraft draft, String mainType, String reason) {
        draft.setMainType(mainType);
        draft.setVetoRisk(Boolean.FALSE);
        draft.setBidEvidenceJson("[]");
        draft.setBidExcerpt(null);
        draft.setPageNo(null);
        draft.setChunkId(null);
        draft.setDocumentId(null);
        draft.setFileName(null);
        draft.setLlmRawJson(null);
        if (ReviewPort.MAIN_SCORING.equals(mainType)) {
            draft.setStatus(null);
            draft.setReviewType("评分建议");
            if (draft.getFullScore() == null) {
                draft.setSuggestedScore(null);
                draft.setDeductionReason(null);
                draft.setJudgement(reason == null ? "暂无评分（条目无满分）" : reason);
            } else {
                draft.setSuggestedScore(BigDecimal.ZERO);
                draft.setJudgement("未检索到响应，按未响应计 0 分");
                draft.setDeductionReason("未检索到响应");
            }
            // verdict 列 NOT NULL；评分项用建议分映射兼容旧字段，API 仍以 suggestedScore 为准
            draft.setVerdict(verdictForScoring(draft.getSuggestedScore(), draft.getFullScore()));
            draft.setReason(draft.getJudgement());
        } else {
            draft.setStatus(ReviewPort.STATUS_UNKNOWN);
            draft.setReviewType("响应性审查");
            draft.setVerdict(ReviewPort.VERDICT_UNCLEAR);
            draft.setJudgement(reason == null ? "未检索到相关响应证据，无法判断。" : reason);
            draft.setReason(draft.getJudgement());
            draft.setSuggestedScore(null);
            draft.setDeductionReason(null);
        }
        return draft;
    }

    private void applyCompareResult(
            ReviewPort.ItemDraft draft,
            LlmPort.CompareResult compared,
            List<VectorRecallPort.RecallHit> top,
            String mainType) {
        draft.setLlmRawJson(compared.getRawContent());
        draft.setRequirementCode(compared.getRequirementId() == null
                ? draft.getRequirementCode() : compared.getRequirementId());
        draft.setTitle(compared.getTitle() == null ? draft.getTitle() : compared.getTitle());
        draft.setReviewType(compared.getReviewType());
        draft.setVetoRisk(compared.getVetoRisk() == null ? Boolean.FALSE : compared.getVetoRisk());
        draft.setNote(compared.getNote());
        draft.setJudgement(truncateJudgement(compared.getJudgement()));
        draft.setReason(draft.getJudgement());

        List<LlmPort.BidEvidence> safeEvidence = filterEvidence(compared.getBidEvidence(), top);
        draft.setBidEvidenceJson(toJsonEvidence(safeEvidence));
        if (!safeEvidence.isEmpty()) {
            LlmPort.BidEvidence first = safeEvidence.get(0);
            draft.setBidExcerpt(first.getQuote());
            draft.setPageNo(first.getPage());
            draft.setChunkId(first.getFragmentId());
            VectorRecallPort.RecallHit hit = findHitByChunkId(top, first.getFragmentId());
            if (hit != null) {
                draft.setDocumentId(hit.getDocumentId());
                draft.setFileName(hit.getFileName());
            }
        } else {
            draft.setBidExcerpt(null);
            draft.setPageNo(null);
            draft.setChunkId(null);
            draft.setDocumentId(null);
            draft.setFileName(null);
        }

        if (ReviewPort.MAIN_SCORING.equals(mainType)) {
            draft.setStatus(null);
            draft.setFullScore(compared.getFullScore() != null ? compared.getFullScore() : draft.getFullScore());
            if (draft.getFullScore() == null) {
                draft.setSuggestedScore(null);
                draft.setDeductionReason(null);
            } else {
                draft.setSuggestedScore(compared.getSuggestedScore());
                draft.setDeductionReason(compared.getDeductionReason());
                if (safeEvidence.isEmpty()
                        && (draft.getSuggestedScore() == null
                        || draft.getSuggestedScore().compareTo(BigDecimal.ZERO) == 0)) {
                    draft.setSuggestedScore(BigDecimal.ZERO);
                    if (draft.getJudgement() == null || draft.getJudgement().isBlank()) {
                        draft.setJudgement("未检索到响应，按未响应计 0 分");
                        draft.setReason(draft.getJudgement());
                    }
                }
            }
            // 库表 verdict NOT NULL；评分不对外用 verdict，按建议分填兼容值
            draft.setVerdict(verdictForScoring(draft.getSuggestedScore(), draft.getFullScore()));
            if (draft.getReviewType() == null) {
                draft.setReviewType("评分建议");
            }
        } else {
            String status = compared.getStatus();
            if (status == null || status.isBlank()) {
                status = safeEvidence.isEmpty() ? ReviewPort.STATUS_UNKNOWN : ReviewPort.STATUS_MISMATCH;
            }
            if (safeEvidence.isEmpty() && !ReviewPort.STATUS_UNKNOWN.equals(status)) {
                // 除无法判断外须有证据
                status = ReviewPort.STATUS_UNKNOWN;
            }
            draft.setStatus(status);
            draft.setVerdict(compared.getVerdict() == null
                    ? mapStatusToVerdict(status) : compared.getVerdict());
            draft.setSuggestedScore(null);
            draft.setDeductionReason(null);
            if (draft.getReviewType() == null) {
                draft.setReviewType(Boolean.TRUE.equals(draft.getVetoRisk()) ? "废标风险" : "响应性审查");
            }
        }
    }

    /**
     * 评分项没有符合/不符合；为满足 biz_review_item.verdict NOT NULL，按建议分映射。
     */
    private static String verdictForScoring(BigDecimal suggested, BigDecimal full) {
        if (full == null || suggested == null) {
            return ReviewPort.VERDICT_UNCLEAR;
        }
        if (suggested.compareTo(BigDecimal.ZERO) <= 0) {
            return ReviewPort.VERDICT_FAIL;
        }
        if (suggested.compareTo(full) >= 0) {
            return ReviewPort.VERDICT_PASS;
        }
        return ReviewPort.VERDICT_UNCLEAR;
    }

    private static String mapStatusToVerdict(String status) {
        if (ReviewPort.STATUS_MATCH.equals(status)) {
            return ReviewPort.VERDICT_PASS;
        }
        if (ReviewPort.STATUS_MISMATCH.equals(status)) {
            return ReviewPort.VERDICT_FAIL;
        }
        return ReviewPort.VERDICT_UNCLEAR;
    }

    private List<LlmPort.BidEvidence> filterEvidence(
            List<LlmPort.BidEvidence> raw,
            List<VectorRecallPort.RecallHit> top) {
        List<LlmPort.BidEvidence> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (int i = 0; i < raw.size(); i++) {
            LlmPort.BidEvidence ev = raw.get(i);
            if (ev == null || ev.getQuote() == null || ev.getQuote().isBlank()) {
                continue;
            }
            String quote = ev.getQuote().trim();
            if (isPlaceholderExcerpt(quote)) {
                continue;
            }
            VectorRecallPort.RecallHit matched = null;
            if (ev.getFragmentId() != null) {
                matched = findHitByChunkId(top, ev.getFragmentId());
            }
            if (matched == null) {
                matched = findHitContaining(top, quote);
            }
            if (matched == null) {
                continue;
            }
            String content = matched.getContent();
            if (content == null || !content.contains(quote)) {
                continue;
            }
            LlmPort.BidEvidence copy = new LlmPort.BidEvidence();
            copy.setFragmentId(matched.getChunkId());
            copy.setPage(ev.getPage() != null ? ev.getPage() : matched.getPageNo());
            copy.setQuote(quote);
            result.add(copy);
        }
        return result;
    }

    private static VectorRecallPort.RecallHit findHitByChunkId(
            List<VectorRecallPort.RecallHit> top, Long chunkId) {
        if (top == null || chunkId == null) {
            return null;
        }
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).getChunkId() == chunkId) {
                return top.get(i);
            }
        }
        return null;
    }

    private static VectorRecallPort.RecallHit findHitContaining(
            List<VectorRecallPort.RecallHit> top, String quote) {
        if (top == null) {
            return null;
        }
        for (int i = 0; i < top.size(); i++) {
            String c = top.get(i).getContent();
            if (c != null && c.contains(quote)) {
                return top.get(i);
            }
        }
        return null;
    }

    static boolean isPlaceholderExcerpt(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        return text.startsWith("无相关")
                || text.startsWith("未找到")
                || text.startsWith("找不到")
                || text.startsWith("没有相关")
                || text.startsWith("未检索到")
                || text.startsWith("未见");
    }

    private String toJsonEvidence(List<LlmPort.BidEvidence> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? new ArrayList<>() : list);
        } catch (Exception ex) {
            return "[]";
        }
    }

    /**
     * 仅用 bidSearch 作投标侧召回词；无 bidSearch 则返回空（不回退 text）。
     */
    List<String> resolveRecallQueries(RequirementChecklistPort.ItemView req) {
        return parseBidSearchJson(req == null ? null : req.getBidSearchJson());
    }

    List<String> parseBidSearchJson(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return result;
            }
            for (int i = 0; i < node.size(); i++) {
                JsonNode el = node.get(i);
                if (el == null || el.isNull()) {
                    continue;
                }
                String s = el.asText();
                if (s != null && !s.isBlank()) {
                    result.add(s.trim());
                }
            }
        } catch (Exception ex) {
            // 非法 JSON 视为无 bidSearch
        }
        return result;
    }

    private List<VectorRecallPort.RecallHit> recallMergeByQueries(
            long orgId,
            long projectId,
            List<Long> packageIds,
            List<String> queries,
            int topK) {
        Map<Long, VectorRecallPort.RecallHit> byChunk = new LinkedHashMap<>();
        for (int q = 0; q < queries.size(); q++) {
            String query = queries.get(q);
            List<String> texts = new ArrayList<>();
            texts.add(query);
            List<float[]> vectors = embeddingPort.embed(texts);
            if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
                throw new IllegalStateException("召回词向量化失败: " + query);
            }
            VectorRecallPort.RecallQuery rq = new VectorRecallPort.RecallQuery();
            rq.setOrgId(orgId);
            rq.setProjectId(projectId);
            rq.setQueryEmbedding(vectors.get(0));
            rq.setTopK(topK);
            rq.setPackageIds(packageIds);
            rq.setDocumentIds(null);
            List<VectorRecallPort.RecallHit> hits = vectorRecallPort.recall(rq);
            if (hits == null) {
                continue;
            }
            for (int i = 0; i < hits.size(); i++) {
                VectorRecallPort.RecallHit hit = hits.get(i);
                Long chunkId = hit.getChunkId();
                VectorRecallPort.RecallHit existing = byChunk.get(chunkId);
                if (existing == null || hit.getScore() > existing.getScore()) {
                    byChunk.put(chunkId, hit);
                }
            }
        }
        List<VectorRecallPort.RecallHit> merged = new ArrayList<>();
        for (Map.Entry<Long, VectorRecallPort.RecallHit> e : byChunk.entrySet()) {
            merged.add(e.getValue());
        }
        return merged;
    }

    /** judgement 产品上限 200 字。 */
    private static String truncateJudgement(String judgement) {
        if (judgement == null) {
            return null;
        }
        String t = judgement.trim();
        int count = t.codePointCount(0, t.length());
        if (count <= 200) {
            return t;
        }
        int end = t.offsetByCodePoints(0, 200);
        return t.substring(0, end);
    }

    private String formatChunks(List<VectorRecallPort.RecallHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            VectorRecallPort.RecallHit hit = hits.get(i);
            sb.append("—— 候选 ").append(i + 1).append(" ——\n");
            sb.append("chunkId=").append(hit.getChunkId());
            if (hit.getPageNo() != null) {
                sb.append(" pageNo=").append(hit.getPageNo());
            }
            sb.append(" documentId=").append(hit.getDocumentId());
            sb.append(" fileName=").append(hit.getFileName() == null ? "" : hit.getFileName());
            sb.append('\n');
            sb.append(hit.getContent() == null ? "" : hit.getContent());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** 管线结果。 */
    public static class PipelineResult {
        private List<ReviewPort.ItemDraft> items = new ArrayList<>();
        private String overallVerdict;

        public List<ReviewPort.ItemDraft> getItems() {
            return items;
        }

        public void setItems(List<ReviewPort.ItemDraft> items) {
            if (items == null) {
                this.items = new ArrayList<>();
            } else {
                this.items = items;
            }
        }

        public String getOverallVerdict() {
            return overallVerdict;
        }

        public void setOverallVerdict(String overallVerdict) {
            this.overallVerdict = overallVerdict;
        }
    }

    /** 进度回调。 */
    public interface ProgressCallback {
        void onProgress(int progress, String message);
    }
}
