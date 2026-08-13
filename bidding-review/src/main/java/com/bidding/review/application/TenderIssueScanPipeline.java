package com.bidding.review.application;

import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 招标解析二期：按 chunk 批次扫描逻辑问题和错别字候选，并合并为最终条目。
 */
@Service
public class TenderIssueScanPipeline {

    private static final int BATCH_CHUNK_SIZE = 8;
    private static final int ISSUE_SCAN_PARALLELISM = 4;

    private final LlmPort llmPort;
    private final ObjectMapper objectMapper;

    public TenderIssueScanPipeline(LlmPort llmPort, ObjectMapper objectMapper) {
        this.llmPort = llmPort;
        this.objectMapper = objectMapper;
    }

    public List<TenderAnalysisPort.ItemDraft> scan(List<DocumentIndexStorePort.ChunkView> chunks) {
        List<TenderAnalysisPort.ItemDraft> result = new ArrayList<>();
        if (chunks == null || chunks.isEmpty()) {
            return result;
        }

        List<String> batchTexts = new ArrayList<>();
        for (int from = 0; from < chunks.size(); from += BATCH_CHUNK_SIZE) {
            int to = Math.min(from + BATCH_CHUNK_SIZE, chunks.size());
            batchTexts.add(formatChunks(chunks.subList(from, to)));
        }

        Map<String, LlmPort.LogicIssueCandidate> logicByKey = new LinkedHashMap<>();
        Map<String, LlmPort.TypoCandidate> typoByKey = new LinkedHashMap<>();
        List<LlmPort.TenderIssueScanResult> batchResults = scanBatches(batchTexts);
        for (int i = 0; i < batchResults.size(); i++) {
            LlmPort.TenderIssueScanResult batchResult = batchResults.get(i);
            if (batchResult == null) {
                continue;
            }
            collectLogicIssues(logicByKey, batchResult.getLogicIssues());
            collectTypos(typoByKey, batchResult.getTypos());
        }

        int sortOrder = 0;
        int logicNo = 1;
        for (LlmPort.LogicIssueCandidate candidate : logicByKey.values()) {
            TenderAnalysisPort.ItemDraft draft = toLogicIssueDraft(candidate);
            draft.setRequirementCode("逻辑问题-" + logicNo);
            draft.setSortOrder(sortOrder);
            result.add(draft);
            logicNo++;
            sortOrder++;
        }

        int typoNo = 1;
        for (LlmPort.TypoCandidate candidate : typoByKey.values()) {
            TenderAnalysisPort.ItemDraft draft = toTypoDraft(candidate);
            draft.setRequirementCode("错别字-" + typoNo);
            draft.setSortOrder(sortOrder);
            result.add(draft);
            typoNo++;
            sortOrder++;
        }
        return result;
    }

