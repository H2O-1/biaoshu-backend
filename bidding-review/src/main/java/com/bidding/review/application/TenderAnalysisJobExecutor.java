package com.bidding.review.application;

import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 招标解析任务执行器。
 * <p>
 * 当前阶段等待本次上传文件的 INDEX 全部完成后，先抽取基础信息；
 * 资格要求、评分标准抽取将在后续步骤接入。
 */
@Service
public class TenderAnalysisJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(TenderAnalysisJobExecutor.class);
    private static final String USER_FRIENDLY_FAILURE_MESSAGE = "解析失败，请稍后重试";

    private final JobPort jobPort;
    private final DocumentAccessPort documentAccessPort;
    private final DocumentIndexStorePort documentIndexStorePort;
    private final LlmPort llmPort;
    private final ChecklistBuildPipeline checklistBuildPipeline;
    private final TenderIssueScanPipeline tenderIssueScanPipeline;
    private final TenderAnalysisPort tenderAnalysisPort;
    private final ObjectMapper objectMapper;

    public TenderAnalysisJobExecutor(JobPort jobPort,
                                     DocumentAccessPort documentAccessPort,
                                     DocumentIndexStorePort documentIndexStorePort,
                                     LlmPort llmPort,
                                     ChecklistBuildPipeline checklistBuildPipeline,
                                     TenderIssueScanPipeline tenderIssueScanPipeline,
                                     TenderAnalysisPort tenderAnalysisPort,
                                     ObjectMapper objectMapper) {
        this.jobPort = jobPort;
        this.documentAccessPort = documentAccessPort;
        this.documentIndexStorePort = documentIndexStorePort;
        this.llmPort = llmPort;
        this.checklistBuildPipeline = checklistBuildPipeline;
        this.tenderIssueScanPipeline = tenderIssueScanPipeline;
        this.tenderAnalysisPort = tenderAnalysisPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 认领并推进一条任务；无可用任务返回 false。
     */
    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional =
                jobPort.claimNextRunnable(JobPort.TYPE_TENDER_ANALYSIS, workerId);
        if (optional.isEmpty()) {
            return false;
        }
        execute(optional.get());
        return true;
    }

    private void execute(JobPort.JobRef job) {
        long jobId = job.getId();
        Payload payload;
        try {
            payload = parsePayload(job.getPayloadJson());
        } catch (Exception ex) {
            fail(jobId, null, "TENDER_ANALYSIS payload 无效: " + ex.getMessage());
            return;
        }

        try {
            if (payload.documentIds.isEmpty()) {
                fail(jobId, payload.analysisId, "招标解析任务缺少文档");
                return;
            }

            int success = 0;
            int waiting = 0;
            DocumentAccessPort.DocumentRef failedDoc = null;
            for (int i = 0; i < payload.documentIds.size(); i++) {
                Long documentId = payload.documentIds.get(i);
                DocumentAccessPort.DocumentRef doc = documentAccessPort.findActiveById(documentId);
                if (doc == null || doc.getOrgId() != job.getOrgId()) {
                    fail(jobId, payload.analysisId, "文档不存在: " + documentId);
                    return;
                }
                String status = doc.getIndexStatus();
                if (DocumentAccessPort.INDEX_SUCCESS.equals(status)) {
                    success++;
                } else if (DocumentAccessPort.INDEX_FAILED.equals(status)) {
                    failedDoc = doc;
                    break;
                } else {
                    waiting++;
                }
            }

            if (failedDoc != null) {
                fail(jobId, payload.analysisId, "文件解析失败：" + failedDoc.getFileName());
                return;
            }

            if (waiting > 0) {
                int progress = 10 + Math.min(30, success * 30 / payload.documentIds.size());
                jobPort.markRunningProgress(jobId, progress, "文件解析中");
                return;
            }

            jobPort.markRunningProgress(jobId, 45, "基础信息抽取中");
            List<DocumentIndexStorePort.ChunkView> chunks =
                    documentIndexStorePort.listChunksByDocumentIds(job.getOrgId(), payload.documentIds);
            if (chunks.isEmpty()) {
                fail(jobId, payload.analysisId, "未找到文档切块");
                return;
            }

            LlmPort.TenderBasicInfoResult basicResult =
                    llmPort.extractTenderBasicInfo(formatChunks(chunks));
            TenderAnalysisPort.BasicInfoDraft basicInfo = toBasicInfoDraft(basicResult, chunks);
            ChecklistBuildPipeline.PipelineResult checklistResult =
                    checklistBuildPipeline.buildFromRankedChunks(
                            toRecallHits(chunks),
                            (progress, message) -> jobPort.markRunningProgress(jobId, progress, message));
            List<TenderAnalysisPort.ItemDraft> itemDrafts =
                    toTenderAnalysisItems(checklistResult.getItems());
            jobPort.markRunningProgress(jobId, 80, "逻辑问题和错别字识别中");
            itemDrafts.addAll(tenderIssueScanPipeline.scan(chunks));
            jobPort.markRunningProgress(jobId, 90, "结果保存中");
            if (!itemDrafts.isEmpty()) {
                tenderAnalysisPort.insertItems(payload.analysisId, itemDrafts);
            }
            tenderAnalysisPort.markSuccess(payload.analysisId, basicInfo,
                    checklistResult.getLlmRawJson());
            jobPort.markSuccess(jobId, "招标解析完成");
            log.info("招标解析任务完成 jobId={} analysisId={} documents={} chunks={} items={}",
                    jobId, payload.analysisId, payload.documentIds.size(), chunks.size(), itemDrafts.size());
        } catch (Exception ex) {
            log.error("招标解析任务执行失败 jobId={} analysisId={}", jobId, payload.analysisId, ex);
            fail(jobId, payload.analysisId, USER_FRIENDLY_FAILURE_MESSAGE);
        }
    }

    private void fail(long jobId, Long analysisId, String message) {
        if (analysisId != null) {
            tenderAnalysisPort.markFailed(analysisId, message);
        }
        jobPort.markFailed(jobId, message);
    }

    private List<TenderAnalysisPort.ItemDraft> toTenderAnalysisItems(
            List<RequirementChecklistPort.ItemDraft> source) {
        List<TenderAnalysisPort.ItemDraft> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (int i = 0; i < source.size(); i++) {
            RequirementChecklistPort.ItemDraft src = source.get(i);
            if (src == null) {
                continue;
            }
            TenderAnalysisPort.ItemDraft item = new TenderAnalysisPort.ItemDraft();
            item.setItemType(ChecklistBuildPipeline.MAIN_SCORING.equals(src.getMainType())
                    ? TenderAnalysisPort.ITEM_SCORING : TenderAnalysisPort.ITEM_QUALIFICATION);
            item.setRequirementCode(src.getRequirementCode());
            item.setTitle(src.getTitle());
            item.setContent(src.getText());
            item.setSubType(src.getSubType());
            item.setSourceExcerpt(src.getSourceExcerpt());
            item.setPageNo(src.getPageNo());
            item.setChunkIdsJson(src.getChunkIdsJson());
            item.setEvidenceJson(src.getEvidenceJson());
            item.setScoreTotal(src.getScoreTotal());
            item.setScorePartsJson(src.getScorePartsJson());
            item.setScoreSumMatches(src.getScoreSumMatches());
            item.setSortOrder(src.getSortOrder());
            result.add(item);
        }
        return result;
    }

    private TenderAnalysisPort.BasicInfoDraft toBasicInfoDraft(LlmPort.TenderBasicInfoResult result,
                                                               List<DocumentIndexStorePort.ChunkView> chunks) {
        LlmPort.TenderBasicInfo info = result == null ? null : result.getInfo();
        TenderAnalysisPort.BasicInfoDraft draft = new TenderAnalysisPort.BasicInfoDraft();
        Map<Long, Integer> pageNoByChunkId = pageNoByChunkId(chunks);
        if (info != null) {
            draft.setTenderNo(valueOf(info.getTenderNo()));
            draft.setTenderer(valueOf(info.getTenderer()));
            draft.setAgency(valueOf(info.getAgency()));
            draft.setBudgetAmountText(valueOf(info.getBudgetAmount()));
            draft.setBidDeadlineText(deadlineTextOf(info.getBidDeadline()));
            draft.setBidDeadlineAt(parseInstant(info.getBidDeadline() == null ? null : info.getBidDeadline().getIso()));
            draft.setProjectIntro(valueOf(info.getProjectIntro()));
            draft.setProjectBackground(valueOf(info.getProjectBackground()));
            draft.setProjectOverview(valueOf(info.getProjectOverview()));
            draft.setEvidenceJson(buildBasicEvidenceJson(info, pageNoByChunkId));
        } else {
            draft.setEvidenceJson("[]");
        }
        draft.setRawJson(result == null ? null : result.getRawContent());
        return draft;
    }

    private Map<Long, Integer> pageNoByChunkId(List<DocumentIndexStorePort.ChunkView> chunks) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (chunks == null) {
            return result;
        }
        for (int i = 0; i < chunks.size(); i++) {
            DocumentIndexStorePort.ChunkView chunk = chunks.get(i);
            if (chunk != null) {
                result.put(chunk.getChunkId(), chunk.getPageNo());
            }
        }
        return result;
    }

    private String buildBasicEvidenceJson(LlmPort.TenderBasicInfo info, Map<Long, Integer> pageNoByChunkId) {
        ArrayNode array = objectMapper.createArrayNode();
        appendEvidence(array, "tenderNo", info.getTenderNo(), pageNoByChunkId);
        appendEvidence(array, "tenderer", info.getTenderer(), pageNoByChunkId);
        appendEvidence(array, "agency", info.getAgency(), pageNoByChunkId);
        appendEvidence(array, "budgetAmount", info.getBudgetAmount(), pageNoByChunkId);
        appendDeadlineEvidence(array, "bidDeadline", info.getBidDeadline(), pageNoByChunkId);
        appendEvidence(array, "projectIntro", info.getProjectIntro(), pageNoByChunkId);
        appendEvidence(array, "projectBackground", info.getProjectBackground(), pageNoByChunkId);
        appendEvidence(array, "projectOverview", info.getProjectOverview(), pageNoByChunkId);
        return array.toString();
    }

    private void appendEvidence(ArrayNode array, String field, LlmPort.FieldEvidence evidence,
                                Map<Long, Integer> pageNoByChunkId) {
        if (evidence == null) {
            return;
        }
        String value = valueOf(evidence);
        String quote = trimToNull(evidence.getQuote());
        Integer pageNo = pageNoByChunkId.get(evidence.getChunkId());
        if (value == null && quote == null && evidence.getChunkId() == null) {
            return;
        }
        ObjectNode node = array.addObject();
        node.put("field", field);
        putNullable(node, "value", value);
        putNullable(node, "quote", quote);
        putNullable(node, "chunkId", evidence.getChunkId());
        putNullable(node, "pageNo", pageNo);
    }

    private void appendDeadlineEvidence(ArrayNode array, String field, LlmPort.DeadlineEvidence evidence,
                                        Map<Long, Integer> pageNoByChunkId) {
        if (evidence == null) {
            return;
        }
        String text = deadlineTextOf(evidence);
        String quote = trimToNull(evidence.getQuote());
        Integer pageNo = pageNoByChunkId.get(evidence.getChunkId());
        if (text == null && quote == null && evidence.getChunkId() == null) {
            return;
        }
        ObjectNode node = array.addObject();
        node.put("field", field);
        putNullable(node, "value", text);
        putNullable(node, "quote", quote);
        putNullable(node, "chunkId", evidence.getChunkId());
        putNullable(node, "pageNo", pageNo);
    }

    private static String valueOf(LlmPort.FieldEvidence evidence) {
        return evidence == null ? null : trimToNull(evidence.getValue());
    }

    private static String deadlineTextOf(LlmPort.DeadlineEvidence evidence) {
        if (evidence == null) {
            return null;
        }
        String text = trimToNull(evidence.getText());
        return text == null ? trimToNull(evidence.getValue()) : text;
    }

    private static Instant parseInstant(String iso) {
        String text = trimToNull(iso);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
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

    private List<VectorRecallPort.RecallHit> toRecallHits(List<DocumentIndexStorePort.ChunkView> chunks) {
        List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
        if (chunks == null) {
            return hits;
        }
        for (int i = 0; i < chunks.size(); i++) {
            DocumentIndexStorePort.ChunkView chunk = chunks.get(i);
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(chunk.getChunkId());
            hit.setChunkIndex(chunk.getChunkIndex());
            hit.setPageNo(chunk.getPageNo());
            hit.setContent(chunk.getContent());
            hit.setDocumentId(chunk.getDocumentId());
            hit.setFileName(chunk.getFileName());
            hit.setScore(1.0f);
            hits.add(hit);
        }
        return hits;
    }

    private Payload parsePayload(String payloadJson) throws Exception {
        JsonNode root = objectMapper.readTree(payloadJson);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("不是 JSON 对象");
        }
        JsonNode analysisNode = root.get("analysisId");
        if (analysisNode == null || !analysisNode.canConvertToLong()) {
            throw new IllegalArgumentException("缺少 analysisId");
        }
        Payload payload = new Payload();
        payload.analysisId = analysisNode.asLong();
        JsonNode docsNode = root.get("documentIds");
        if (docsNode != null && docsNode.isArray()) {
            for (int i = 0; i < docsNode.size(); i++) {
                JsonNode item = docsNode.get(i);
                if (item != null && item.canConvertToLong()) {
                    payload.documentIds.add(item.asLong());
                }
            }
        }
        return payload;
    }

    private static class Payload {
        private long analysisId;
        private List<Long> documentIds = new ArrayList<>();
    }
}
