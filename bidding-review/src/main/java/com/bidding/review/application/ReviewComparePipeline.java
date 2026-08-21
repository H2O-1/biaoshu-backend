package com.bidding.review.application;

import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RerankPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.port.VectorRecallPort;
import com.bidding.review.infrastructure.recall.Bm25LexicalRecall;
import com.bidding.review.infrastructure.recall.RrfFusion;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审查管线：按投标包全量对照（包 × 清单条目）。
 * 召回仅用 bidSearch（无则不召回）；向量 ∥ BM25（RRF）后再重排；产品向 LLM（review-v2）落库。
 * 条目对照默认最多 4 路并行（含召回/重排/LLM）。
 */
@Service
public class ReviewComparePipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewComparePipeline.class);

    private final EmbeddingPort embeddingPort;
    private final VectorRecallPort vectorRecallPort;
    private final Bm25LexicalRecall bm25LexicalRecall;
    private final RerankPort rerankPort;
    private final LlmPort llmPort;
    private final PackageLookupPort packageLookupPort;
    private final ObjectMapper objectMapper;
    private final int defaultRecallTopK;
    private final int bidRerankTopN;
    private final String bidInstruct;
    private final boolean hybridEnabled;
    private final int bm25TopK;
    private final int rrfK;
    /** 条目并行度，硬上限 4。 */
    private final int compareParallelism;

    public ReviewComparePipeline(
            EmbeddingPort embeddingPort,
            VectorRecallPort vectorRecallPort,
            Bm25LexicalRecall bm25LexicalRecall,
            RerankPort rerankPort,
            LlmPort llmPort,
            PackageLookupPort packageLookupPort,
            ObjectMapper objectMapper,
            @Value("${bidding.review.recall-top-k:30}") int defaultRecallTopK,
            @Value("${bidding.review.rerank-top-n:${bidding.rerank.bid-top-n:15}}") int bidRerankTopN,
            @Value("${bidding.rerank.bid-instruct:}")
            String bidInstruct,
            @Value("${bidding.recall.hybrid-enabled:true}") boolean hybridEnabled,
            @Value("${bidding.recall.bm25-top-k:0}") int bm25TopK,
            @Value("${bidding.recall.rrf-k:60}") int rrfK,
            @Value("${bidding.review.compare-parallelism:4}") int compareParallelism) {
        this.embeddingPort = embeddingPort;
        this.vectorRecallPort = vectorRecallPort;
        this.bm25LexicalRecall = bm25LexicalRecall;
        this.rerankPort = rerankPort;
        this.llmPort = llmPort;
        this.packageLookupPort = packageLookupPort;
        this.objectMapper = objectMapper;
        this.defaultRecallTopK = defaultRecallTopK < 1 ? 30 : defaultRecallTopK;
        this.bidRerankTopN = bidRerankTopN < 1 ? 6 : bidRerankTopN;
        this.bidInstruct = bidInstruct;
        this.hybridEnabled = hybridEnabled;
        this.bm25TopK = bm25TopK;
        this.rrfK = rrfK < 1 ? 60 : rrfK;
        this.compareParallelism = compareParallelism < 1 ? 1 : Math.min(4, compareParallelism);
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

        int reqCount = requirements == null ? 0 : requirements.size();
        int totalSteps = Math.max(reqCount * pkgCount, 1);
        long tAll = System.currentTimeMillis();

        List<CompareTask> tasks = new ArrayList<>();
        for (int p = 0; p < pkgCount; p++) {
            long packageId = bidPackageIds.get(p);
            String supplier = supplierByPackage.get(packageId);
            for (int i = 0; i < reqCount; i++) {
                CompareTask task = new CompareTask();
                task.sortIndex = tasks.size();
                task.packageId = packageId;
                task.supplier = supplier;
                task.req = requirements.get(i);
                tasks.add(task);
            }
        }

        ReviewPort.ItemDraft[] slots = new ReviewPort.ItemDraft[tasks.size()];
        if (!tasks.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(compareParallelism);
            AtomicInteger done = new AtomicInteger(0);
            Object progressLock = new Object();
            List<CompletableFuture<Void>> futures = new ArrayList<>(tasks.size());
            try {
                for (int t = 0; t < tasks.size(); t++) {
                    CompareTask task = tasks.get(t);
                    futures.add(CompletableFuture.runAsync(() -> {
                        ReviewPort.ItemDraft draft = compareOne(
                                orgId, projectId, task.packageId, task.supplier, task.req,
                                safeRecallTopK, safeRerankTopN);
                        draft.setSortOrder(task.sortIndex);
                        slots[task.sortIndex] = draft;
                        int finished = done.incrementAndGet();
                        if (progress != null) {
                            int pct = 10 + (int) ((75.0 * finished) / totalSteps);
                            synchronized (progressLock) {
                                progress.onProgress(pct, "AI审查中");
                            }
                        }
                    }, pool));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new IllegalStateException("审查并行对照失败: " + cause.getMessage(), cause);
            } finally {
                pool.shutdownNow();
            }
        }

        List<ReviewPort.ItemDraft> drafts = new ArrayList<>(slots.length);
        for (int i = 0; i < slots.length; i++) {
            drafts.add(slots[i]);
        }

        if (progress != null) {
            progress.onProgress(90, "AI审查中");
        }
        log.info("审查管线完成 projectId={} packages={} requirements={} items={} "
                        + "parallelism={} totalMs={}",
                projectId, pkgCount, reqCount, drafts.size(),
                compareParallelism, System.currentTimeMillis() - tAll);
        PipelineResult result = new PipelineResult();
        result.setItems(drafts);
        result.setOverallVerdict(null);
        return result;
    }

    /**
     * 投标模拟审核入口：不依赖项目和清单落库，直接用本次抽取出的招标侧要求草稿审查一个内部投标包。
     */
    public PipelineResult runSimulation(
            long orgId,
            List<RequirementChecklistPort.ItemDraft> requirements,
            long bidPackageId,
            int recallTopK,
            int rerankTopN,
            ProgressCallback progress) {
        List<RequirementChecklistPort.ItemView> views = toItemViews(requirements);
        List<Long> packageIds = new ArrayList<>();
        packageIds.add(bidPackageId);
        return run(orgId, 0L, views, packageIds, recallTopK, rerankTopN, progress);
    }

    private List<RequirementChecklistPort.ItemView> toItemViews(
            List<RequirementChecklistPort.ItemDraft> drafts) {
        List<RequirementChecklistPort.ItemView> views = new ArrayList<>();
        if (drafts == null) {
            return views;
        }
        for (int i = 0; i < drafts.size(); i++) {
            RequirementChecklistPort.ItemDraft draft = drafts.get(i);
            if (draft == null) {
                continue;
            }
            RequirementChecklistPort.ItemView view = new RequirementChecklistPort.ItemView();
            view.setId(i + 1L);
            view.setChecklistId(0L);
            view.setDimensionCode(draft.getDimensionCode());
            view.setText(draft.getText());
            view.setSourceExcerpt(draft.getSourceExcerpt());
            view.setPageNo(draft.getPageNo());
            view.setChunkIdsJson(draft.getChunkIdsJson());
            view.setMust(draft.isMust());
            view.setSortOrder(draft.getSortOrder());
            view.setTitle(draft.getTitle());
            view.setMainType(draft.getMainType());
            view.setSubType(draft.getSubType());
            view.setBidSearchJson(draft.getBidSearchJson());
            view.setEvidenceJson(draft.getEvidenceJson());
            view.setScoreTotal(draft.getScoreTotal());
            view.setScorePartsJson(draft.getScorePartsJson());
            view.setScoreSumMatches(draft.getScoreSumMatches());
            view.setRequirementCode(draft.getRequirementCode());
            views.add(view);
        }
        return views;
    }

    private static final class CompareTask {
        private int sortIndex;
        private long packageId;
        private String supplier;
        private RequirementChecklistPort.ItemView req;
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
            log.info("审查无bidSearch projectId={} packageId={} reqItemId={} code={} bidSearchJson={}",
                    projectId, packageId, req.getId(), requirementCode, req.getBidSearchJson());
            return fillNoEvidence(draft, mainType, "无 bidSearch，跳过投标召回");
        }

        List<Long> onlyThisPackage = new ArrayList<>();
        onlyThisPackage.add(packageId);

        long t0 = System.currentTimeMillis();
        BidRecallResult recalled = recallMergeByQueries(
                orgId, projectId, onlyThisPackage, recallQueries, recallTopK);
        long recallMs = System.currentTimeMillis() - t0;
        List<VectorRecallPort.RecallHit> hits = recalled.hits;
        log.info("审查bidSearch召回 projectId={} packageId={} reqItemId={} code={} "
                        + "bidSearch={} perQueryHits={} mergedHits={} recallTopK={} recallMs={}",
                projectId, packageId, req.getId(), requirementCode,
                recallQueries, recalled.perQueryHits, hits.size(), recallTopK, recallMs);

        if (hits.isEmpty()) {
            log.info("审查无召回 projectId={} packageId={} reqItemId={} code={} bidSearch={} recallMs={}",
                    projectId, packageId, req.getId(), requirementCode, recallQueries, recallMs);
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
        if (top.isEmpty()) {
            log.info("审查重排为空 projectId={} packageId={} reqItemId={} code={} "
                            + "bidSearch={} hitCount={} recallMs={} rerankMs={}",
                    projectId, packageId, req.getId(), requirementCode,
                    recallQueries, hits.size(), recallMs, rerankMs);
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
        log.info("审查对照完成 projectId={} packageId={} reqItemId={} code={} "
                        + "bidSearch={} perQueryHits={} hitCount={} topN={} "
                        + "recallMs={} rerankMs={} llmMs={} totalMs={}",
                projectId, packageId, req.getId(), requirementCode,
                recallQueries, recalled.perQueryHits, hits.size(), top.size(),
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
                // 除无法判断外须有可核对原文证据；强制改状态时同步结论文，避免标签与 judgement 打架
                String llmStatus = status;
                status = ReviewPort.STATUS_UNKNOWN;
                int rawCount = compared.getBidEvidence() == null ? 0 : compared.getBidEvidence().size();
                log.warn("资格项证据未通过原文校验，强制无法判断 requirementCode={} llmStatus={} "
                                + "rawEvidenceCount={} recallHits={}",
                        draft.getRequirementCode(), llmStatus, rawCount, top == null ? 0 : top.size());
                draft.setJudgement(truncateJudgement(
                        "模型倾向「" + llmStatus + "」，但未能绑定可核对的投标原文证据，故判定为无法判断。"));
                draft.setReason(draft.getJudgement());
            }
            draft.setStatus(status);
            // verdict 以最终 status 为准，不沿用模型可能过时的 PASS/FAIL
            draft.setVerdict(mapStatusToVerdict(status));
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
                log.debug("投标证据未匹配召回片段 fragmentId={} quoteLen={}",
                        ev.getFragmentId(), quote.length());
                continue;
            }
            String content = matched.getContent();
            if (!quoteInContent(content, quote)) {
                log.debug("投标证据 quote 非召回块原文子串 chunkId={} quoteLen={}",
                        matched.getChunkId(), quote.length());
                continue;
            }
            // 落库尽量用块内原文子串（空白归一匹配时回填真实片段）
            String canonical = canonicalQuoteFromContent(content, quote);
            LlmPort.BidEvidence copy = new LlmPort.BidEvidence();
            copy.setFragmentId(matched.getChunkId());
            copy.setPage(matched.getPageNo());
            copy.setQuote(canonical);
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
            if (quoteInContent(top.get(i).getContent(), quote)) {
                return top.get(i);
            }
        }
        return null;
    }

    /**
     * quote 须能在召回块中找到：先精确 contains，再空白归一后 contains。
     * 仍要求是原文（禁止语义改写），仅容忍空白/换行差异。
     */
    static boolean quoteInContent(String content, String quote) {
        if (content == null || quote == null || quote.isEmpty()) {
            return false;
        }
        if (content.contains(quote)) {
            return true;
        }
        String normalizedContent = normalizeWhitespace(content);
        String normalizedQuote = normalizeWhitespace(quote);
        return !normalizedQuote.isEmpty() && normalizedContent.contains(normalizedQuote);
    }

    /**
     * 若精确命中则返回 quote；若仅空白归一命中，尽量回填 content 中对应原文片段。
     */
    static String canonicalQuoteFromContent(String content, String quote) {
        if (content == null || quote == null) {
            return quote;
        }
        if (content.contains(quote)) {
            return quote;
        }
        String normalizedContent = normalizeWhitespace(content);
        String normalizedQuote = normalizeWhitespace(quote);
        int idx = normalizedContent.indexOf(normalizedQuote);
        if (idx < 0) {
            return quote;
        }
        // 按「非空白字符序号」映射回原文下标，取覆盖该段的原文切片
        int start = mapNormalizedIndexToOriginal(content, idx);
        int end = mapNormalizedIndexToOriginal(content, idx + normalizedQuote.length());
        if (start < 0 || end < start || end > content.length()) {
            return quote;
        }
        return content.substring(start, end).trim();
    }

    static String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", "");
    }

    /**
     * 将「去掉空白后的下标」映射回原文字符下标（指向第 normalizedIndex 个非空白字符；
     * 若 normalizedIndex == 非空白总数，则返回原文 length）。
     */
    static int mapNormalizedIndexToOriginal(String original, int normalizedIndex) {
        if (original == null || normalizedIndex < 0) {
            return -1;
        }
        int seen = 0;
        for (int i = 0; i < original.length(); i++) {
            if (Character.isWhitespace(original.charAt(i))) {
                continue;
            }
            if (seen == normalizedIndex) {
                return i;
            }
            seen++;
        }
        if (seen == normalizedIndex) {
            return original.length();
        }
        return -1;
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

    private BidRecallResult recallMergeByQueries(
            long orgId,
            long projectId,
            List<Long> packageIds,
            List<String> queries,
            int topK) {
        Map<Long, VectorRecallPort.RecallHit> byChunk = new LinkedHashMap<>();
        List<Integer> perQueryHits = new ArrayList<>();
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
            List<VectorRecallPort.RecallHit> vectorHits = vectorRecallPort.recall(rq);
            List<VectorRecallPort.RecallHit> hits = vectorHits;
            if (hybridEnabled && bm25LexicalRecall != null) {
                int lexicalTopK = bm25TopK < 1 ? topK : bm25TopK;
                List<VectorRecallPort.RecallHit> bm25Hits = bm25LexicalRecall.search(
                        orgId, projectId, query, packageIds, null, lexicalTopK);
                hits = RrfFusion.fuse(vectorHits, bm25Hits, rrfK, topK);
            }
            int hitCount = hits == null ? 0 : hits.size();
            perQueryHits.add(hitCount);
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
        BidRecallResult result = new BidRecallResult();
        result.hits = merged;
        result.perQueryHits = perQueryHits;
        return result;
    }

    private static final class BidRecallResult {
        private List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
        private List<Integer> perQueryHits = new ArrayList<>();
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