    private List<LlmPort.TenderIssueScanResult> scanBatches(List<String> batchTexts) {
        int poolSize = Math.min(ISSUE_SCAN_PARALLELISM, batchTexts.size());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<LlmPort.TenderIssueScanResult>> futures = new ArrayList<>();
            for (int i = 0; i < batchTexts.size(); i++) {
                String batchText = batchTexts.get(i);
                futures.add(pool.submit(() -> llmPort.scanTenderIssues(batchText)));
            }

            List<LlmPort.TenderIssueScanResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                results.add(futures.get(i).get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("逻辑问题和错别字识别被中断", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("逻辑问题和错别字识别失败", cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private void collectLogicIssues(Map<String, LlmPort.LogicIssueCandidate> byKey,
                                    List<LlmPort.LogicIssueCandidate> candidates) {
        if (candidates == null) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            LlmPort.LogicIssueCandidate candidate = candidates.get(i);
            if (candidate == null || !hasEvidence(candidate.getEvidence())) {
                continue;
            }
            String key = normalize(candidate.getTitle()) + "|" + normalize(candidate.getIssueType());
            if (!byKey.containsKey(key)) {
                byKey.put(key, candidate);
            } else {
                mergeEvidence(byKey.get(key).getEvidence(), candidate.getEvidence());
            }
        }
    }

    private void collectTypos(Map<String, LlmPort.TypoCandidate> byKey,
                              List<LlmPort.TypoCandidate> candidates) {
        if (candidates == null) {
            return;
        }
        for (int i = 0; i < candidates.size(); i++) {
            LlmPort.TypoCandidate candidate = candidates.get(i);
            if (candidate == null || !hasEvidence(candidate.getEvidence())) {
                continue;
            }
            String key = normalize(candidate.getWrongText())
                    + "|" + normalize(candidate.getSuggestedText())
                    + "|" + normalize(candidate.getContext());
            if (!byKey.containsKey(key)) {
                byKey.put(key, candidate);
            } else {
                mergeEvidence(byKey.get(key).getEvidence(), candidate.getEvidence());
            }
        }
    }

    private TenderAnalysisPort.ItemDraft toLogicIssueDraft(LlmPort.LogicIssueCandidate candidate) {
        TenderAnalysisPort.ItemDraft draft = new TenderAnalysisPort.ItemDraft();
        draft.setItemType(TenderAnalysisPort.ITEM_LOGIC_ISSUE);
        draft.setTitle(trimToNull(candidate.getTitle()));
        draft.setContent(joinProblemAndSuggestion(candidate.getProblem(), candidate.getSuggestion()));
        draft.setSubType(trimToNull(candidate.getIssueType()));
        draft.setSourceExcerpt(joinQuotes(candidate.getEvidence()));
        draft.setPageNo(firstPageNo(candidate.getEvidence()));
        draft.setChunkIdsJson(toJsonLongArray(chunkIds(candidate.getEvidence())));
        draft.setEvidenceJson(toLogicEvidenceJson(candidate));
        return draft;
    }

    private TenderAnalysisPort.ItemDraft toTypoDraft(LlmPort.TypoCandidate candidate) {
        TenderAnalysisPort.ItemDraft draft = new TenderAnalysisPort.ItemDraft();
        draft.setItemType(TenderAnalysisPort.ITEM_TYPO);
        draft.setTitle("疑似错别字：" + trimToEmpty(candidate.getWrongText()));
        draft.setContent(joinTypoContent(candidate));
        draft.setSubType(null);
        draft.setSourceExcerpt(firstNonBlank(candidate.getContext(), joinQuotes(candidate.getEvidence())));
        draft.setPageNo(firstPageNo(candidate.getEvidence()));
        draft.setChunkIdsJson(toJsonLongArray(chunkIds(candidate.getEvidence())));
        draft.setEvidenceJson(toTypoEvidenceJson(candidate));
        return draft;
    }

    private String formatChunks(List<DocumentIndexStorePort.ChunkView> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            DocumentIndexStorePort.ChunkView chunk = chunks.get(i);
            sb.append("—— 候选 ").append(i + 1).append(" ——\n");
            sb.append("chunkId=").append(chunk.getChunkId());
            if (chunk.getPageNo() != null) {
                sb.append(" pageNo=").append(chunk.getPageNo());
            }
            sb.append(" documentId=").append(chunk.getDocumentId());
            sb.append(" fileName=").append(chunk.getFileName() == null ? "" : chunk.getFileName());
            sb.append('\n');
            sb.append(chunk.getContent() == null ? "" : chunk.getContent());
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private boolean hasEvidence(List<LlmPort.IssueEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return false;
        }
        for (int i = 0; i < evidence.size(); i++) {
            LlmPort.IssueEvidence item = evidence.get(i);
            if (item != null && (item.getChunkId() != null || trimToNull(item.getQuote()) != null)) {
                return true;
            }
        }
        return false;
    }

    private void mergeEvidence(List<LlmPort.IssueEvidence> target, List<LlmPort.IssueEvidence> source) {
        if (target == null || source == null) {
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        for (int i = 0; i < target.size(); i++) {
            existing.add(evidenceKey(target.get(i)));
        }
        for (int i = 0; i < source.size(); i++) {
            LlmPort.IssueEvidence evidence = source.get(i);
            String key = evidenceKey(evidence);
            if (!existing.contains(key)) {
                target.add(evidence);
                existing.add(key);
            }
        }
    }

    private String evidenceKey(LlmPort.IssueEvidence evidence) {
        if (evidence == null) {
            return "";
        }
        return String.valueOf(evidence.getChunkId()) + "|" + normalize(evidence.getQuote());
    }

    private List<Long> chunkIds(List<LlmPort.IssueEvidence> evidence) {
        Set<Long> ids = new LinkedHashSet<>();
        if (evidence != null) {
            for (int i = 0; i < evidence.size(); i++) {
                LlmPort.IssueEvidence item = evidence.get(i);
                if (item != null && item.getChunkId() != null) {
                    ids.add(item.getChunkId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private Integer firstPageNo(List<LlmPort.IssueEvidence> evidence) {
        if (evidence == null) {
            return null;
        }
        for (int i = 0; i < evidence.size(); i++) {
            LlmPort.IssueEvidence item = evidence.get(i);
            if (item != null && item.getPageNo() != null) {
                return item.getPageNo();
            }
        }
        return null;
    }

    private String joinQuotes(List<LlmPort.IssueEvidence> evidence) {
        List<String> quotes = new ArrayList<>();
        if (evidence != null) {
            for (int i = 0; i < evidence.size(); i++) {
                LlmPort.IssueEvidence item = evidence.get(i);
                String quote = item == null ? null : trimToNull(item.getQuote());
                if (quote != null) {
                    quotes.add(quote);
                }
            }
        }
        return String.join("；", quotes);
    }

    private String toLogicEvidenceJson(LlmPort.LogicIssueCandidate candidate) {
        ArrayNode array = objectMapper.createArrayNode();
        List<LlmPort.IssueEvidence> evidence = candidate.getEvidence();
        for (int i = 0; i < evidence.size(); i++) {
            LlmPort.IssueEvidence item = evidence.get(i);
            if (item == null) {
                continue;
            }
            ObjectNode node = baseEvidenceNode(item);
            putNullable(node, "severity", trimToNull(candidate.getSeverity()));
            putNullable(node, "issueType", trimToNull(candidate.getIssueType()));
            putNullable(node, "suggestion", trimToNull(candidate.getSuggestion()));
            array.add(node);
        }
        return array.toString();
    }

    private String toTypoEvidenceJson(LlmPort.TypoCandidate candidate) {
        ArrayNode array = objectMapper.createArrayNode();
        List<LlmPort.IssueEvidence> evidence = candidate.getEvidence();
        for (int i = 0; i < evidence.size(); i++) {
            LlmPort.IssueEvidence item = evidence.get(i);
            if (item == null) {
                continue;
            }
            ObjectNode node = baseEvidenceNode(item);
            putNullable(node, "wrongText", trimToNull(candidate.getWrongText()));
            putNullable(node, "suggestedText", trimToNull(candidate.getSuggestedText()));
            putNullable(node, "confidence", trimToNull(candidate.getConfidence()));
            putNullable(node, "suggestion", trimToNull(candidate.getSuggestion()));
            array.add(node);
        }
        return array.toString();
    }

    private ObjectNode baseEvidenceNode(LlmPort.IssueEvidence evidence) {
        ObjectNode node = objectMapper.createObjectNode();
        putNullable(node, "chunkId", evidence.getChunkId());
        putNullable(node, "pageNo", evidence.getPageNo());
        putNullable(node, "quote", trimToNull(evidence.getQuote()));
        return node;
    }

    private String toJsonLongArray(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? new ArrayList<>() : ids);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 chunkIds 失败", ex);
        }
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private String joinProblemAndSuggestion(String problem, String suggestion) {
        String safeProblem = trimToNull(problem);
        String safeSuggestion = trimToNull(suggestion);
        if (safeProblem == null) {
            return safeSuggestion;
        }
        if (safeSuggestion == null) {
            return safeProblem;
        }
        return safeProblem + "建议：" + safeSuggestion;
    }

    private String joinTypoContent(LlmPort.TypoCandidate candidate) {
        String wrong = trimToNull(candidate.getWrongText());
        String suggested = trimToNull(candidate.getSuggestedText());
        String suggestion = trimToNull(candidate.getSuggestion());
        StringBuilder sb = new StringBuilder();
        if (wrong != null && suggested != null) {
            sb.append("原文疑似将“").append(suggested).append("”写成“").append(wrong).append("”。");
        } else if (wrong != null) {
            sb.append("原文存在疑似错别字“").append(wrong).append("”。");
        }
        if (suggestion != null) {
            sb.append("建议：").append(suggestion);
        }
        return sb.toString();
    }

    private String firstNonBlank(String first, String second) {
        String safeFirst = trimToNull(first);
        return safeFirst == null ? trimToNull(second) : safeFirst;
    }

    private String normalize(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed.replaceAll("\\s+", "");
    }

    private String trimToEmpty(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? "" : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
