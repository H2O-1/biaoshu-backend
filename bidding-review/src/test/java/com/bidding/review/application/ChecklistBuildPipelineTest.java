package com.bidding.review.application;

import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.RerankPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChecklistBuildPipelineTest {

    @Mock
    private EmbeddingPort embeddingPort;
    @Mock
    private VectorRecallPort vectorRecallPort;
    @Mock
    private RerankPort rerankPort;
    @Mock
    private LlmPort llmPort;

    @Test
    void buildRecallsAllDimsThenSplitParallelExtract() {
        ChecklistBuildPipeline pipeline = newPipeline();
        stubRecallAndRerank();

        LlmPort.ExtractedRequirement e1 = new LlmPort.ExtractedRequirement();
        e1.setText("要求具备二级资质");
        e1.setMainType("QUALIFICATION");
        e1.setTitle("资质");
        e1.setMust(true);
        List<Long> chunkIds = new ArrayList<>();
        chunkIds.add(1L);
        e1.setChunkIds(chunkIds);
        List<String> bidSearch = new ArrayList<>();
        bidSearch.add("资质证书");
        e1.setBidSearch(bidSearch);
        LlmPort.ExtractedRequirement eDup = new LlmPort.ExtractedRequirement();
        eDup.setText("  要求具备二级资质  ");
        eDup.setMainType("QUALIFICATION");
        eDup.setMust(true);
        List<LlmPort.ExtractedRequirement> qualItems = new ArrayList<>();
        qualItems.add(e1);
        qualItems.add(eDup);
        LlmPort.ExtractChecklistResult qualResult = new LlmPort.ExtractChecklistResult();
        qualResult.setItems(qualItems);
        qualResult.setRawContent("{\"qualificationRequirements\":[]}");

        LlmPort.ExtractedRequirement s1 = new LlmPort.ExtractedRequirement();
        s1.setText("技术方案满分15分");
        s1.setMainType("SCORING");
        s1.setTitle("技术方案");
        List<Long> sChunkIds = new ArrayList<>();
        sChunkIds.add(1L);
        s1.setChunkIds(sChunkIds);
        List<LlmPort.ExtractedRequirement> scoreItems = new ArrayList<>();
        scoreItems.add(s1);
        LlmPort.ExtractChecklistResult scoreResult = new LlmPort.ExtractChecklistResult();
        scoreResult.setItems(scoreItems);
        scoreResult.setRawContent("{\"scoring\":{}}");

        when(llmPort.extractQualificationChecklist(anyString())).thenReturn(qualResult);
        when(llmPort.extractScoringChecklist(anyString())).thenReturn(scoreResult);

        LlmPort.BidSearchRow qRow = new LlmPort.BidSearchRow();
        qRow.setId("Q1");
        List<String> qQueries = new ArrayList<>();
        qQueries.add("资质证书");
        qQueries.add("资质扫描件");
        qRow.setQueries(qQueries);
        LlmPort.BidSearchRow sRow = new LlmPort.BidSearchRow();
        sRow.setId("S1");
        List<String> sQueries = new ArrayList<>();
        sQueries.add("技术方案");
        sRow.setQueries(sQueries);
        LlmPort.BidSearchResult bidSearchResult = new LlmPort.BidSearchResult();
        List<LlmPort.BidSearchRow> rows = new ArrayList<>();
        rows.add(qRow);
        rows.add(sRow);
        bidSearchResult.setRows(rows);
        bidSearchResult.setRawContent("{\"rows\":[]}");
        when(llmPort.generateBidSearch(anyString())).thenReturn(bidSearchResult);

        LlmPort.EvidenceRow evQ = new LlmPort.EvidenceRow();
        evQ.setId("Q1");
        evQ.setFragmentId(1L);
        evQ.setPage(3);
        evQ.setQuote("要求具备二级资质");
        LlmPort.EvidenceRow evS = new LlmPort.EvidenceRow();
        evS.setId("S1");
        evS.setFragmentId(1L);
        evS.setPage(3);
        evS.setQuote("技术方案满分15分");
        LlmPort.EvidenceResult evidenceResult = new LlmPort.EvidenceResult();
        List<LlmPort.EvidenceRow> evRows = new ArrayList<>();
        evRows.add(evQ);
        evRows.add(evS);
        evidenceResult.setRows(evRows);
        evidenceResult.setRawContent("{\"rows\":[]}");
        when(llmPort.generateEvidence(anyString(), anyString())).thenReturn(evidenceResult);

        List<Long> packageIds = new ArrayList<>();
        packageIds.add(10L);
        ChecklistBuildPipeline.PipelineResult result = pipeline.build(
                1L, 100L, sampleDims(), packageIds, null, 40, 8, null);
        List<RequirementChecklistPort.ItemDraft> items = result.getItems();

        assertEquals(2, items.size());
        assertEquals("QUALIFICATION", items.get(0).getMainType());
        assertEquals("QUALIFICATION", items.get(0).getDimensionCode());
        assertEquals("要求具备二级资质", items.get(0).getText());
        assertEquals("资质", items.get(0).getTitle());
        assertEquals("Q1", items.get(0).getRequirementCode());
        assertEquals("[\"资质证书\",\"资质扫描件\"]", items.get(0).getBidSearchJson());
        assertTrue(items.get(0).getEvidenceJson().contains("\"fragmentId\":1"));
        assertEquals("要求具备二级资质", items.get(0).getSourceExcerpt());
        assertEquals(Integer.valueOf(3), items.get(0).getPageNo());
        assertEquals("SCORING", items.get(1).getMainType());
        assertEquals("S1", items.get(1).getRequirementCode());
        assertEquals("[\"技术方案\"]", items.get(1).getBidSearchJson());
        assertTrue(result.getLlmRawJson().contains("qualification"));
        assertTrue(result.getLlmRawJson().contains("scoring"));
        assertTrue(result.getLlmRawJson().contains("bidSearch"));
        assertTrue(result.getLlmRawJson().contains("evidence"));
        verify(llmPort, times(1)).extractQualificationChecklist(anyString());
        verify(llmPort, times(1)).extractScoringChecklist(anyString());
        verify(llmPort, times(1)).generateBidSearch(anyString());
        verify(llmPort, times(1)).generateEvidence(anyString(), anyString());
    }

    @Test
    void buildSplitFailsWhenOneSideThrows() {
        ChecklistBuildPipeline pipeline = newPipeline();
        stubRecallAndRerank();

        LlmPort.ExtractChecklistResult qualResult = new LlmPort.ExtractChecklistResult();
        qualResult.setItems(new ArrayList<>());
        qualResult.setRawContent("{}");
        when(llmPort.extractQualificationChecklist(anyString())).thenReturn(qualResult);
        when(llmPort.extractScoringChecklist(anyString()))
                .thenThrow(new IllegalStateException("评分 LLM 超时"));

        List<Long> packageIds = new ArrayList<>();
        packageIds.add(10L);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                pipeline.build(1L, 100L, sampleDims(), packageIds, null, 40, 8, null));
        assertEquals("评分 LLM 超时", ex.getMessage());
    }

    @Test
    void mergeSplitResultsOrdersQualThenScore() {
        LlmPort.ExtractedRequirement q = new LlmPort.ExtractedRequirement();
        q.setText("资格");
        LlmPort.ExtractChecklistResult qual = new LlmPort.ExtractChecklistResult();
        List<LlmPort.ExtractedRequirement> qItems = new ArrayList<>();
        qItems.add(q);
        qual.setItems(qItems);
        qual.setRawContent("{\"qualificationRequirements\":[]}");

        LlmPort.ExtractedRequirement s = new LlmPort.ExtractedRequirement();
        s.setText("评分");
        LlmPort.ExtractChecklistResult score = new LlmPort.ExtractChecklistResult();
        List<LlmPort.ExtractedRequirement> sItems = new ArrayList<>();
        sItems.add(s);
        score.setItems(sItems);
        score.setRawContent("{\"scoring\":{}}");

        ChecklistBuildPipeline.LlmExtractBundle bundle =
                ChecklistBuildPipeline.mergeSplitResults(qual, score);
        assertEquals(2, bundle.items.size());
        assertEquals("QUALIFICATION", bundle.items.get(0).getMainType());
        assertEquals("SCORING", bundle.items.get(1).getMainType());
        assertEquals(
                "{\"qualification\":{\"qualificationRequirements\":[]},\"scoring\":{\"scoring\":{}}}",
                bundle.rawJson);
    }

    private ChecklistBuildPipeline newPipeline() {
        return new ChecklistBuildPipeline(
                embeddingPort, vectorRecallPort, null, rerankPort, llmPort,
                new ObjectMapper(), 40, 8, "instruct", false, 0, 60, 10);
    }

    private List<ReviewDimensionPort.Dimension> sampleDims() {
        ReviewDimensionPort.Dimension dim = new ReviewDimensionPort.Dimension();
        dim.setCode("QUALIFICATION");
        dim.setName("资格与资质");
        List<String> queries = new ArrayList<>();
        queries.add("资格条件");
        queries.add("资质等级");
        dim.setQueries(queries);
        List<ReviewDimensionPort.Dimension> dims = new ArrayList<>();
        dims.add(dim);
        return dims;
    }

    private void stubRecallAndRerank() {
        float[] vec = new float[]{0.1f, 0.2f};
        when(embeddingPort.embed(anyList())).thenAnswer(invocation -> {
            List<?> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(vec);
            }
            return vectors;
        });

        VectorRecallPort.RecallHit h0 = hit(1L, "要求具备二级资质");
        VectorRecallPort.RecallHit h1 = hit(2L, "另一段");
        when(vectorRecallPort.recallMulti(any())).thenAnswer(invocation -> {
            VectorRecallPort.RecallMultiQuery mq = invocation.getArgument(0);
            int qCount = mq.getQueryEmbeddings() == null ? 0 : mq.getQueryEmbeddings().size();
            List<List<VectorRecallPort.RecallHit>> perQuery = new ArrayList<>();
            for (int i = 0; i < qCount; i++) {
                List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
                hits.add(h0);
                if (i > 0) {
                    hits.add(h1);
                }
                perQuery.add(hits);
            }
            VectorRecallPort.RecallMultiResult result = new VectorRecallPort.RecallMultiResult();
            result.setPerQueryHits(perQuery);
            result.setCandidateCount(2);
            result.setLoadMs(1L);
            result.setScoreMs(1L);
            return result;
        });

        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        r0.setScore(0.9f);
        List<RerankPort.RerankHit> ranked = new ArrayList<>();
        ranked.add(r0);
        when(rerankPort.rerank(anyString(), anyList(), anyInt(), eq("instruct"))).thenReturn(ranked);
    }

    @Test
    void collectAllQueryTextsSkipsBlank() {
        ReviewDimensionPort.Dimension d1 = new ReviewDimensionPort.Dimension();
        List<String> q1 = new ArrayList<>();
        q1.add("资格条件");
        q1.add("  ");
        q1.add("资质等级");
        d1.setQueries(q1);
        ReviewDimensionPort.Dimension d2 = new ReviewDimensionPort.Dimension();
        List<String> q2 = new ArrayList<>();
        q2.add("评分标准");
        d2.setQueries(q2);
        List<ReviewDimensionPort.Dimension> dims = new ArrayList<>();
        dims.add(d1);
        dims.add(d2);
        List<String> texts = ChecklistBuildPipeline.collectAllQueryTexts(dims);
        assertEquals(3, texts.size());
        assertEquals("资格条件", texts.get(0));
        assertEquals("资质等级", texts.get(1));
        assertEquals("评分标准", texts.get(2));
    }

    @Test
    void formatChecklistItemsForBidSearchUsesCodeAndFields() {
        RequirementChecklistPort.ItemDraft q = new RequirementChecklistPort.ItemDraft();
        q.setRequirementCode("Q1");
        q.setSubType("CERTIFICATE");
        q.setTitle("资质");
        q.setText("须具备二级资质");
        List<RequirementChecklistPort.ItemDraft> list = new ArrayList<>();
        list.add(q);
        assertEquals("Q1 | CERTIFICATE | 资质 | 须具备二级资质",
                ChecklistBuildPipeline.formatChecklistItemsForBidSearch(list));
    }

    @Test
    void buildFailsWhenBidSearchThrows() {
        ChecklistBuildPipeline pipeline = newPipeline();
        stubRecallAndRerank();

        LlmPort.ExtractedRequirement e1 = new LlmPort.ExtractedRequirement();
        e1.setText("要求具备二级资质");
        e1.setMainType("QUALIFICATION");
        List<LlmPort.ExtractedRequirement> qualItems = new ArrayList<>();
        qualItems.add(e1);
        LlmPort.ExtractChecklistResult qualResult = new LlmPort.ExtractChecklistResult();
        qualResult.setItems(qualItems);
        qualResult.setRawContent("{}");
        LlmPort.ExtractChecklistResult scoreResult = new LlmPort.ExtractChecklistResult();
        scoreResult.setItems(new ArrayList<>());
        scoreResult.setRawContent("{}");
        when(llmPort.extractQualificationChecklist(anyString())).thenReturn(qualResult);
        when(llmPort.extractScoringChecklist(anyString())).thenReturn(scoreResult);
        when(llmPort.generateBidSearch(anyString()))
                .thenThrow(new IllegalStateException("bidSearch LLM 超时"));

        List<Long> packageIds = new ArrayList<>();
        packageIds.add(10L);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                pipeline.build(1L, 100L, sampleDims(), packageIds, null, 40, 8, null));
        assertEquals("bidSearch LLM 超时", ex.getMessage());
    }

    @Test
    void buildFailsWhenEvidenceThrows() {
        ChecklistBuildPipeline pipeline = newPipeline();
        stubRecallAndRerank();

        LlmPort.ExtractedRequirement e1 = new LlmPort.ExtractedRequirement();
        e1.setText("要求具备二级资质");
        e1.setMainType("QUALIFICATION");
        List<Long> chunkIds = new ArrayList<>();
        chunkIds.add(1L);
        e1.setChunkIds(chunkIds);
        List<LlmPort.ExtractedRequirement> qualItems = new ArrayList<>();
        qualItems.add(e1);
        LlmPort.ExtractChecklistResult qualResult = new LlmPort.ExtractChecklistResult();
        qualResult.setItems(qualItems);
        qualResult.setRawContent("{}");
        LlmPort.ExtractChecklistResult scoreResult = new LlmPort.ExtractChecklistResult();
        scoreResult.setItems(new ArrayList<>());
        scoreResult.setRawContent("{}");
        when(llmPort.extractQualificationChecklist(anyString())).thenReturn(qualResult);
        when(llmPort.extractScoringChecklist(anyString())).thenReturn(scoreResult);
        LlmPort.BidSearchResult bidSearchResult = new LlmPort.BidSearchResult();
        bidSearchResult.setRows(new ArrayList<>());
        bidSearchResult.setRawContent("{\"rows\":[]}");
        when(llmPort.generateBidSearch(anyString())).thenReturn(bidSearchResult);
        when(llmPort.generateEvidence(anyString(), anyString()))
                .thenThrow(new IllegalStateException("evidence LLM 超时"));

        List<Long> packageIds = new ArrayList<>();
        packageIds.add(10L);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                pipeline.build(1L, 100L, sampleDims(), packageIds, null, 40, 8, null));
        assertEquals("evidence LLM 超时", ex.getMessage());
    }

    @Test
    void assignRequirementCodesByMainType() {
        RequirementChecklistPort.ItemDraft q = new RequirementChecklistPort.ItemDraft();
        q.setMainType(ChecklistBuildPipeline.MAIN_QUALIFICATION);
        RequirementChecklistPort.ItemDraft s = new RequirementChecklistPort.ItemDraft();
        s.setMainType(ChecklistBuildPipeline.MAIN_SCORING);
        RequirementChecklistPort.ItemDraft q2 = new RequirementChecklistPort.ItemDraft();
        q2.setMainType(ChecklistBuildPipeline.MAIN_QUALIFICATION);
        List<RequirementChecklistPort.ItemDraft> list = new ArrayList<>();
        list.add(q);
        list.add(s);
        list.add(q2);
        ChecklistBuildPipeline.assignRequirementCodes(list);
        assertEquals("Q1", list.get(0).getRequirementCode());
        assertEquals("S1", list.get(1).getRequirementCode());
        assertEquals("Q2", list.get(2).getRequirementCode());
    }

    @Test
    void normalizeCollapsesWhitespace() {
        assertEquals("a b", ChecklistBuildPipeline.normalizeText("  a   b  "));
    }

    @Test
    void buildCompositeRerankQueryJoinsNameAndAllQueries() {
        ReviewDimensionPort.Dimension dim = new ReviewDimensionPort.Dimension();
        dim.setName("资格与资质");
        List<String> queries = new ArrayList<>();
        queries.add("资格条件");
        queries.add("  ");
        queries.add("资质等级");
        queries.add("类似业绩");
        dim.setQueries(queries);
        assertEquals(
                "资格与资质：资格条件；资质等级；类似业绩",
                ChecklistBuildPipeline.buildCompositeRerankQuery(dim));
    }

    @Test
    void buildAllDimensionsRerankQueryJoinsDims() {
        ReviewDimensionPort.Dimension d1 = new ReviewDimensionPort.Dimension();
        d1.setName("资格与资质");
        List<String> q1 = new ArrayList<>();
        q1.add("资格条件");
        d1.setQueries(q1);
        ReviewDimensionPort.Dimension d2 = new ReviewDimensionPort.Dimension();
        d2.setName("评分");
        List<String> q2 = new ArrayList<>();
        q2.add("技术方案");
        d2.setQueries(q2);
        List<ReviewDimensionPort.Dimension> dims = new ArrayList<>();
        dims.add(d1);
        dims.add(d2);
        assertEquals(
                "资格与资质：资格条件。评分：技术方案",
                ChecklistBuildPipeline.buildAllDimensionsRerankQuery(dims));
    }

    private static VectorRecallPort.RecallHit hit(long chunkId, String content) {
        VectorRecallPort.RecallHit h = new VectorRecallPort.RecallHit();
        h.setChunkId(chunkId);
        h.setContent(content);
        h.setDocumentId(1L);
        h.setFileName("a.pdf");
        h.setPackageId(10L);
        h.setPackageType("TENDER");
        h.setScore(0.5f);
        return h;
    }
}
