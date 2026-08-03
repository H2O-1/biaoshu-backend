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
    void buildRecallsAllDimsThenOneChecklistExtract() {
        ChecklistBuildPipeline pipeline = new ChecklistBuildPipeline(
                embeddingPort, vectorRecallPort, rerankPort, llmPort,
                new ObjectMapper(), 40, 8, "instruct");

        ReviewDimensionPort.Dimension dim = new ReviewDimensionPort.Dimension();
        dim.setCode("QUALIFICATION");
        dim.setName("资格与资质");
        List<String> queries = new ArrayList<>();
        queries.add("资格条件");
        queries.add("资质等级");
        dim.setQueries(queries);
        List<ReviewDimensionPort.Dimension> dims = new ArrayList<>();
        dims.add(dim);

        float[] vec = new float[]{0.1f, 0.2f};
        List<float[]> vectors = new ArrayList<>();
        vectors.add(vec);
        when(embeddingPort.embed(anyList())).thenReturn(vectors);

        VectorRecallPort.RecallHit h0 = hit(1L, "要求具备二级资质");
        VectorRecallPort.RecallHit h1 = hit(2L, "另一段");
        List<VectorRecallPort.RecallHit> hits1 = new ArrayList<>();
        hits1.add(h0);
        List<VectorRecallPort.RecallHit> hits2 = new ArrayList<>();
        hits2.add(h0);
        hits2.add(h1);
        when(vectorRecallPort.recall(any())).thenReturn(hits1, hits2);

        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        r0.setScore(0.9f);
        List<RerankPort.RerankHit> ranked = new ArrayList<>();
        ranked.add(r0);
        when(rerankPort.rerank(anyString(), anyList(), anyInt(), eq("instruct"))).thenReturn(ranked);

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
        LlmPort.ExtractedRequirement e2 = new LlmPort.ExtractedRequirement();
        e2.setText("  要求具备二级资质  ");
        e2.setMainType("QUALIFICATION");
        e2.setMust(true);
        List<LlmPort.ExtractedRequirement> extracted = new ArrayList<>();
        extracted.add(e1);
        extracted.add(e2);
        LlmPort.ExtractChecklistResult extractResult = new LlmPort.ExtractChecklistResult();
        extractResult.setItems(extracted);
        extractResult.setRawContent("{\"items\":[...]}");
        when(llmPort.extractChecklist(anyString())).thenReturn(extractResult);

        List<Long> packageIds = new ArrayList<>();
        packageIds.add(10L);
        ChecklistBuildPipeline.PipelineResult result = pipeline.build(
                1L, 100L, dims, packageIds, null, 40, 8, null);
        List<RequirementChecklistPort.ItemDraft> items = result.getItems();

        assertEquals(1, items.size());
        assertEquals("QUALIFICATION", items.get(0).getMainType());
        assertEquals("QUALIFICATION", items.get(0).getDimensionCode());
        assertEquals("要求具备二级资质", items.get(0).getText());
        assertEquals("资质", items.get(0).getTitle());
        assertEquals("Q1", items.get(0).getRequirementCode());
        assertEquals("{\"items\":[...]}", result.getLlmRawJson());
        verify(llmPort, times(1)).extractChecklist(anyString());
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
