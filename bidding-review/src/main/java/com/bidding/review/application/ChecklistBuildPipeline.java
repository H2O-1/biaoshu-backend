package com.bidding.review.application;

import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RerankPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建清单管线：按管理员维度多 query 召回并合并 → 综合重排一次 → LLM 一次归纳资格/评分条目。
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
    private final RerankPort rerankPort;
    private final LlmPort llmPort;
    private final ObjectMapper objectMapper;
    private final int defaultRecallTopK;
    private final int tenderRerankTopN;
    private final String tenderInstruct;

    public ChecklistBuildPipeline(
            EmbeddingPort embeddingPort,
            VectorRecallPort vectorRecallPort,
            RerankPort rerankPort,
            LlmPort llmPort,
            ObjectMapper objectMapper,
            @Value("${bidding.checklist.recall-top-k:${bidding.recall.default-top-k:40}}") int defaultRecallTopK,
            @Value("${bidding.checklist.rerank-top-n:${bidding.rerank.tender-top-n:8}}") int tenderRerankTopN,
            @Value("${bidding.rerank.tender-instruct:给定招标要求查询，从招标文件中检索陈述相关强制性要求、资格条件或废标否决条款的段落。}")
            String tenderInstruct) {
        this.embeddingPort = embeddingPort;
        this.vectorRecallPort = vectorRecallPort;
        this.rerankPort = rerankPort;
        this.llmPort = llmPort;
        this.objectMapper = objectMapper;
        this.defaultRecallTopK = defaultRecallTopK < 1 ? 40 : defaultRecallTopK;
        this.tenderRerankTopN = tenderRerankTopN < 1 ? 8 : tenderRerankTopN;
        this.tenderInstruct = tenderInstruct;
    }

    /**
     * 对勾选维度做多路召回后一次归纳，返回可落库条目与 LLM 原文。
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

        if (progress != null) {
            progress.onProgress(15, "多维召回中");
        }
        List<VectorRecallPort.RecallHit> merged = recallAllDimensions(
                orgId, projectId, dimensions, tenderPackageIds, documentIds, safeRecallTopK);
        if (merged.isEmpty()) {
            log.info("建清单无召回 projectId={} dimCount={}",
                    projectId, dimensions == null ? 0 : dimensions.size());
            return emptyResult();
        }

        if (progress != null) {
            progress.onProgress(45, "重排中");
        }
        String rerankQuery = buildAllDimensionsRerankQuery(dimensions);
        List<VectorRecallPort.RecallHit> ranked = rerankHits(rerankQuery, merged, safeRerankTopN);
        if (ranked.isEmpty()) {
            log.warn("建清单重排结果为空 projectId={}", projectId);
            return emptyResult();
        }

        if (progress != null) {
            progress.onProgress(60, "LLM归纳中");
        }
        String chunksText = formatChunks(ranked);
        LlmPort.ExtractChecklistResult extractedResult = llmPort.extractChecklist(chunksText);
        List<LlmPort.ExtractedRequirement> extracted =
                extractedResult == null ? null : extractedResult.getItems();
        String llmRaw = extractedResult == null ? null : extractedResult.getRawContent();
        log.info("建清单归纳完成 projectId={} recall={} rerank={} extracted={}",
                projectId, merged.size(), ranked.size(),
                extracted == null ? 0 : extracted.size());

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
        if (progress != null) {
            progress.onProgress(85, "条目去重完成 count=" + deduped.size());
        }
        PipelineResult result = new PipelineResult();
        result.setItems(deduped);
        result.setLlmRawJson(llmRaw);
        return result;
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
        draft.setBidSearchJson(toJsonStringArray(er.getBidSearch()));
        draft.setEvidenceJson(er.getEvidenceJson());
        draft.setScoreTotal(er.getScoreTotal());
        draft.setScorePartsJson(er.getScorePartsJson());
        draft.setScoreSumMatches(er.getScoreSumMatches());
        draft.setSortOrder(sortOrder);
        return draft;
    }

    private List<VectorRecallPort.RecallHit> recallAllDimensions(
            long orgId,
            long projectId,
            List<ReviewDimensionPort.Dimension> dimensions,
            List<Long> packageIds,
            List<Long> documentIds,
            int topK) {
        Map<Long, VectorRecallPort.RecallHit> byChunk = new LinkedHashMap<>();
        int dimCount = dimensions == null ? 0 : dimensions.size();
        for (int d = 0; d < dimCount; d++) {
            ReviewDimensionPort.Dimension dim = dimensions.get(d);
            List<VectorRecallPort.RecallHit> part =
                    recallMergeByChunkId(orgId, projectId, dim, packageIds, documentIds, topK);
            for (int i = 0; i < part.size(); i++) {
                VectorRecallPort.RecallHit hit = part.get(i);
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

    private List<VectorRecallPort.RecallHit> recallMergeByChunkId(
            long orgId,
            long projectId,
            ReviewDimensionPort.Dimension dim,
            List<Long> packageIds,
            List<Long> documentIds,
            int topK) {
        Map<Long, VectorRecallPort.RecallHit> byChunk = new LinkedHashMap<>();
        List<String> queries = dim.getQueries();
        if (queries == null || queries.isEmpty()) {
            return new ArrayList<>();
        }
        for (int q = 0; q < queries.size(); q++) {
            String query = queries.get(q);
            if (query == null || query.trim().isEmpty()) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            texts.add(query.trim());
            List<float[]> vectors = embeddingPort.embed(texts);
            if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
                throw new IllegalStateException("query 向量化失败: " + query.trim());
            }
            VectorRecallPort.RecallQuery rq = new VectorRecallPort.RecallQuery();
            rq.setOrgId(orgId);
            rq.setProjectId(projectId);
            rq.setQueryEmbedding(vectors.get(0));
            rq.setTopK(topK);
            rq.setPackageIds(packageIds);
            rq.setDocumentIds(documentIds);
            List<VectorRecallPort.RecallHit> hits = vectorRecallPort.recall(rq);
            log.info("建清单召回 projectId={} dim={} queryLen={} hitCount={}",
                    projectId, dim.getCode(), query.trim().length(), hits == null ? 0 : hits.size());
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
        log.info("建清单重排 rerankQueryLen={} in={} out={}",
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

    /** 进度回调。 */
    public interface ProgressCallback {
        void onProgress(int progress, String message);
    }
}
