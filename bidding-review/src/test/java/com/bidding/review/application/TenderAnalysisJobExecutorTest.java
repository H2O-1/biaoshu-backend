package com.bidding.review.application;

import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenderAnalysisJobExecutorTest {

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
    private TenderIssueScanPipeline tenderIssueScanPipeline;
    @Mock
    private TenderAnalysisPort tenderAnalysisPort;

    @Test
    void claimAndRunOnceReturnsFalseWhenNoJob() {
        TenderAnalysisJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_TENDER_ANALYSIS, "w1"))
                .thenReturn(Optional.empty());

        assertEquals(false, executor.claimAndRunOnce("w1"));
    }

    @Test
    void waitingIndexKeepsJobRunning() {
        TenderAnalysisJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_TENDER_ANALYSIS, "w1"))
                .thenReturn(Optional.of(job()));
        DocumentAccessPort.DocumentRef doc1 = doc(7L, DocumentAccessPort.INDEX_SUCCESS);
        DocumentAccessPort.DocumentRef doc2 = doc(8L, "PROCESSING");
        when(documentAccessPort.findActiveById(7L)).thenReturn(doc1);
        when(documentAccessPort.findActiveById(8L)).thenReturn(doc2);

        assertEquals(true, executor.claimAndRunOnce("w1"));

        verify(jobPort).markRunningProgress(101L, 25, "文件解析中 success=1 waiting=1");
        verify(llmPort, never()).extractTenderBasicInfo(anyString());
        verify(tenderAnalysisPort, never()).markSuccess(anyLong(), any(), anyString());
    }

    @Test
    void failedIndexMarksAnalysisAndJobFailed() {
        TenderAnalysisJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_TENDER_ANALYSIS, "w1"))
                .thenReturn(Optional.of(job()));
        DocumentAccessPort.DocumentRef doc1 = doc(7L, DocumentAccessPort.INDEX_SUCCESS);
        DocumentAccessPort.DocumentRef doc2 = doc(8L, DocumentAccessPort.INDEX_FAILED);
        doc2.setFileName("失败文件.pdf");
        when(documentAccessPort.findActiveById(7L)).thenReturn(doc1);
        when(documentAccessPort.findActiveById(8L)).thenReturn(doc2);

        assertEquals(true, executor.claimAndRunOnce("w1"));

        verify(tenderAnalysisPort).markFailed(eq(55L), anyString());
        verify(jobPort).markFailed(eq(101L), anyString());
        verify(llmPort, never()).extractTenderBasicInfo(anyString());
    }

    @Test
    void successExtractsBasicInfoAndChecklistItems() {
        TenderAnalysisJobExecutor executor = newExecutor();
        when(jobPort.claimNextPendingOrOwnedRunning(JobPort.TYPE_TENDER_ANALYSIS, "w1"))
                .thenReturn(Optional.of(job()));
        when(documentAccessPort.findActiveById(7L)).thenReturn(doc(7L, DocumentAccessPort.INDEX_SUCCESS));
        when(documentAccessPort.findActiveById(8L)).thenReturn(doc(8L, DocumentAccessPort.INDEX_SUCCESS));

        List<DocumentIndexStorePort.ChunkView> chunks = new ArrayList<>();
        chunks.add(chunk(9001L, 7L, "招标人：采购人A。预算金额100万元。"));
        chunks.add(chunk(9002L, 8L, "评分标准：技术方案满分10分。"));
        when(documentIndexStorePort.listChunksByDocumentIds(eq(2L), anyList())).thenReturn(chunks);
        when(llmPort.extractTenderBasicInfo(anyString())).thenReturn(basicInfoResult());
        when(checklistBuildPipeline.buildFromRankedChunks(anyList(), any()))
                .thenReturn(checklistResult());
        when(tenderIssueScanPipeline.scan(anyList())).thenReturn(issueItems());

        assertEquals(true, executor.claimAndRunOnce("w1"));

        ArgumentCaptor<TenderAnalysisPort.BasicInfoDraft> basicCaptor =
                ArgumentCaptor.forClass(TenderAnalysisPort.BasicInfoDraft.class);
        verify(tenderAnalysisPort).markSuccess(eq(55L), basicCaptor.capture(), eq("{\"qualification\":{},\"scoring\":{}}"));
        TenderAnalysisPort.BasicInfoDraft basic = basicCaptor.getValue();
        assertEquals("采购人A", basic.getTenderer());
        assertEquals("代理B", basic.getAgency());
        assertEquals("预算金额100万元", basic.getBudgetAmountText());
        assertEquals("2026年8月15日17:00", basic.getBidDeadlineText());
        assertEquals(Instant.parse("2026-08-15T09:00:00Z"), basic.getBidDeadlineAt());
        assertTrue(basic.getEvidenceJson().contains("\"field\":\"tenderer\""));
        assertTrue(basic.getRawJson().contains("raw"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TenderAnalysisPort.ItemDraft>> itemsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(tenderAnalysisPort).insertItems(eq(55L), itemsCaptor.capture());
        List<TenderAnalysisPort.ItemDraft> items = itemsCaptor.getValue();
        assertEquals(4, items.size());
        assertEquals(TenderAnalysisPort.ITEM_QUALIFICATION, items.get(0).getItemType());
        assertEquals("Q1", items.get(0).getRequirementCode());
        assertEquals("须具备二级资质", items.get(0).getContent());
        assertEquals(TenderAnalysisPort.ITEM_SCORING, items.get(1).getItemType());
        assertEquals("S1", items.get(1).getRequirementCode());
        assertEquals(0, new BigDecimal("10").compareTo(items.get(1).getScoreTotal()));
        assertEquals(TenderAnalysisPort.ITEM_LOGIC_ISSUE, items.get(2).getItemType());
        assertEquals("逻辑问题-1", items.get(2).getRequirementCode());
        assertEquals(TenderAnalysisPort.ITEM_TYPO, items.get(3).getItemType());
        assertEquals("错别字-1", items.get(3).getRequirementCode());
        verify(jobPort).markRunningProgress(101L, 80, "逻辑问题和错别字识别中");
        verify(jobPort).markSuccess(101L, "招标解析完成");
    }

    private TenderAnalysisJobExecutor newExecutor() {
        return new TenderAnalysisJobExecutor(
                jobPort,
                documentAccessPort,
                documentIndexStorePort,
                llmPort,
                checklistBuildPipeline,
                tenderIssueScanPipeline,
                tenderAnalysisPort,
                new ObjectMapper()
        );
    }

    private static JobPort.JobRef job() {
        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(101L);
        job.setOrgId(2L);
        job.setPayloadJson("{\"analysisId\":55,\"documentIds\":[7,8]}");
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
        chunk.setPageNo(1);
        chunk.setContent(content);
        return chunk;
    }

    private static LlmPort.TenderBasicInfoResult basicInfoResult() {
        LlmPort.TenderBasicInfo info = new LlmPort.TenderBasicInfo();
        LlmPort.FieldEvidence tenderer = field("采购人A", "招标人：采购人A", 9001L, 1);
        LlmPort.FieldEvidence agency = field("代理B", "代理机构：代理B", 9001L, 1);
        LlmPort.FieldEvidence budget = field("预算金额100万元", "预算金额100万元", 9001L, 1);
        LlmPort.DeadlineEvidence deadline = new LlmPort.DeadlineEvidence();
        deadline.setText("2026年8月15日17:00");
        deadline.setIso("2026-08-15T09:00:00Z");
        deadline.setQuote("截止时间：2026年8月15日17:00");
        deadline.setChunkId(9001L);
        deadline.setPageNo(1);
        info.setTenderer(tenderer);
        info.setAgency(agency);
        info.setBudgetAmount(budget);
        info.setBidDeadline(deadline);
        info.setProjectIntro(field("简介", "项目简介", 9001L, 1));
        info.setProjectBackground(field("背景", "项目背景", 9001L, 1));
        info.setProjectOverview(field("概况", "项目概况", 9001L, 1));

        LlmPort.TenderBasicInfoResult result = new LlmPort.TenderBasicInfoResult();
        result.setInfo(info);
        result.setRawContent("{\"raw\":true}");
        return result;
    }

    private static LlmPort.FieldEvidence field(String value, String quote, long chunkId, int pageNo) {
        LlmPort.FieldEvidence field = new LlmPort.FieldEvidence();
        field.setValue(value);
        field.setQuote(quote);
        field.setChunkId(chunkId);
        field.setPageNo(pageNo);
        return field;
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
        s.setChunkIdsJson("[9002]");
        s.setEvidenceJson("[{\"fragmentId\":9002,\"page\":1,\"quote\":\"技术方案满分10分\"}]");
        s.setScoreTotal(new BigDecimal("10"));
        s.setScoreSumMatches(Boolean.TRUE);
        s.setSortOrder(1);

        List<RequirementChecklistPort.ItemDraft> items = new ArrayList<>();
        items.add(q);
        items.add(s);
        ChecklistBuildPipeline.PipelineResult result = new ChecklistBuildPipeline.PipelineResult();
        result.setItems(items);
        result.setLlmRawJson("{\"qualification\":{},\"scoring\":{}}");
        return result;
    }

    private static List<TenderAnalysisPort.ItemDraft> issueItems() {
        TenderAnalysisPort.ItemDraft logic = new TenderAnalysisPort.ItemDraft();
        logic.setItemType(TenderAnalysisPort.ITEM_LOGIC_ISSUE);
        logic.setRequirementCode("逻辑问题-1");
        logic.setTitle("业绩门槛与预算不匹配");
        logic.setContent("预算100万元，业绩要求500万元。");
        logic.setChunkIdsJson("[9001,9002]");
        logic.setEvidenceJson("[]");
        logic.setSortOrder(2);

        TenderAnalysisPort.ItemDraft typo = new TenderAnalysisPort.ItemDraft();
        typo.setItemType(TenderAnalysisPort.ITEM_TYPO);
        typo.setRequirementCode("错别字-1");
        typo.setTitle("疑似错别字：解折");
        typo.setContent("建议改为解析。");
        typo.setChunkIdsJson("[9002]");
        typo.setEvidenceJson("[]");
        typo.setSortOrder(3);

        List<TenderAnalysisPort.ItemDraft> items = new ArrayList<>();
        items.add(logic);
        items.add(typo);
        return items;
    }
}
