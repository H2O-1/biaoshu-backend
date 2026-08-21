package com.bidding.review.application;

import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 投标模拟审核任务执行器。
 * <p>
 * 等待招标文件和投标文件 INDEX 全部完成后，抽取招标侧资格/评分要求，审查投标侧响应，并扫描投标文件问题。
 */
@Service
public class BidSimulationAuditJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(BidSimulationAuditJobExecutor.class);

    private final JobPort jobPort;
    private final DocumentAccessPort documentAccessPort;
    private final DocumentIndexStorePort documentIndexStorePort;
    private final LlmPort llmPort;
    private final ChecklistBuildPipeline checklistBuildPipeline;
    private final ReviewComparePipeline reviewComparePipeline;
    private final TenderIssueScanPipeline tenderIssueScanPipeline;
    private final BidSimulationAuditPort bidSimulationAuditPort;
    private final ObjectMapper objectMapper;

    public BidSimulationAuditJobExecutor(JobPort jobPort,
                                         DocumentAccessPort documentAccessPort,
                                         DocumentIndexStorePort documentIndexStorePort,
                                         LlmPort llmPort,
                                         ChecklistBuildPipeline checklistBuildPipeline,
                                         ReviewComparePipeline reviewComparePipeline,
                                         TenderIssueScanPipeline tenderIssueScanPipeline,
                                         BidSimulationAuditPort bidSimulationAuditPort,
                                         ObjectMapper objectMapper) {
        this.jobPort = jobPort;
        this.documentAccessPort = documentAccessPort;
        this.documentIndexStorePort = documentIndexStorePort;
        this.llmPort = llmPort;
        this.checklistBuildPipeline = checklistBuildPipeline;
        this.reviewComparePipeline = reviewComparePipeline;
        this.tenderIssueScanPipeline = tenderIssueScanPipeline;
        this.bidSimulationAuditPort = bidSimulationAuditPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 认领并推进一条任务；无可用任务返回 false。
     */
    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional =
                jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_BID_SIMULATION_AUDIT, workerId);
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
            fail(jobId, null, "BID_SIMULATION_AUDIT payload 无效: " + ex.getMessage());
            return;
        }

        try {
            if (payload.tenderDocumentIds.isEmpty()) {
                fail(jobId, payload.auditId, "投标模拟审核任务缺少招标文件");
                return;
            }
            if (payload.bidDocumentIds.isEmpty()) {
                fail(jobId, payload.auditId, "投标模拟审核任务缺少投标文件");
                return;
            }

            List<Long> allDocumentIds = new ArrayList<>();
            allDocumentIds.addAll(payload.tenderDocumentIds);
            allDocumentIds.addAll(payload.bidDocumentIds);
            IndexState indexState = checkIndex(job.getOrgId(), allDocumentIds);
            if (indexState.failedDoc != null) {
                fail(jobId, payload.auditId, "文件解析失败：" + indexState.failedDoc.getFileName());
                return;
            }

            if (indexState.waiting > 0) {
                int progress = 10 + Math.min(25, indexState.success * 25 / allDocumentIds.size());
                jobPort.markRunningProgress(jobId, progress, "文件解析中");
                return;
            }

            jobPort.markRunningProgress(jobId, 45, "招标基础信息抽取中");
            List<DocumentIndexStorePort.ChunkView> tenderChunks =
                    documentIndexStorePort.listChunksByDocumentIds(job.getOrgId(), payload.tenderDocumentIds);
            if (tenderChunks.isEmpty()) {
                fail(jobId, payload.auditId, "未找到招标文件切块");
                return;
            }
            TenderAnalysisPort.BasicInfoDraft basicInfo =
                    toBasicInfoDraft(llmPort.extractTenderBasicInfo(formatChunks(tenderChunks)), tenderChunks);
            jobPort.markRunningProgress(jobId, 50, "资格要求和评分标准抽取中");
            ChecklistBuildPipeline.PipelineResult checklistResult =
                    checklistBuildPipeline.buildFromRankedChunks(
                            toRecallHits(tenderChunks),
                            (progress, message) -> jobPort.markRunningProgress(jobId, progress, message));
            List<RequirementChecklistPort.ItemDraft> checklistItems = checklistResult.getItems();
            jobPort.markRunningProgress(jobId, 70, "投标响应审查中");
            ReviewComparePipeline.PipelineResult compareResult =
                    reviewComparePipeline.runSimulation(
                            job.getOrgId(),
                            checklistItems,
                            payload.bidPackageId,
                            0,
                            0,
                            (progress, message) -> jobPort.markRunningProgress(jobId, progress, message));
            List<BidSimulationAuditPort.ItemDraft> items =
                    toAuditItems(checklistItems, compareResult.getItems());
            jobPort.markRunningProgress(jobId, 85, "逻辑问题和错别字识别中");
            List<DocumentIndexStorePort.ChunkView> bidChunks =
                    documentIndexStorePort.listChunksByDocumentIds(job.getOrgId(), payload.bidDocumentIds);
            if (bidChunks.isEmpty()) {
                fail(jobId, payload.auditId, "未找到投标文件切块");
                return;
            }
            items.addAll(toAuditIssueItems(tenderIssueScanPipeline.scan(bidChunks), items.size()));
            jobPort.markRunningProgress(jobId, 95, "结果保存中");
            if (!items.isEmpty()) {
                bidSimulationAuditPort.insertItems(payload.auditId, items);
            }
            bidSimulationAuditPort.markSuccess(payload.auditId, buildSummary(items), basicInfo);
            jobPort.markSuccess(jobId, "投标模拟审核完成");
            log.info("投标模拟审核招标侧抽取完成 jobId={} auditId={} tenderDocs={} bidDocs={} chunks={} items={}",
                    jobId, payload.auditId, payload.tenderDocumentIds.size(), payload.bidDocumentIds.size(),
                    tenderChunks.size(), items.size());
        } catch (Exception ex) {
            log.error("投标模拟审核任务执行失败 jobId={} auditId={}", jobId, payload.auditId, ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            fail(jobId, payload.auditId, message);
        }
    }

    private IndexState checkIndex(long orgId, List<Long> documentIds) {
        IndexState state = new IndexState();
        for (Long documentId : documentIds) {
            DocumentAccessPort.DocumentRef doc = documentAccessPort.findActiveById(documentId);
            if (doc == null || doc.getOrgId() != orgId) {
                throw new IllegalArgumentException("文档不存在: " + documentId);
            }
            String status = doc.getIndexStatus();
            if (DocumentAccessPort.INDEX_SUCCESS.equals(status)) {
                state.success++;
            } else if (DocumentAccessPort.INDEX_FAILED.equals(status)) {
                state.failedDoc = doc;
                return state;
            } else {
                state.waiting++;
            }
        }
        return state;
    }

    private void fail(long jobId, Long auditId, String message) {
        if (auditId != null) {
            bidSimulationAuditPort.markFailed(auditId, message);
        }
        jobPort.markFailed(jobId, message);
    }

    private List<BidSimulationAuditPort.ItemDraft> toAuditItems(
            List<RequirementChecklistPort.ItemDraft> source,
            List<ReviewPort.ItemDraft> compared) {
        List<BidSimulationAuditPort.ItemDraft> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (RequirementChecklistPort.ItemDraft src : source) {
            if (src == null) {
                continue;
            }
            BidSimulationAuditPort.ItemDraft item = new BidSimulationAuditPort.ItemDraft();
            boolean scoring = ChecklistBuildPipeline.MAIN_SCORING.equals(src.getMainType());
            ReviewPort.ItemDraft review = findComparedByCode(compared, src.getRequirementCode());
            item.setItemType(scoring
                    ? BidSimulationAuditPort.ITEM_SCORING : BidSimulationAuditPort.ITEM_QUALIFICATION);
            item.setRequirementCode(src.getRequirementCode());
            item.setTitle(firstNonBlank(review == null ? null : review.getTitle(), src.getTitle()));
            item.setContent(src.getText());
            item.setResult(scoring ? null : mapQualificationResult(review == null ? null : review.getStatus()));
            item.setReason(firstNonBlank(
                    review == null ? null : review.getReason(),
                    review == null ? null : review.getJudgement()));
            item.setSuggestedScore(scoring && review != null ? review.getSuggestedScore() : null);
            item.setFullScore(scoring
                    ? firstNonNull(review == null ? null : review.getFullScore(), src.getScoreTotal())
                    : null);
            item.setSubType(src.getSubType());
            item.setSourceExcerpt(firstNonBlank(review == null ? null : review.getBidExcerpt(), src.getSourceExcerpt()));
            item.setPageNo(review != null && review.getPageNo() != null ? review.getPageNo() : src.getPageNo());
            item.setChunkIdsJson(review != null && review.getChunkId() != null
                    ? "[" + review.getChunkId() + "]" : src.getChunkIdsJson());
            item.setEvidenceJson(review == null ? src.getEvidenceJson() : review.getBidEvidenceJson());
            item.setRawJson(review == null ? null : review.getLlmRawJson());
            item.setSortOrder(src.getSortOrder());
            result.add(item);
        }
        return result;
    }

    private ReviewPort.ItemDraft findComparedByCode(List<ReviewPort.ItemDraft> compared, String code) {
        if (compared == null || code == null) {
            return null;
        }
        for (ReviewPort.ItemDraft item : compared) {
            if (item != null && code.equals(item.getRequirementCode())) {
                return item;
            }
        }
        return null;
    }

    private List<BidSimulationAuditPort.ItemDraft> toAuditIssueItems(
            List<TenderAnalysisPort.ItemDraft> source,
            int sortOffset) {
        List<BidSimulationAuditPort.ItemDraft> result = new ArrayList<>();
        if (source == null) {
            return result;
        }
        for (TenderAnalysisPort.ItemDraft src : source) {
            if (src == null) {
                continue;
            }
            BidSimulationAuditPort.ItemDraft item = new BidSimulationAuditPort.ItemDraft();
            item.setItemType(src.getItemType());
            item.setRequirementCode(src.getRequirementCode());
            item.setTitle(src.getTitle());
            item.setContent(src.getContent());
            item.setSubType(src.getSubType());
            item.setSourceExcerpt(src.getSourceExcerpt());
            item.setPageNo(src.getPageNo());
            item.setChunkIdsJson(src.getChunkIdsJson());
            item.setEvidenceJson(src.getEvidenceJson());
            item.setSortOrder(sortOffset + src.getSortOrder());
            result.add(item);
        }
        return result;
    }

    private String mapQualificationResult(String status) {
        if (ReviewPort.STATUS_MATCH.equals(status)) {
            return BidSimulationAuditPort.RESULT_PASS;
        }
        if (ReviewPort.STATUS_MISMATCH.equals(status)) {
            return BidSimulationAuditPort.RESULT_FAIL;
        }
        return BidSimulationAuditPort.RESULT_MANUAL;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second;
    }

    private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
        return first == null ? second : first;
    }

    private BidSimulationAuditPort.SummaryDraft buildSummary(List<BidSimulationAuditPort.ItemDraft> items) {
        BidSimulationAuditPort.SummaryDraft summary = new BidSimulationAuditPort.SummaryDraft();
        BigDecimal scoreTotal = BigDecimal.ZERO;
        if (items == null) {
            summary.setScoreTotal(scoreTotal);
            return summary;
        }
        int qualificationTotal = 0;
        for (BidSimulationAuditPort.ItemDraft item : items) {
            if (item == null) {
                continue;
            }
            if (BidSimulationAuditPort.ITEM_QUALIFICATION.equals(item.getItemType())) {
                qualificationTotal++;
                if (BidSimulationAuditPort.RESULT_PASS.equals(item.getResult())) {
                    summary.setQualificationPassCount(summary.getQualificationPassCount() + 1);
                } else if (BidSimulationAuditPort.RESULT_FAIL.equals(item.getResult())) {
                    summary.setQualificationFailCount(summary.getQualificationFailCount() + 1);
                } else {
                    summary.setQualificationManualCount(summary.getQualificationManualCount() + 1);
                }
            } else if (BidSimulationAuditPort.ITEM_SCORING.equals(item.getItemType())
                    && item.getFullScore() != null) {
                scoreTotal = scoreTotal.add(item.getFullScore());
                if (item.getSuggestedScore() != null) {
                    summary.setScoreSuggested(summary.getScoreSuggested().add(item.getSuggestedScore()));
                }
            } else if (BidSimulationAuditPort.ITEM_LOGIC_ISSUE.equals(item.getItemType())) {
                summary.setLogicIssueCount(summary.getLogicIssueCount() + 1);
            } else if (BidSimulationAuditPort.ITEM_TYPO.equals(item.getItemType())) {
                summary.setTypoCount(summary.getTypoCount() + 1);
            }
        }
        summary.setQualificationTotal(qualificationTotal);
        if (qualificationTotal > 0) {
            BigDecimal passRate = BigDecimal.valueOf(summary.getQualificationPassCount())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(qualificationTotal), 2, java.math.RoundingMode.HALF_UP);
            summary.setQualificationPassRate(passRate);
        }
        summary.setScoreTotal(scoreTotal);
        return summary;
    }

    private List<VectorRecallPort.RecallHit> toRecallHits(List<DocumentIndexStorePort.ChunkView> chunks) {
        List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
        if (chunks == null) {
            return hits;
        }
        for (DocumentIndexStorePort.ChunkView chunk : chunks) {
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

    private TenderAnalysisPort.BasicInfoDraft toBasicInfoDraft(LlmPort.TenderBasicInfoResult result,
                                                               List<DocumentIndexStorePort.ChunkView> chunks) {
        LlmPort.TenderBasicInfo info = result == null ? null : result.getInfo();
        TenderAnalysisPort.BasicInfoDraft draft = new TenderAnalysisPort.BasicInfoDraft();
        Map<Long, Integer> pageNoByChunkId = pageNoByChunkId(chunks);
        if (info != null) {
            draft.setTenderer(valueOf(info.getTenderer()));
            draft.setAgency(valueOf(info.getAgency()));
            draft.setBudgetAmountText(valueOf(info.getBudgetAmount()));
            draft.setBidDeadlineText(deadlineTextOf(info.getBidDeadline()));
            draft.setBidDeadlineAt(parseInstant(info.getBidDeadline() == null ? null : info.getBidDeadline().getIso()));
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
        appendEvidence(array, "tenderer", info.getTenderer(), pageNoByChunkId);
        appendEvidence(array, "agency", info.getAgency(), pageNoByChunkId);
        appendEvidence(array, "budgetAmount", info.getBudgetAmount(), pageNoByChunkId);
        appendDeadlineEvidence(array, "bidDeadline", info.getBidDeadline(), pageNoByChunkId);
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

    private Payload parsePayload(String payloadJson) throws Exception {
        JsonNode root = objectMapper.readTree(payloadJson);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("不是 JSON 对象");
        }
        JsonNode auditNode = root.get("auditId");
        if (auditNode == null || !auditNode.canConvertToLong()) {
            throw new IllegalArgumentException("缺少 auditId");
        }
        Payload payload = new Payload();
        payload.auditId = auditNode.asLong();
        JsonNode bidPackageNode = root.get("bidPackageId");
        if (bidPackageNode == null || !bidPackageNode.canConvertToLong()) {
            throw new IllegalArgumentException("缺少 bidPackageId");
        }
        payload.bidPackageId = bidPackageNode.asLong();
        payload.tenderDocumentIds = readLongArray(root.get("tenderDocumentIds"));
        payload.bidDocumentIds = readLongArray(root.get("bidDocumentIds"));
        return payload;
    }

    private List<Long> readLongArray(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            if (item != null && item.canConvertToLong()) {
                result.add(item.asLong());
            }
        }
        return result;
    }

    private static class Payload {
        private long auditId;
        private long bidPackageId;
        private List<Long> tenderDocumentIds = new ArrayList<>();
        private List<Long> bidDocumentIds = new ArrayList<>();
    }

    private static class IndexState {
        private int success;
        private int waiting;
        private DocumentAccessPort.DocumentRef failedDoc;
    }
}
