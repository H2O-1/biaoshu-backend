package com.bidding.review.application;

import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidSimulationAuditJobExecutorTest {

    @Mock
    private JobPort jobPort;
    @Mock
    private DocumentAccessPort documentAccessPort;
    @Mock
    private DocumentIndexStorePort documentIndexStorePort;
    @Mock
    private LlmPort llmPort;
    @Mock
    private ChecklistBuildPipeline checklistBuildPipeline;
    @Mock
    private ReviewComparePipeline reviewComparePipeline;
    @Mock
    private TenderIssueScanPipeline tenderIssueScanPipeline;
    @Mock
    private BidSimulationAuditPort bidSimulationAuditPort;

    @Test
    void claimAndRunOnceReturnsFalseWhenNoJob() {
        BidSimulationAuditJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_BID_SIMULATION_AUDIT, "w1"))
                .thenReturn(Optional.empty());

        assertEquals(false, executor.claimAndRunOnce("w1"));
    }

    @Test
    void waitingIndexKeepsJobRunning() {
        BidSimulationAuditJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_BID_SIMULATION_AUDIT, "w1"))
                .thenReturn(Optional.of(job()));
        when(documentAccessPort.findActiveById(7L)).thenReturn(doc(7L, DocumentAccessPort.INDEX_SUCCESS));
        when(documentAccessPort.findActiveById(8L)).thenReturn(doc(8L, "PROCESSING"));

        assertEquals(true, executor.claimAndRunOnce("w1"));

        verify(jobPort).markRunningProgress(101L, 22, "文件解析中");
        verify(bidSimulationAuditPort, never()).markSuccess(eq(55L), any(), any());
        verify(jobPort, never()).markSuccess(eq(101L), anyString());
    }

    @Test
    void failedIndexMarksAuditAndJobFailed() {
        BidSimulationAuditJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_BID_SIMULATION_AUDIT, "w1"))
                .thenReturn(Optional.of(job()));
        when(documentAccessPort.findActiveById(7L)).thenReturn(doc(7L, DocumentAccessPort.INDEX_SUCCESS));
        DocumentAccessPort.DocumentRef failed = doc(8L, DocumentAccessPort.INDEX_FAILED);
        failed.setFileName("失败文件.pdf");
        when(documentAccessPort.findActiveById(8L)).thenReturn(failed);

        assertEquals(true, executor.claimAndRunOnce("w1"));

        verify(bidSimulationAuditPort).markFailed(55L, "文件解析失败：失败文件.pdf");
        verify(jobPort).markFailed(101L, "文件解析失败：失败文件.pdf");
        verify(bidSimulationAuditPort, never()).markSuccess(eq(55L), any(), any());
    }

    @Test
    void invalidPayloadMarksJobFailedOnly() {
        BidSimulationAuditJobExecutor executor = newExecutor();
        JobPort.JobRef job = job();
        job.setPayloadJson("{}");
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_BID_SIMULATION_AUDIT, "w1"))
                .thenReturn(Optional.of(job));

        assertEquals(true, executor.claimAndRunOnce("w1"));

        verify(jobPort).markFailed(eq(101L), anyString());
        verify(bidSimulationAuditPort, never()).markFailed(eq(55L), anyString());
    }

    @Test
    void successExtractsTenderChecklistAndMarksSummary() {
        BidSimulationAuditJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_BID_SIMULATION_AUDIT, "w1"))
                .thenReturn(Optional.of(job()));
        when(documentAccessPort.findActiveById(7L)).thenReturn(doc(7L, DocumentAccessPort.INDEX_SUCCESS));
        when(documentAccessPort.findActiveById(8L)).thenReturn(doc(8L, DocumentAccessPort.INDEX_SUCCESS));
        when(documentIndexStorePort.listChunksByDocumentIds(eq(2L), eq(List.of(7L))))
                .thenReturn(List.of(chunk(9001L, 7L, "资格要求：须具备二级资质。评分：技术方案10分。")));
        when(documentIndexStorePort.listChunksByDocumentIds(eq(2L), eq(List.of(8L))))
                .thenReturn(List.of(chunk(8001L, 8L, "投标正文：技术方案完整。存在解折错字。")));
        when(llmPort.extractTenderBasicInfo(anyString())).thenReturn(basicInfoResult());
        when(checklistBuildPipeline.buildFromRankedChunks(anyList(), any()))
                .thenReturn(checklistResult());
        when(reviewComparePipeline.runSimulation(eq(2L), anyList(), eq(88L), eq(0), eq(0), any()))
                .thenReturn(compareResult());
        when(tenderIssueScanPipeline.scan(anyList())).thenReturn(issueItems());

        assertEquals(true, executor.claimAndRunOnce("w1"));

        verify(jobPort).markRunningProgress(101L, 45, "招标基础信息抽取中");
        verify(jobPort).markRunningProgress(101L, 50, "资格要求和评分标准抽取中");
        verify(jobPort).markRunningProgress(101L, 70, "投标响应审查中");
        verify(jobPort).markRunningProgress(101L, 85, "逻辑问题和错别字识别中");
        verify(jobPort).markRunningProgress(101L, 95, "结果保存中");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BidSimulationAuditPort.ItemDraft>> itemsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(bidSimulationAuditPort).insertItems(eq(55L), itemsCaptor.capture());
        List<BidSimulationAuditPort.ItemDraft> items = itemsCaptor.getValue();
        assertEquals(4, items.size());
        assertEquals(BidSimulationAuditPort.ITEM_QUALIFICATION, items.get(0).getItemType());
        assertEquals("Q1", items.get(0).getRequirementCode());
        assertEquals("须具备二级资质", items.get(0).getContent());
        assertEquals(BidSimulationAuditPort.RESULT_PASS, items.get(0).getResult());
        assertEquals("投标文件提供二级资质证书。", items.get(0).getReason());
        assertEquals("本公司具备二级资质", items.get(0).getSourceExcerpt());
        assertEquals(Integer.valueOf(2), items.get(0).getPageNo());
        assertEquals("[8001]", items.get(0).getChunkIdsJson());
        assertEquals("[{\"fragmentId\":8001,\"page\":2,\"quote\":\"本公司具备二级资质\"}]", items.get(0).getEvidenceJson());
        assertEquals(BidSimulationAuditPort.ITEM_SCORING, items.get(1).getItemType());
        assertEquals("S1", items.get(1).getRequirementCode());
        assertEquals(new BigDecimal("10.00"), items.get(1).getFullScore());
        assertEquals(new BigDecimal("8.00"), items.get(1).getSuggestedScore());
        assertEquals("方案较完整，缺少风险预案。", items.get(1).getReason());
        assertEquals(BidSimulationAuditPort.ITEM_LOGIC_ISSUE, items.get(2).getItemType());
        assertEquals("逻辑问题-1", items.get(2).getRequirementCode());
        assertEquals("页码前后不一致", items.get(2).getTitle());
        assertEquals(2, items.get(2).getSortOrder());
        assertEquals(BidSimulationAuditPort.ITEM_TYPO, items.get(3).getItemType());
        assertEquals("错别字-1", items.get(3).getRequirementCode());
        assertEquals("疑似错别字：解折", items.get(3).getTitle());
        assertEquals(3, items.get(3).getSortOrder());

        ArgumentCaptor<BidSimulationAuditPort.SummaryDraft> summaryCaptor =
                ArgumentCaptor.forClass(BidSimulationAuditPort.SummaryDraft.class);
        ArgumentCaptor<TenderAnalysisPort.BasicInfoDraft> basicCaptor =
                ArgumentCaptor.forClass(TenderAnalysisPort.BasicInfoDraft.class);
        verify(bidSimulationAuditPort).markSuccess(eq(55L), summaryCaptor.capture(), basicCaptor.capture());
        assertEquals(1, summaryCaptor.getValue().getQualificationTotal());
        assertEquals(1, summaryCaptor.getValue().getQualificationPassCount());
        assertEquals(0, summaryCaptor.getValue().getQualificationFailCount());
        assertEquals(0, summaryCaptor.getValue().getQualificationManualCount());
        assertEquals(new BigDecimal("100.00"), summaryCaptor.getValue().getQualificationPassRate());
        assertEquals(new BigDecimal("8.00"), summaryCaptor.getValue().getScoreSuggested());
        assertEquals(new BigDecimal("10.00"), summaryCaptor.getValue().getScoreTotal());
        assertEquals(1, summaryCaptor.getValue().getLogicIssueCount());
        assertEquals(1, summaryCaptor.getValue().getTypoCount());
        assertEquals("采购人A", basicCaptor.getValue().getTenderer());
        assertEquals("代理B", basicCaptor.getValue().getAgency());
        assertEquals("预算金额100万元", basicCaptor.getValue().getBudgetAmountText());
        assertEquals("2026年8月15日17:00", basicCaptor.getValue().getBidDeadlineText());
        assertEquals("2026-08-15T09:00:00Z", basicCaptor.getValue().getBidDeadlineAt().toString());
        assertEquals("[{\"field\":\"tenderer\",\"value\":\"采购人A\",\"quote\":\"招标人：采购人A\",\"chunkId\":9001,\"pageNo\":21},{\"field\":\"agency\",\"value\":\"代理B\",\"quote\":\"代理机构：代理B\",\"chunkId\":9001,\"pageNo\":21},{\"field\":\"budgetAmount\",\"value\":\"预算金额100万元\",\"quote\":\"预算金额100万元\",\"chunkId\":9001,\"pageNo\":21},{\"field\":\"bidDeadline\",\"value\":\"2026年8月15日17:00\",\"quote\":\"截止时间：2026年8月15日17:00\",\"chunkId\":9001,\"pageNo\":21}]",
                basicCaptor.getValue().getEvidenceJson());
        assertEquals("{\"basicInfo\":true}", basicCaptor.getValue().getRawJson());
        verify(jobPort).markSuccess(101L, "投标模拟审核完成");
    }

    private BidSimulationAuditJobExecutor newExecutor() {
        return new BidSimulationAuditJobExecutor(
                jobPort,
                documentAccessPort,
                documentIndexStorePort,
                llmPort,
                checklistBuildPipeline,
                reviewComparePipeline,
                tenderIssueScanPipeline,
                bidSimulationAuditPort,
                new ObjectMapper()
        );
    }

    private static JobPort.JobRef job() {
        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(101L);
        job.setOrgId(2L);
        job.setPayloadJson("{\"auditId\":55,\"bidPackageId\":88,\"tenderDocumentIds\":[7],\"bidDocumentIds\":[8]}");
        return job;
    }

    private static DocumentAccessPort.DocumentRef doc(long id, String indexStatus) {
        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(id);
        doc.setOrgId(2L);
        doc.setFileName("文件" + id + ".pdf");
        doc.setIndexStatus(indexStatus);
        return doc;
    }

    private static DocumentIndexStorePort.ChunkView chunk(long chunkId, long documentId, String content) {
        DocumentIndexStorePort.ChunkView chunk = new DocumentIndexStorePort.ChunkView();
        chunk.setChunkId(chunkId);
        chunk.setDocumentId(documentId);
        chunk.setFileName("文件" + documentId + ".pdf");
        chunk.setChunkIndex(0);
        chunk.setPageNo(documentId == 7L ? 21 : 22);
        chunk.setContent(content);
        return chunk;
    }

    private static ChecklistBuildPipeline.PipelineResult checklistResult() {
        RequirementChecklistPort.ItemDraft q = new RequirementChecklistPort.ItemDraft();
        q.setMainType(ChecklistBuildPipeline.MAIN_QUALIFICATION);
        q.setRequirementCode("Q1");
        q.setTitle("资质");
        q.setText("须具备二级资质");
        q.setSubType("CERTIFICATE");
        q.setSourceExcerpt("须具备二级资质");
        q.setPageNo(1);
        q.setChunkIdsJson("[9001]");
        q.setEvidenceJson("[{\"fragmentId\":9001,\"page\":1,\"quote\":\"须具备二级资质\"}]");
        q.setSortOrder(0);

        RequirementChecklistPort.ItemDraft s = new RequirementChecklistPort.ItemDraft();
        s.setMainType(ChecklistBuildPipeline.MAIN_SCORING);
        s.setRequirementCode("S1");
        s.setTitle("技术方案");
        s.setText("技术方案满分10分");
        s.setSubType("TECH_SCORE");
        s.setSourceExcerpt("技术方案满分10分");
        s.setPageNo(1);
        s.setChunkIdsJson("[9001]");
        s.setEvidenceJson("[{\"fragmentId\":9001,\"page\":1,\"quote\":\"技术方案满分10分\"}]");
        s.setScoreTotal(new BigDecimal("10.00"));
        s.setSortOrder(1);

        List<RequirementChecklistPort.ItemDraft> items = new ArrayList<>();
        items.add(q);
        items.add(s);
        ChecklistBuildPipeline.PipelineResult result = new ChecklistBuildPipeline.PipelineResult();
        result.setItems(items);
        result.setLlmRawJson("{\"qualification\":{},\"scoring\":{}}");
        return result;
    }

    private static ReviewComparePipeline.PipelineResult compareResult() {
        ReviewPort.ItemDraft q = new ReviewPort.ItemDraft();
        q.setRequirementCode("Q1");
        q.setTitle("资质");
        q.setMainType(ReviewPort.MAIN_QUALIFICATION);
        q.setStatus(ReviewPort.STATUS_MATCH);
        q.setReason("投标文件提供二级资质证书。");
        q.setJudgement("投标文件提供二级资质证书。");
        q.setBidExcerpt("本公司具备二级资质");
        q.setPageNo(2);
        q.setChunkId(8001L);
        q.setBidEvidenceJson("[{\"fragmentId\":8001,\"page\":2,\"quote\":\"本公司具备二级资质\"}]");
        q.setLlmRawJson("{\"status\":\"符合\"}");

        ReviewPort.ItemDraft s = new ReviewPort.ItemDraft();
        s.setRequirementCode("S1");
        s.setTitle("技术方案");
        s.setMainType(ReviewPort.MAIN_SCORING);
        s.setReason("方案较完整，缺少风险预案。");
        s.setJudgement("方案较完整，缺少风险预案。");
        s.setSuggestedScore(new BigDecimal("8.00"));
        s.setFullScore(new BigDecimal("10.00"));
        s.setBidExcerpt("技术方案覆盖总体架构和实施计划");
        s.setPageNo(5);
        s.setChunkId(8002L);
        s.setBidEvidenceJson("[{\"fragmentId\":8002,\"page\":5,\"quote\":\"技术方案覆盖总体架构和实施计划\"}]");
        s.setLlmRawJson("{\"suggestedScore\":8}");

        ReviewComparePipeline.PipelineResult result = new ReviewComparePipeline.PipelineResult();
        result.setItems(List.of(q, s));
        return result;
    }

    private static LlmPort.TenderBasicInfoResult basicInfoResult() {
        LlmPort.TenderBasicInfo info = new LlmPort.TenderBasicInfo();
        info.setTenderer(field("采购人A", "招标人：采购人A", 9001L, 9001));
        info.setAgency(field("代理B", "代理机构：代理B", 9001L, 9001));
        info.setBudgetAmount(field("预算金额100万元", "预算金额100万元", 9001L, 9001));
        LlmPort.DeadlineEvidence deadline = new LlmPort.DeadlineEvidence();
        deadline.setText("2026年8月15日17:00");
        deadline.setIso("2026-08-15T09:00:00Z");
        deadline.setQuote("截止时间：2026年8月15日17:00");
        deadline.setChunkId(9001L);
        deadline.setPageNo(9001);
        info.setBidDeadline(deadline);

        LlmPort.TenderBasicInfoResult result = new LlmPort.TenderBasicInfoResult();
        result.setInfo(info);
        result.setRawContent("{\"basicInfo\":true}");
        return result;
    }

    private static LlmPort.FieldEvidence field(String value, String quote, Long chunkId, Integer pageNo) {
        LlmPort.FieldEvidence field = new LlmPort.FieldEvidence();
        field.setValue(value);
        field.setQuote(quote);
        field.setChunkId(chunkId);
        field.setPageNo(pageNo);
        return field;
    }

    private static List<TenderAnalysisPort.ItemDraft> issueItems() {
        TenderAnalysisPort.ItemDraft logic = new TenderAnalysisPort.ItemDraft();
        logic.setItemType(TenderAnalysisPort.ITEM_LOGIC_ISSUE);
        logic.setRequirementCode("逻辑问题-1");
        logic.setTitle("页码前后不一致");
        logic.setContent("目录页码与正文页码不一致。建议核对目录。");
        logic.setSubType("PAGE_INCONSISTENCY");
        logic.setSourceExcerpt("目录第5页；正文第6页");
        logic.setPageNo(5);
        logic.setChunkIdsJson("[8001]");
        logic.setEvidenceJson("[{\"chunkId\":8001,\"pageNo\":5,\"quote\":\"目录第5页\"}]");
        logic.setSortOrder(0);

        TenderAnalysisPort.ItemDraft typo = new TenderAnalysisPort.ItemDraft();
        typo.setItemType(TenderAnalysisPort.ITEM_TYPO);
        typo.setRequirementCode("错别字-1");
        typo.setTitle("疑似错别字：解折");
        typo.setContent("原文疑似将“解析”写成“解折”。建议：改为“解析”。");
        typo.setSourceExcerpt("解折失败，请重新解析");
        typo.setPageNo(8);
        typo.setChunkIdsJson("[8002]");
        typo.setEvidenceJson("[{\"chunkId\":8002,\"pageNo\":8,\"quote\":\"解折失败，请重新解析\"}]");
        typo.setSortOrder(1);

        return List.of(logic, typo);
    }
}
