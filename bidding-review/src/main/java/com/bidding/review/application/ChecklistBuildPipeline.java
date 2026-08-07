package com.bidding.review.application;

import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RerankPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.port.VectorRecallPort;
import com.bidding.review.infrastructure.recall.Bm25LexicalRecall;
import com.bidding.review.infrastructure.recall.RrfFusion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 建清单管线：按管理员维度多 query 召回并合并 → 综合重排一次 →
 * 按产物拆分：资格条目 A ∥ 评分条目 B → 编号 → bidSearch C ∥ evidence D → 合并落库草稿。
 * 召回：向量余弦 ∥ BM25，RRF 融合后再重排（可用 hybrid-enabled 关闭 BM25）。
 */
@Service
public class ChecklistBuildPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChecklistBuildPipeline.class);

    /** 主类：资格要求 */
    public static final String MAIN_QUALIFICATION = "QUALIFICATION";
    /** 主类：评分项 */
    public static final String MAIN_SCORING = "SCORING";

    private final EmbeddingPort embeddingPort;
    private final VectorRecallPort vectorRecallPort;
    private final Bm25LexicalRecall bm25LexicalRecall;
    private final RerankPort rerankPort;
    private final LlmPort llmPort;
    private final ObjectMapper objectMapper;
    private final int defaultRecallTopK;
    private final int tenderRerankTopN;
    private final String tenderInstruct;
    private final boolean hybridEnabled;
    private final int bm25TopK;
    private final int rrfK;
    /** C 步每批条目数（默认 10）。 */
    private final int bidSearchBatchSize;

    public ChecklistBuildPipeline(
            EmbeddingPort embeddingPort,
            VectorRecallPort vectorRecallPort,
            Bm25LexicalRecall bm25LexicalRecall,
            RerankPort rerankPort,
            LlmPort llmPort,
            ObjectMapper objectMapper,
            @Value("${bidding.checklist.recall-top-k:${bidding.recall.default-top-k:40}}") int defaultRecallTopK,
            @Value("${bidding.checklist.rerank-top-n:${bidding.rerank.tender-top-n:15}}") int tenderRerankTopN,
            @Value("${bidding.rerank.tender-instruct:}")
            String tenderInstruct,
            @Value("${bidding.recall.hybrid-enabled:true}") boolean hybridEnabled,
            @Value("${bidding.recall.bm25-top-k:0}") int bm25TopK,
            @Value("${bidding.recall.rrf-k:60}") int rrfK,
            @Value("${bidding.checklist.bid-search-batch-size:10}") int bidSearchBatchSize) {
        this.embeddingPort = embeddingPort;
        this.vectorRecallPort = vectorRecallPort;
        this.bm25LexicalRecall = bm25LexicalRecall;
        this.rerankPort = rerankPort;
        this.llmPort = llmPort;
        this.objectMapper = objectMapper;
        this.defaultRecallTopK = defaultRecallTopK < 1 ? 40 : defaultRecallTopK;
        this.tenderRerankTopN = tenderRerankTopN < 1 ? 8 : tenderRerankTopN;
        this.tenderInstruct = tenderInstruct;
        this.hybridEnabled = hybridEnabled;
        this.bm25TopK = bm25TopK;
        this.rrfK = rrfK < 1 ? 60 : rrfK;
        this.bidSearchBatchSize = bidSearchBatchSize < 1 ? 10 : bidSearchBatchSize;
    }

    /**
     * 对勾选维度做多路召回后归纳，返回可落库条目与 LLM 原文。
     * A∥B 任一侧或 C 失败则抛异常，由执行器将整单标 FAILED。
     */
    public PipelineResult build(
            long orgId,
            long projectId,
            List<ReviewDimensionPort.Dimension> dimensions,
            List<Long> tenderPackageIds,
            List<Long> documentIds,
            int recallTopK,
            int rerankTopN,
            ProgressCallback progress) {
        int safeRecallTopK = recallTopK < 1 ? defaultRecallTopK : recallTopK;
        int safeRerankTopN = rerankTopN < 1 ? tenderRerankTopN : rerankTopN;
        long tAll = System.currentTimeMillis();

        if (progress != null) {
            progress.onProgress(15, "多维召回中");
        }
        long t0 = System.currentTimeMillis();
        List<VectorRecallPort.RecallHit> merged = recallAllDimensions(
                orgId, projectId, dimensions, tenderPackageIds, documentIds, safeRecallTopK);
        long recallMs = System.currentTimeMillis() - t0;
        if (merged.isEmpty()) {
            log.info("建清单无召回 projectId={} dimCount={} recallMs={} totalMs={}",
                    projectId, dimensions == null ? 0 : dimensions.size(),
                    recallMs, System.currentTimeMillis() - tAll);
            return emptyResult();
        }

        if (progress != null) {
            progress.onProgress(45, "重排中");
        }
        String rerankQuery = buildAllDimensionsRerankQuery(dimensions);
        t0 = System.currentTimeMillis();
        List<VectorRecallPort.RecallHit> ranked = rerankHits(rerankQuery, merged, safeRerankTopN);
        long rerankMs = System.currentTimeMillis() - t0;
        if (ranked.isEmpty()) {
            log.warn("建清单重排结果为空 projectId={} recallMs={} rerankMs={}",
                    projectId, recallMs, rerankMs);
            return emptyResult();
        }

        if (progress != null) {
            progress.onProgress(60, "LLM拆分归纳中(资格∥评分)");
        }
        String chunksText = formatChunks(ranked);
        t0 = System.currentTimeMillis();
        LlmExtractBundle bundle = extractWithLlm(chunksText);
        long llmMs = System.currentTimeMillis() - t0;
        List<LlmPort.ExtractedRequirement> extracted = bundle.items;
        String llmRaw = bundle.rawJson;

        Map<Long, Boolean> allowedChunk = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            allowedChunk.put(ranked.get(i).getChunkId(), Boolean.TRUE);
        }

        List<RequirementChecklistPort.ItemDraft> all = new ArrayList<>();
        if (extracted != null) {
            for (int i = 0; i < extracted.size(); i++) {
                LlmPort.ExtractedRequirement er = extracted.get(i);
                RequirementChecklistPort.ItemDraft draft = toDraft(er, allowedChunk, all.size());
                if (draft != null) {
                    all.add(draft);
                }
            }
        }

        List<RequirementChecklistPort.ItemDraft> deduped = dedupeByNormalizedText(all);
        assignRequirementCodes(deduped);
        for (int i = 0; i < deduped.size(); i++) {
            deduped.get(i).setSortOrder(i);
        }

        long bidSearchMs = 0L;
        long evidenceMs = 0L;
        long cdWallMs = 0L;
        if (!deduped.isEmpty()) {
            if (progress != null) {
                progress.onProgress(75, "生成bidSearch∥evidence中 count=" + deduped.size());
            }
            long[] bidMsHolder = new long[1];
            long[] evMsHolder = new long[1];
            long tCd = System.currentTimeMillis();
            ExecutorService cdPool = Executors.newFixedThreadPool(2);
            try {
                // C 与 D 互不依赖：编号后可并行，墙钟 ≈ max(C, D)
                final List<RequirementChecklistPort.ItemDraft> itemsForCd = deduped;
                final List<VectorRecallPort.RecallHit> rankedForCd = ranked;
                CompletableFuture<String> bidFuture = CompletableFuture.supplyAsync(() -> {
                    long t1 = System.currentTimeMillis();
                    try {
                        return attachBidSearch(itemsForCd);
                    } finally {
                        bidMsHolder[0] = System.currentTimeMillis() - t1;
                    }
                }, cdPool);
                CompletableFuture<String> evFuture = CompletableFuture.supplyAsync(() -> {
                    long t1 = System.currentTimeMillis();
                    try {
                        return attachEvidence(itemsForCd, rankedForCd);
                    } finally {
                        evMsHolder[0] = System.currentTimeMillis() - t1;
                    }
                }, cdPool);
                CompletableFuture.allOf(bidFuture, evFuture).join();
                String bidSearchRaw = bidFuture.join();
                String evidenceRaw = evFuture.join();
                llmRaw = appendLlmPartRaw(llmRaw, "bidSearch", bidSearchRaw);
                llmRaw = appendLlmPartRaw(llmRaw, "evidence", evidenceRaw);
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                log.warn("建清单C∥D失败 bidSearchMs={} evidenceMs={} wallMs={}",
                        bidMsHolder[0], evMsHolder[0], System.currentTimeMillis() - tCd);
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new IllegalStateException("建清单 bidSearch/evidence 失败: " + cause.getMessage(), cause);
            } finally {
                cdPool.shutdownNow();
            }
            bidSearchMs = bidMsHolder[0];
            evidenceMs = evMsHolder[0];
            cdWallMs = System.currentTimeMillis() - tCd;
            log.info("建清单C∥D完成 bidSearchMs={} evidenceMs={} wallMs={}",
                    bidSearchMs, evidenceMs, cdWallMs);
        }

        log.info("建清单完成 projectId={} recallHits={} rerankTop={} extracted={} "
                        + "recallMs={} rerankMs={} llmMs={} bidSearchMs={} evidenceMs={} cdWallMs={} totalMs={}",
                projectId, merged.size(), ranked.size(),
                deduped.size(),
                recallMs, rerankMs, llmMs, bidSearchMs, evidenceMs, cdWallMs,
                System.currentTimeMillis() - tAll);

        if (progress != null) {
            progress.onProgress(85, "条目去重完成 count=" + deduped.size());
        }
        PipelineResult result = new PipelineResult();
        result.setItems(deduped);
        result.setLlmRawJson(llmRaw);
        return result;
    }

    /**
     * 调试：只执行建清单阶段的多维召回，使用与正式建清单一致的召回词收集、
     * 混合召回和合并排序逻辑。
     */
    public RecallDebugResult debugRecall(
            long orgId,
            long projectId,
            List<ReviewDimensionPort.Dimension> dimensions,
            List<Long> tenderPackageIds,
            List<Long> documentIds,
            int topK) {
        int safeTopK = topK < 1 ? defaultRecallTopK : topK;
        List<String> queryTexts = collectAllQueryTexts(dimensions);
        List<VectorRecallPort.RecallHit> hits = recallAllDimensions(
                orgId, projectId, dimensions, tenderPackageIds, documentIds, safeTopK);

        RecallDebugResult result = new RecallDebugResult();
        result.setTopK(safeTopK);
        result.setQueryTexts(queryTexts);
        result.setHits(toDebugHits(hits));
        return result;
    }

    /**
     * 调试：执行建清单阶段的多维召回和综合重排，返回正式喂给建清单 LLM 前的 topN 片段。
     */
    public RerankDebugResult debugRerank(
            long orgId,
            long projectId,
            List<ReviewDimensionPort.Dimension> dimensions,
            List<Long> tenderPackageIds,
            List<Long> documentIds,
            int recallTopK,
            int rerankTopN) {
        int safeRecallTopK = recallTopK < 1 ? defaultRecallTopK : recallTopK;
        int safeRerankTopN = rerankTopN < 1 ? tenderRerankTopN : rerankTopN;
        List<String> queryTexts = collectAllQueryTexts(dimensions);
        List<VectorRecallPort.RecallHit> recalled = recallAllDimensions(
                orgId, projectId, dimensions, tenderPackageIds, documentIds, safeRecallTopK);
        String rerankQuery = buildAllDimensionsRerankQuery(dimensions);

        List<RerankHitView> hits = new ArrayList<>();
        if (!recalled.isEmpty()) {
            List<String> docs = new ArrayList<>();
            for (int i = 0; i < recalled.size(); i++) {
                String content = recalled.get(i).getContent();
                docs.add(content == null ? "" : content);
            }
            List<RerankPort.RerankHit> ranked =
                    rerankPort.rerank(rerankQuery, docs, safeRerankTopN, tenderInstruct);
            hits = toRerankDebugHits(recalled, ranked);
        }

        RerankDebugResult result = new RerankDebugResult();
        result.setRecallTopK(safeRecallTopK);
        result.setRerankTopN(safeRerankTopN);
        result.setQueryTexts(queryTexts);
        result.setRerankQuery(rerankQuery);
        result.setRecallHitCount(recalled.size());
        result.setHits(hits);
        return result;
    }

    /**
     * 资格∥评分并行；任一侧失败则整单失败（抛出异常）。
     */
    private LlmExtractBundle extractWithLlm(String chunksText) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long[] qualMs = new long[1];
        long[] scoreMs = new long[1];
        long tWall = System.currentTimeMillis();
        try {
            CompletableFuture<LlmPort.ExtractChecklistResult> qualFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    return llmPort.extractQualificationChecklist(chunksText);
                } finally {
                    qualMs[0] = System.currentTimeMillis() - t0;
                }
            }, pool);
            CompletableFuture<LlmPort.ExtractChecklistResult> scoreFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    return llmPort.extractScoringChecklist(chunksText);
                } finally {
                    scoreMs[0] = System.currentTimeMillis() - t0;
                }
            }, pool);
            CompletableFuture.allOf(qualFuture, scoreFuture).join();
            LlmPort.ExtractChecklistResult qual = qualFuture.join();
            LlmPort.ExtractChecklistResult score = scoreFuture.join();
            int qualCount = qual == null || qual.getItems() == null ? 0 : qual.getItems().size();
            int scoreCount = score == null || score.getItems() == null ? 0 : score.getItems().size();
            log.info("建清单拆分LLM完成 qualLlmMs={} scoreLlmMs={} wallMs={} qualItems={} scoreItems={}",
                    qualMs[0], scoreMs[0], System.currentTimeMillis() - tWall, qualCount, scoreCount);
            return mergeSplitResults(qual, score);
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("建清单拆分LLM失败 qualLlmMs={} scoreLlmMs={} wallMs={}",
                    qualMs[0], scoreMs[0], System.currentTimeMillis() - tWall);
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("建清单拆分抽取失败: " + cause.getMessage(), cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 合并资格/评分两侧结果；raw 落库为两侧原文的简易拼接，便于排查。
     */
    static LlmExtractBundle mergeSplitResults(LlmPort.ExtractChecklistResult qual,
                                              LlmPort.ExtractChecklistResult score) {
        LlmExtractBundle bundle = new LlmExtractBundle();
        List<LlmPort.ExtractedRequirement> items = new ArrayList<>();
        if (qual != null && qual.getItems() != null) {
            for (int i = 0; i < qual.getItems().size(); i++) {
                LlmPort.ExtractedRequirement er = qual.getItems().get(i);
                if (er == null) {
                    continue;
                }
                er.setMainType(MAIN_QUALIFICATION);
                items.add(er);
            }
        }
        if (score != null && score.getItems() != null) {
            for (int i = 0; i < score.getItems().size(); i++) {
                LlmPort.ExtractedRequirement er = score.getItems().get(i);
                if (er == null) {
                    continue;
                }
                er.setMainType(MAIN_SCORING);
                items.add(er);
            }
        }
        bundle.items = items;
        bundle.rawJson = buildSplitRawJson(
                qual == null ? null : qual.getRawContent(),
                score == null ? null : score.getRawContent());
        return bundle;
    }

    static String buildSplitRawJson(String qualRaw, String scoreRaw) {
        String q = qualRaw == null ? "null" : qualRaw.trim();
        String s = scoreRaw == null ? "null" : scoreRaw.trim();
        // 两侧已是 JSON 对象时拼成联合对象，便于人工查看；非法则原样拼接
        if ((q.startsWith("{") || "null".equals(q)) && (s.startsWith("{") || "null".equals(s))) {
            return "{\"qualification\":" + ("null".equals(q) ? "null" : q)
                    + ",\"scoring\":" + ("null".equals(s) ? "null" : s) + "}";
        }
        return "{\"qualificationRaw\":" + jsonStringLiteral(q)
                + ",\"scoringRaw\":" + jsonStringLiteral(s) + "}";
    }

    private static String jsonStringLiteral(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 内部：合并后的抽取条目 + 原文。 */
    static class LlmExtractBundle {
        List<LlmPort.ExtractedRequirement> items = new ArrayList<>();
        String rawJson;
    }

    /**
     * 按主类分别编号：资格 Q1..、评分 S1..（按当前顺序出现次序）。
     */
    static void assignRequirementCodes(List<RequirementChecklistPort.ItemDraft> items) {
        if (items == null) {
            return;
        }
        int q = 0;
        int s = 0;
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            if (MAIN_SCORING.equals(item.getMainType())) {
                s++;
                item.setRequirementCode("S" + s);
            } else {
                q++;
                item.setRequirementCode("Q" + q);
            }
        }
    }

    private static PipelineResult emptyResult() {
        PipelineResult result = new PipelineResult();
        result.setItems(new ArrayList<>());
        result.setLlmRawJson(null);
        return result;
    }

    private RequirementChecklistPort.ItemDraft toDraft(LlmPort.ExtractedRequirement er,
                                                       Map<Long, Boolean> allowedChunk,
                                                       int sortOrder) {
        if (er.getText() == null || er.getText().trim().isEmpty()) {
            return null;
        }
        String mainType = er.getMainType();
        if (mainType == null || mainType.isBlank()) {
            mainType = MAIN_QUALIFICATION;
        }
        if (!MAIN_QUALIFICATION.equals(mainType) && !MAIN_SCORING.equals(mainType)) {
            mainType = MAIN_QUALIFICATION;
        }

        List<Long> safeChunkIds = new ArrayList<>();
        List<Long> rawIds = er.getChunkIds();
        if (rawIds != null) {
            for (int i = 0; i < rawIds.size(); i++) {
                Long id = rawIds.get(i);
                if (id != null && allowedChunk.containsKey(id) && !safeChunkIds.contains(id)) {
                    safeChunkIds.add(id);
                }
            }
        }

        RequirementChecklistPort.ItemDraft draft = new RequirementChecklistPort.ItemDraft();
        draft.setMainType(mainType);
        draft.setSubType(er.getSubType());
        draft.setTitle(er.getTitle());
        draft.setText(er.getText().trim());
        // dimension_code 与主类对齐（QUALIFICATION / SCORING）
        draft.setDimensionCode(mainType);
        draft.setMust(MAIN_QUALIFICATION.equals(mainType));
        draft.setSourceExcerpt(er.getSourceExcerpt());
        draft.setPageNo(er.getPageNo());
        draft.setChunkIdsJson(toJsonLongArray(safeChunkIds));
        // A/B 不产出 bidSearch / evidence；由 C/D 挂接
        draft.setBidSearchJson("[]");
        draft.setEvidenceJson("[]");
        draft.setScoreTotal(er.getScoreTotal());
        draft.setScorePartsJson(er.getScorePartsJson());
        draft.setScoreSumMatches(er.getScoreSumMatches());
        draft.setSortOrder(sortOrder);
        return draft;
    }

    /**
     * 产物 C：按最终 Q/S 编号分批生成 bidSearch 并写回草稿。
     *
     * @return 各批 raw JSON 拼接，便于落库排查
     */
    private String attachBidSearch(List<RequirementChecklistPort.ItemDraft> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        Map<String, List<String>> byCode = new LinkedHashMap<>();
        StringBuilder rawParts = new StringBuilder();
        int batch = bidSearchBatchSize;
        int batches = 0;
        for (int from = 0; from < items.size(); from += batch) {
            int to = Math.min(from + batch, items.size());
            List<RequirementChecklistPort.ItemDraft> slice = items.subList(from, to);
            String inputText = formatChecklistItemsForBidSearch(slice);
            LlmPort.BidSearchResult result = llmPort.generateBidSearch(inputText);
            batches++;
            if (result != null && result.getRawContent() != null) {
                if (rawParts.length() > 0) {
                    rawParts.append(',');
                }
                rawParts.append(result.getRawContent().trim());
            }
            List<LlmPort.BidSearchRow> rows = result == null ? null : result.getRows();
            if (rows == null) {
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                LlmPort.BidSearchRow row = rows.get(i);
                if (row == null || row.getId() == null || row.getId().isBlank()) {
                    continue;
                }
                byCode.put(row.getId().trim(), row.getQueries() == null
                        ? new ArrayList<>() : new ArrayList<>(row.getQueries()));
            }
        }
        int matched = 0;
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            String code = item.getRequirementCode();
            List<String> queries = code == null ? null : byCode.get(code);
            if (queries == null) {
                log.warn("建清单bidSearch未返回条目 code={}", code);
                item.setBidSearchJson("[]");
            } else {
                matched++;
                item.setBidSearchJson(toJsonStringArray(queries));
            }
        }
        log.info("建清单bidSearch完成 batches={} items={} matched={} batchSize={}",
                batches, items.size(), matched, batch);
        if (rawParts.length() == 0) {
            return null;
        }
        if (batches == 1) {
            return rawParts.toString();
        }
        return "[" + rawParts + "]";
    }

    static String formatChecklistItemsForBidSearch(List<RequirementChecklistPort.ItemDraft> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null) {
            return "";
        }
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(nullToEmpty(item.getRequirementCode()));
            sb.append(" | ");
            sb.append(nullToEmpty(item.getSubType()));
            sb.append(" | ");
            sb.append(nullToEmpty(item.getTitle()));
            sb.append(" | ");
            sb.append(nullToEmpty(item.getText()));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 产物 D：按最终 Q/S 编号分批摘录招标侧证据；写回 evidenceJson，并用首条 quote 填 sourceExcerpt。
     * LLM 调用失败抛异常（与 C 一致，整单 FAILED）；无 chunkIds 的条目跳过。
     */
    private String attachEvidence(List<RequirementChecklistPort.ItemDraft> items,
                                  List<VectorRecallPort.RecallHit> ranked) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        Map<Long, VectorRecallPort.RecallHit> hitById = new LinkedHashMap<>();
        if (ranked != null) {
            for (int i = 0; i < ranked.size(); i++) {
                VectorRecallPort.RecallHit hit = ranked.get(i);
                if (hit != null) {
                    hitById.put(hit.getChunkId(), hit);
                }
            }
        }

        List<RequirementChecklistPort.ItemDraft> withChunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            List<Long> ids = parseChunkIdsJson(item.getChunkIdsJson());
            boolean any = false;
            for (int j = 0; j < ids.size(); j++) {
                if (hitById.containsKey(ids.get(j))) {
                    any = true;
                    break;
                }
            }
            if (any) {
                withChunks.add(item);
            }
        }
        if (withChunks.isEmpty()) {
            log.info("建清单evidence跳过 无可用chunkIds items={}", items.size());
            return null;
        }

        Map<String, List<LlmPort.EvidenceRow>> byCode = new LinkedHashMap<>();
        StringBuilder rawParts = new StringBuilder();
        int batch = bidSearchBatchSize;
        int batches = 0;
        for (int from = 0; from < withChunks.size(); from += batch) {
            int to = Math.min(from + batch, withChunks.size());
            List<RequirementChecklistPort.ItemDraft> slice = withChunks.subList(from, to);
            String itemsText = formatChecklistItemsForEvidence(slice);
            String chunksText = formatRelatedChunksForEvidence(slice, hitById);
            LlmPort.EvidenceResult result = llmPort.generateEvidence(itemsText, chunksText);
            batches++;
            if (result != null && result.getRawContent() != null) {
                if (rawParts.length() > 0) {
                    rawParts.append(',');
                }
                rawParts.append(result.getRawContent().trim());
            }
            List<LlmPort.EvidenceRow> rows = result == null ? null : result.getRows();
            if (rows == null) {
                continue;
            }
            for (int i = 0; i < rows.size(); i++) {
                LlmPort.EvidenceRow row = rows.get(i);
                if (row == null || row.getId() == null || row.getId().isBlank()) {
                    continue;
                }
                if (row.getFragmentId() == null || !hitById.containsKey(row.getFragmentId())) {
                    continue;
                }
                String code = row.getId().trim();
                List<LlmPort.EvidenceRow> list = byCode.get(code);
                if (list == null) {
                    list = new ArrayList<>();
                    byCode.put(code, list);
                }
                list.add(row);
            }
        }

        int matched = 0;
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            String code = item.getRequirementCode();
            List<LlmPort.EvidenceRow> rows = code == null ? null : byCode.get(code);
            if (rows == null || rows.isEmpty()) {
                item.setEvidenceJson("[]");
                continue;
            }
            matched++;
            item.setEvidenceJson(toEvidenceJson(rows));
            LlmPort.EvidenceRow first = rows.get(0);
            if (first.getQuote() != null && !first.getQuote().isBlank()) {
                item.setSourceExcerpt(first.getQuote());
            }
            if (item.getPageNo() == null && first.getPage() != null) {
                item.setPageNo(first.getPage());
            }
        }
        log.info("建清单evidence完成 batches={} itemsWithChunks={} matched={} batchSize={}",
                batches, withChunks.size(), matched, batch);
        if (rawParts.length() == 0) {
            return null;
        }
        if (batches == 1) {
            return rawParts.toString();
        }
        return "[" + rawParts + "]";
    }

    static String formatChecklistItemsForEvidence(List<RequirementChecklistPort.ItemDraft> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null) {
            return "";
        }
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            if (item == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(nullToEmpty(item.getRequirementCode()));
            sb.append(" | ");
            sb.append(nullToEmpty(item.getTitle()));
            sb.append(" | ");
            sb.append(nullToEmpty(item.getText()));
            sb.append(" | chunkIds=");
            List<Long> ids = parseChunkIdsJsonStatic(item.getChunkIdsJson());
            for (int j = 0; j < ids.size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(ids.get(j));
            }
        }
        return sb.toString();
    }

    private String formatRelatedChunksForEvidence(List<RequirementChecklistPort.ItemDraft> items,
                                                  Map<Long, VectorRecallPort.RecallHit> hitById) {
        Map<Long, Boolean> needed = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            List<Long> ids = parseChunkIdsJson(items.get(i).getChunkIdsJson());
            for (int j = 0; j < ids.size(); j++) {
                Long id = ids.get(j);
                if (hitById.containsKey(id)) {
                    needed.put(id, Boolean.TRUE);
                }
            }
        }
        List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
        for (Long id : needed.keySet()) {
            hits.add(hitById.get(id));
        }
        return formatChunks(hits);
    }

    private List<Long> parseChunkIdsJson(String json) {
        List<Long> result = new ArrayList<>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return result;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return result;
            }
            for (int i = 0; i < node.size(); i++) {
                if (node.get(i) != null && node.get(i).isNumber()) {
                    result.add(node.get(i).asLong());
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON 视为无 chunkIds
        }
        return result;
    }

    static List<Long> parseChunkIdsJsonStatic(String json) {
        List<Long> result = new ArrayList<>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return result;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (node == null || !node.isArray()) {
                return result;
            }
            for (int i = 0; i < node.size(); i++) {
                if (node.get(i) != null && node.get(i).isNumber()) {
                    result.add(node.get(i).asLong());
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON 视为无 chunkIds
        }
        return result;
    }

    private String toEvidenceJson(List<LlmPort.EvidenceRow> rows) {
        try {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                LlmPort.EvidenceRow row = rows.get(i);
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("fragmentId", row.getFragmentId());
                obj.put("page", row.getPage());
                obj.put("quote", row.getQuote());
                arr.add(obj);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception ex) {
            return "[]";
        }
    }

    static String appendLlmPartRaw(String llmRaw, String fieldName, String partRaw) {
        if (partRaw == null || partRaw.isBlank() || fieldName == null || fieldName.isBlank()) {
            return llmRaw;
        }
        String base = llmRaw == null || llmRaw.isBlank() ? "{}" : llmRaw.trim();
        String part = partRaw.trim();
        if (base.startsWith("{") && base.endsWith("}") && (part.startsWith("{") || part.startsWith("["))) {
            return base.substring(0, base.length() - 1) + ",\"" + fieldName + "\":" + part + "}";
        }
        return "{\"itemsRaw\":" + jsonStringLiteral(base)
                + ",\"" + fieldName + "\":" + (part.startsWith("{") || part.startsWith("[")
                ? part : jsonStringLiteral(part)) + "}";
    }

    static String appendBidSearchRaw(String llmRaw, String bidSearchRaw) {
        return appendLlmPartRaw(llmRaw, "bidSearch", bidSearchRaw);
    }

    private List<VectorRecallPort.RecallHit> recallAllDimensions(
            long orgId,
            long projectId,
            List<ReviewDimensionPort.Dimension> dimensions,
            List<Long> packageIds,
            List<Long> documentIds,
            int topK) {
        List<String> queryTexts = collectAllQueryTexts(dimensions);
        if (queryTexts.isEmpty()) {
            return new ArrayList<>();
        }

        long tEmbed = System.currentTimeMillis();
        List<float[]> vectors = embeddingPort.embed(queryTexts);
        long embedMs = System.currentTimeMillis() - tEmbed;
        if (vectors == null || vectors.size() != queryTexts.size()) {
            throw new IllegalStateException(
                    "query 批量向量化数量不一致: texts=" + queryTexts.size()
                            + " vectors=" + (vectors == null ? 0 : vectors.size()));
        }
        for (int i = 0; i < vectors.size(); i++) {
            if (vectors.get(i) == null) {
                throw new IllegalStateException("query 向量化失败: " + queryTexts.get(i));
            }
        }

        VectorRecallPort.RecallMultiQuery multi = new VectorRecallPort.RecallMultiQuery();
        multi.setOrgId(orgId);
        multi.setProjectId(projectId);
        multi.setQueryEmbeddings(vectors);
        multi.setTopK(topK);
        multi.setPackageIds(packageIds);
        multi.setDocumentIds(documentIds);

        VectorRecallPort.RecallMultiResult multiResult = vectorRecallPort.recallMulti(multi);
        List<VectorRecallPort.RecallHit> vectorMerged = mergeHitsByMaxScore(
                multiResult == null ? null : multiResult.getPerQueryHits());
        sortByScoreDesc(vectorMerged);

        List<VectorRecallPort.RecallHit> merged = vectorMerged;
        if (hybridEnabled && bm25LexicalRecall != null) {
            int lexicalTopK = bm25TopK < 1 ? topK : bm25TopK;
            List<List<VectorRecallPort.RecallHit>> bm25PerQuery = bm25LexicalRecall.searchMulti(
                    orgId, projectId, queryTexts, packageIds, documentIds, lexicalTopK);
            List<VectorRecallPort.RecallHit> bm25Merged = mergeHitsByMaxScore(bm25PerQuery);
            sortByScoreDesc(bm25Merged);
            merged = RrfFusion.fuse(vectorMerged, bm25Merged, rrfK, 0);
            log.info("建清单混合召回 projectId={} vectorHits={} bm25Hits={} rrfHits={} rrfK={}",
                    projectId, vectorMerged.size(), bm25Merged.size(), merged.size(), rrfK);
        }

        log.info("建清单召回完成 projectId={} queryCount={} candidates={} "
                        + "embedMs={} loadMs={} scoreMs={} mergedHits={} hybrid={}",
                projectId,
                queryTexts.size(),
                multiResult == null ? 0 : multiResult.getCandidateCount(),
                embedMs,
                multiResult == null ? 0 : multiResult.getLoadMs(),
                multiResult == null ? 0 : multiResult.getScoreMs(),
                merged.size(),
                hybridEnabled);
        return merged;
    }

    private static List<VectorRecallPort.RecallHit> mergeHitsByMaxScore(
            List<List<VectorRecallPort.RecallHit>> perQuery) {
        Map<Long, VectorRecallPort.RecallHit> byChunk = new LinkedHashMap<>();
        if (perQuery != null) {
            for (int q = 0; q < perQuery.size(); q++) {
                List<VectorRecallPort.RecallHit> hits = perQuery.get(q);
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
        }
        List<VectorRecallPort.RecallHit> merged = new ArrayList<>();
        for (Map.Entry<Long, VectorRecallPort.RecallHit> e : byChunk.entrySet()) {
            merged.add(e.getValue());
        }
        return merged;
    }

    private static void sortByScoreDesc(List<VectorRecallPort.RecallHit> hits) {
        if (hits == null || hits.size() < 2) {
            return;
        }
        Collections.sort(hits, new Comparator<VectorRecallPort.RecallHit>() {
            @Override
            public int compare(VectorRecallPort.RecallHit a, VectorRecallPort.RecallHit b) {
                return Float.compare(b.getScore(), a.getScore());
            }
        });
    }

    /** 收集全部维度非空检索词（顺序稳定，供批量 embed）。 */
    static List<String> collectAllQueryTexts(List<ReviewDimensionPort.Dimension> dimensions) {
        List<String> texts = new ArrayList<>();
        int dimCount = dimensions == null ? 0 : dimensions.size();
        for (int d = 0; d < dimCount; d++) {
            ReviewDimensionPort.Dimension dim = dimensions.get(d);
            if (dim == null || dim.getQueries() == null) {
                continue;
            }
            List<String> queries = dim.getQueries();
            for (int q = 0; q < queries.size(); q++) {
                String query = queries.get(q);
                if (query == null || query.trim().isEmpty()) {
                    continue;
                }
                texts.add(query.trim());
            }
        }
        return texts;
    }

    private List<VectorRecallPort.RecallHit> rerankHits(
            String rerankQuery,
            List<VectorRecallPort.RecallHit> merged,
            int topN) {
        if (rerankQuery == null || rerankQuery.isEmpty()) {
            log.warn("建清单重排跳过：无可用综合 query");
            return new ArrayList<>();
        }
        List<String> docs = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            String content = merged.get(i).getContent();
            docs.add(content == null ? "" : content);
        }
        List<RerankPort.RerankHit> ranked = rerankPort.rerank(rerankQuery, docs, topN, tenderInstruct);
        List<VectorRecallPort.RecallHit> result = new ArrayList<>();
        if (ranked == null) {
            return result;
        }
        for (int i = 0; i < ranked.size(); i++) {
            RerankPort.RerankHit rh = ranked.get(i);
            int idx = rh.getIndex();
            if (idx < 0 || idx >= merged.size()) {
                throw new IllegalStateException("重排 index 越界: " + idx);
            }
            result.add(merged.get(idx));
        }
        log.debug("建清单重排 rerankQueryLen={} in={} out={}",
                rerankQuery.length(), merged.size(), result.size());
        return result;
    }

    /**
     * 多维综合重排 query：各维「名：侧面」用句号拼接。
     */
    static String buildAllDimensionsRerankQuery(List<ReviewDimensionPort.Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "招标资格条件与评分要求";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dimensions.size(); i++) {
            String part = buildCompositeRerankQuery(dimensions.get(i));
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('。');
            }
            sb.append(part);
        }
        if (sb.length() == 0) {
            return "招标资格条件与评分要求";
        }
        return sb.toString();
    }

    /**
     * 拼单维综合 query。形如：{@code 资格与资质：资格条件；资质等级}。
     */
    static String buildCompositeRerankQuery(ReviewDimensionPort.Dimension dim) {
        if (dim == null) {
            return "";
        }
        String name = dim.getName() == null ? "" : dim.getName().trim();
        List<String> parts = new ArrayList<>();
        List<String> queries = dim.getQueries();
        if (queries != null) {
            for (int i = 0; i < queries.size(); i++) {
                String q = queries.get(i);
                if (q == null) {
                    continue;
                }
                String trimmed = q.trim();
                if (!trimmed.isEmpty()) {
                    parts.add(trimmed);
                }
            }
        }
        if (parts.isEmpty()) {
            return name;
        }
        String joined = joinWithSemicolon(parts);
        if (name.isEmpty()) {
            return joined;
        }
        return name + "：" + joined;
    }

    private static String joinWithSemicolon(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append('；');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
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

    /**
     * 归一化文本完全相同则去重（保留先出现的）。
     */
    static List<RequirementChecklistPort.ItemDraft> dedupeByNormalizedText(
            List<RequirementChecklistPort.ItemDraft> items) {
        List<RequirementChecklistPort.ItemDraft> result = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        if (items == null) {
            return result;
        }
        for (int i = 0; i < items.size(); i++) {
            RequirementChecklistPort.ItemDraft item = items.get(i);
            String key = normalizeText(item.getText());
            if (key.isEmpty()) {
                continue;
            }
            if (seen.containsKey(key)) {
                continue;
            }
            seen.put(key, Boolean.TRUE);
            result.add(item);
        }
        return result;
    }

    static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean prevSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!prevSpace && sb.length() > 0) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString().trim();
    }

    private String toJsonLongArray(List<Long> ids) {
        try {
            if (ids == null) {
                return "[]";
            }
            return objectMapper.writeValueAsString(ids);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String toJsonStringArray(List<String> values) {
        try {
            if (values == null) {
                return "[]";
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<RecallHitView> toDebugHits(List<VectorRecallPort.RecallHit> hits) {
        List<RecallHitView> result = new ArrayList<>();
        if (hits == null) {
            return result;
        }
        for (int i = 0; i < hits.size(); i++) {
            VectorRecallPort.RecallHit hit = hits.get(i);
            RecallHitView view = new RecallHitView();
            view.setRank(i + 1);
            view.setChunkId(hit.getChunkId());
            view.setChunkIndex(hit.getChunkIndex());
            view.setPageNo(hit.getPageNo());
            view.setContent(hit.getContent());
            view.setDocumentId(hit.getDocumentId());
            view.setFileName(hit.getFileName());
            view.setPackageId(hit.getPackageId());
            view.setPackageType(hit.getPackageType());
            view.setPackageName(hit.getPackageName());
            view.setScore(hit.getScore());
            result.add(view);
        }
        return result;
    }

    private static List<RerankHitView> toRerankDebugHits(
            List<VectorRecallPort.RecallHit> recalled,
            List<RerankPort.RerankHit> ranked) {
        List<RerankHitView> result = new ArrayList<>();
        if (recalled == null || ranked == null) {
            return result;
        }
        for (int i = 0; i < ranked.size(); i++) {
            RerankPort.RerankHit rankedHit = ranked.get(i);
            int idx = rankedHit.getIndex();
            if (idx < 0 || idx >= recalled.size()) {
                throw new IllegalStateException("重排 index 越界: " + idx);
            }
            VectorRecallPort.RecallHit hit = recalled.get(idx);
            RerankHitView view = new RerankHitView();
            view.setRank(i + 1);
            view.setChunkId(hit.getChunkId());
            view.setChunkIndex(hit.getChunkIndex());
            view.setPageNo(hit.getPageNo());
            view.setContent(hit.getContent());
            view.setDocumentId(hit.getDocumentId());
            view.setFileName(hit.getFileName());
            view.setPackageId(hit.getPackageId());
            view.setPackageType(hit.getPackageType());
            view.setPackageName(hit.getPackageName());
            view.setRecallScore(hit.getScore());
            view.setRerankScore(rankedHit.getScore());
            result.add(view);
        }
        return result;
    }

    /** 管线结果：条目 + LLM 原文。 */
    public static class PipelineResult {
        private List<RequirementChecklistPort.ItemDraft> items = new ArrayList<>();
        private String llmRawJson;

        public List<RequirementChecklistPort.ItemDraft> getItems() {
            return items;
        }

        public void setItems(List<RequirementChecklistPort.ItemDraft> items) {
            if (items == null) {
                this.items = new ArrayList<>();
            } else {
                this.items = items;
            }
        }

        public String getLlmRawJson() {
            return llmRawJson;
        }

        public void setLlmRawJson(String llmRawJson) {
            this.llmRawJson = llmRawJson;
        }
    }

    /** 建清单召回调试结果。 */
    public static class RecallDebugResult {
        private int topK;
        private List<String> queryTexts = new ArrayList<>();
        private List<RecallHitView> hits = new ArrayList<>();

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public List<String> getQueryTexts() {
            return queryTexts;
        }

        public void setQueryTexts(List<String> queryTexts) {
            this.queryTexts = queryTexts == null ? new ArrayList<>() : queryTexts;
        }

        public List<RecallHitView> getHits() {
            return hits;
        }

        public void setHits(List<RecallHitView> hits) {
            this.hits = hits == null ? new ArrayList<>() : hits;
        }
    }

    /** 建清单重排调试结果。 */
    public static class RerankDebugResult {
        private int recallTopK;
        private int rerankTopN;
        private List<String> queryTexts = new ArrayList<>();
        private String rerankQuery;
        private int recallHitCount;
        private List<RerankHitView> hits = new ArrayList<>();

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public int getRerankTopN() {
            return rerankTopN;
        }

        public void setRerankTopN(int rerankTopN) {
            this.rerankTopN = rerankTopN;
        }

        public List<String> getQueryTexts() {
            return queryTexts;
        }

        public void setQueryTexts(List<String> queryTexts) {
            this.queryTexts = queryTexts == null ? new ArrayList<>() : queryTexts;
        }

        public int getQueryCount() {
            return queryTexts.size();
        }

        public String getRerankQuery() {
            return rerankQuery;
        }

        public void setRerankQuery(String rerankQuery) {
            this.rerankQuery = rerankQuery;
        }

        public int getRecallHitCount() {
            return recallHitCount;
        }

        public void setRecallHitCount(int recallHitCount) {
            this.recallHitCount = recallHitCount;
        }

        public List<RerankHitView> getHits() {
            return hits;
        }

        public void setHits(List<RerankHitView> hits) {
            this.hits = hits == null ? new ArrayList<>() : hits;
        }

        public int getRerankHitCount() {
            return hits.size();
        }
    }

    /** 召回命中片段调试视图。 */
    public static class RecallHitView {
        private int rank;
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

        public int getRank() {
            return rank;
        }

        public void setRank(int rank) {
            this.rank = rank;
        }

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

    /** 重排命中片段调试视图。 */
    public static class RerankHitView {
        private int rank;
        private long chunkId;
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private long documentId;
        private String fileName;
        private long packageId;
        private String packageType;
        private String packageName;
        private float recallScore;
        private float rerankScore;

        public int getRank() {
            return rank;
        }

        public void setRank(int rank) {
            this.rank = rank;
        }

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

        public float getRecallScore() {
            return recallScore;
        }

        public void setRecallScore(float recallScore) {
            this.recallScore = recallScore;
        }

        public float getRerankScore() {
            return rerankScore;
        }

        public void setRerankScore(float rerankScore) {
            this.rerankScore = rerankScore;
        }
    }

    /** 进度回调。 */
    public interface ProgressCallback {
        void onProgress(int progress, String message);
    }
}
