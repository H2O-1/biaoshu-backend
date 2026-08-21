package com.bidding.review.application;

import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RerankPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.port.VectorRecallPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewComparePipelineTest {

    @Mock
    private EmbeddingPort embeddingPort;
    @Mock
    private VectorRecallPort vectorRecallPort;
    @Mock
    private RerankPort rerankPort;
    @Mock
    private LlmPort llmPort;
    @Mock
    private PackageLookupPort packageLookupPort;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runUsesBidSearchAndProductCompare() {
        stubPackage(80L, "华东智建");
        ReviewComparePipeline pipeline = newPipeline();

        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setId(11L);
        req.setDimensionCode("QUALIFICATION");
        req.setMainType("QUALIFICATION");
        req.setRequirementCode("Q1");
        req.setTitle("资质");
        req.setText("须具备二级资质");
        req.setBidSearchJson("[\"资质证书\",\"二级资质\"]");
        req.setSourceExcerpt("招标原文…");
        req.setMust(true);
        List<RequirementChecklistPort.ItemView> items = new ArrayList<>();
        items.add(req);

        float[] vec = new float[]{0.1f, 0.2f};
        List<float[]> vectors = new ArrayList<>();
        vectors.add(vec);
        when(embeddingPort.embed(anyList())).thenReturn(vectors);

        when(vectorRecallPort.recall(any())).thenAnswer(invocation -> {
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(800L);
            hit.setContent("本公司具备二级资质");
            hit.setDocumentId(80L);
            hit.setFileName("投标.pdf");
            hit.setPackageId(80L);
            hit.setPageNo(2);
            hit.setScore(0.8f);
            List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
            hits.add(hit);
            return hits;
        });

        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        r0.setScore(0.95f);
        List<RerankPort.RerankHit> ranked = new ArrayList<>();
        ranked.add(r0);
        when(rerankPort.rerank(eq("须具备二级资质"), anyList(), anyInt(), eq("bid-instruct")))
                .thenReturn(ranked);

        LlmPort.CompareResult compared = new LlmPort.CompareResult();
        compared.setRequirementId("Q1");
        compared.setTitle("资质");
        compared.setReviewType("响应性审查");
        compared.setStatus(ReviewPort.STATUS_MATCH);
        compared.setVerdict(ReviewPort.VERDICT_PASS);
        compared.setVetoRisk(false);
        compared.setJudgement("已响应");
        LlmPort.BidEvidence ev = new LlmPort.BidEvidence();
        ev.setFragmentId(800L);
        ev.setPage(800);
        ev.setQuote("具备二级资质");
        List<LlmPort.BidEvidence> evidences = new ArrayList<>();
        evidences.add(ev);
        compared.setBidEvidence(evidences);
        compared.setRawContent("{\"status\":\"符合\"}");
        when(llmPort.compareRequirement(any(LlmPort.CompareRequest.class))).thenReturn(compared);

        List<Long> bidPkgs = new ArrayList<>();
        bidPkgs.add(80L);
        ReviewComparePipeline.PipelineResult result =
                pipeline.run(2L, 100L, items, bidPkgs, 30, 6, null);

        assertNull(result.getOverallVerdict());
        assertEquals(1, result.getItems().size());
        assertEquals(ReviewPort.STATUS_MATCH, result.getItems().get(0).getStatus());
        assertEquals("具备二级资质", result.getItems().get(0).getBidExcerpt());
        assertEquals(Integer.valueOf(2), result.getItems().get(0).getPageNo());
        assertEquals("[{\"fragmentId\":800,\"page\":2,\"quote\":\"具备二级资质\"}]",
                result.getItems().get(0).getBidEvidenceJson());
        verify(vectorRecallPort, times(2)).recall(any());
        verify(llmPort).compareRequirement(any(LlmPort.CompareRequest.class));
    }

    @Test
    void noBidSearchSkipsRecallQualificationIsUnknown() {
        stubPackage(99L, "测试公司");
        ReviewComparePipeline pipeline = newPipeline();

        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setId(1L);
        req.setDimensionCode("QUALIFICATION");
        req.setMainType("QUALIFICATION");
        req.setRequirementCode("Q1");
        req.setText("要求");
        req.setMust(true);
        List<RequirementChecklistPort.ItemView> items = new ArrayList<>();
        items.add(req);

        ReviewComparePipeline.PipelineResult result =
                pipeline.run(1L, 1L, items, List.of(99L), 10, 3, null);
        ReviewPort.ItemDraft item = result.getItems().get(0);
        assertEquals(ReviewPort.STATUS_UNKNOWN, item.getStatus());
        assertEquals(ReviewPort.VERDICT_UNCLEAR, item.getVerdict());
        assertNull(item.getBidExcerpt());
        assertEquals(Long.valueOf(99L), item.getPackageId());
        verify(vectorRecallPort, never()).recall(any());
        verify(llmPort, never()).compareRequirement(any(LlmPort.CompareRequest.class));
    }

    @Test
    void placeholderEvidenceClearedToUnknown() {
        stubPackage(80L, "公司A");
        ReviewComparePipeline pipeline = newPipeline();

        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setId(11L);
        req.setMainType("QUALIFICATION");
        req.setRequirementCode("Q2");
        req.setText("须具备 ISO9001");
        req.setBidSearchJson("[\"ISO9001\",\"质量管理体系\"]");
        req.setMust(true);
        List<RequirementChecklistPort.ItemView> items = new ArrayList<>();
        items.add(req);

        float[] vec = new float[]{0.1f};
        List<float[]> vectors = new ArrayList<>();
        vectors.add(vec);
        when(embeddingPort.embed(anyList())).thenReturn(vectors);
        when(vectorRecallPort.recall(any())).thenAnswer(invocation -> {
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(545L);
            hit.setContent("本公司质量管理介绍……");
            hit.setDocumentId(80L);
            hit.setFileName("投标.pdf");
            hit.setPackageId(80L);
            hit.setPageNo(15);
            List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
            hits.add(hit);
            return hits;
        });
        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        List<RerankPort.RerankHit> ranked = new ArrayList<>();
        ranked.add(r0);
        when(rerankPort.rerank(anyString(), anyList(), anyInt(), eq("bid-instruct"))).thenReturn(ranked);

        LlmPort.CompareResult compared = new LlmPort.CompareResult();
        compared.setStatus(ReviewPort.STATUS_MISMATCH);
        compared.setVerdict(ReviewPort.VERDICT_FAIL);
        compared.setJudgement("未提及认证");
        LlmPort.BidEvidence ev = new LlmPort.BidEvidence();
        ev.setFragmentId(545L);
        ev.setPage(15);
        ev.setQuote("无相关摘录");
        List<LlmPort.BidEvidence> evidences = new ArrayList<>();
        evidences.add(ev);
        compared.setBidEvidence(evidences);
        when(llmPort.compareRequirement(any(LlmPort.CompareRequest.class))).thenReturn(compared);

        ReviewPort.ItemDraft item = pipeline.run(2L, 100L, items, List.of(80L), 30, 6, null)
                .getItems().get(0);
        assertEquals(ReviewPort.STATUS_UNKNOWN, item.getStatus());
        assertEquals(ReviewPort.VERDICT_UNCLEAR, item.getVerdict());
        assertNull(item.getBidExcerpt());
        assertNull(item.getChunkId());
        assertTrue(item.getJudgement().contains("无法判断"));
        assertTrue(item.getJudgement().contains("未能绑定可核对的投标原文证据"));
    }

    @Test
    void matchStatusWithUnverifiableEvidenceSyncsJudgementToUnknown() {
        stubPackage(80L, "公司A");
        ReviewComparePipeline pipeline = newPipeline();

        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setId(12L);
        req.setMainType("QUALIFICATION");
        req.setRequirementCode("Q3");
        req.setText("无重大违法记录");
        req.setBidSearchJson("[\"无重大违法\",\"承诺函\"]");
        req.setMust(true);
        List<RequirementChecklistPort.ItemView> items = new ArrayList<>();
        items.add(req);

        when(embeddingPort.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        when(vectorRecallPort.recall(any())).thenAnswer(invocation -> {
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(900L);
            hit.setContent("本公司简介与组织架构……");
            hit.setDocumentId(80L);
            hit.setFileName("投标.pdf");
            hit.setPackageId(80L);
            hit.setPageNo(3);
            return List.of(hit);
        });
        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        when(rerankPort.rerank(anyString(), anyList(), anyInt(), eq("bid-instruct")))
                .thenReturn(List.of(r0));

        LlmPort.CompareResult compared = new LlmPort.CompareResult();
        compared.setStatus(ReviewPort.STATUS_MATCH);
        compared.setVerdict(ReviewPort.VERDICT_PASS);
        compared.setJudgement("符合。投标人在承诺函中声明无重大违法记录。");
        LlmPort.BidEvidence ev = new LlmPort.BidEvidence();
        ev.setFragmentId(900L);
        ev.setPage(3);
        // 语义改写，不在召回块中 → 证据作废
        ev.setQuote("本公司郑重承诺近三年无重大违法记录");
        compared.setBidEvidence(List.of(ev));
        when(llmPort.compareRequirement(any(LlmPort.CompareRequest.class))).thenReturn(compared);

        ReviewPort.ItemDraft item = pipeline.run(2L, 100L, items, List.of(80L), 30, 6, null)
                .getItems().get(0);
        assertEquals(ReviewPort.STATUS_UNKNOWN, item.getStatus());
        assertEquals(ReviewPort.VERDICT_UNCLEAR, item.getVerdict());
        assertNull(item.getBidExcerpt());
        assertTrue(item.getJudgement().contains("符合"));
        assertTrue(item.getJudgement().contains("无法判断"));
        assertEquals(item.getJudgement(), item.getReason());
    }

    @Test
    void whitespaceOnlyDifferenceInQuoteStillMatches() {
        stubPackage(80L, "公司A");
        ReviewComparePipeline pipeline = newPipeline();

        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setId(13L);
        req.setMainType("QUALIFICATION");
        req.setRequirementCode("Q4");
        req.setText("须具备二级资质");
        req.setBidSearchJson("[\"二级资质\"]");
        req.setMust(true);

        when(embeddingPort.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        when(vectorRecallPort.recall(any())).thenAnswer(invocation -> {
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(901L);
            hit.setContent("本公司具备二级资质证书");
            hit.setDocumentId(80L);
            hit.setFileName("投标.pdf");
            hit.setPackageId(80L);
            hit.setPageNo(1);
            return List.of(hit);
        });
        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        when(rerankPort.rerank(anyString(), anyList(), anyInt(), eq("bid-instruct")))
                .thenReturn(List.of(r0));

        LlmPort.CompareResult compared = new LlmPort.CompareResult();
        compared.setStatus(ReviewPort.STATUS_MATCH);
        compared.setVerdict(ReviewPort.VERDICT_PASS);
        compared.setJudgement("符合。");
        LlmPort.BidEvidence ev = new LlmPort.BidEvidence();
        ev.setFragmentId(901L);
        ev.setPage(1);
        ev.setQuote("具备 二级 资质"); // 仅多了空格
        compared.setBidEvidence(List.of(ev));
        when(llmPort.compareRequirement(any(LlmPort.CompareRequest.class))).thenReturn(compared);

        ReviewPort.ItemDraft item = pipeline.run(2L, 100L, items(req), List.of(80L), 30, 6, null)
                .getItems().get(0);
        assertEquals(ReviewPort.STATUS_MATCH, item.getStatus());
        assertEquals(ReviewPort.VERDICT_PASS, item.getVerdict());
        assertEquals("具备二级资质", item.getBidExcerpt());
    }

    @Test
    void quoteInContentAllowsWhitespaceNormalizationOnly() {
        assertTrue(ReviewComparePipeline.quoteInContent("具备二级资质", "具备 二级 资质"));
        assertFalse(ReviewComparePipeline.quoteInContent("具备二级资质", "具备三级资质"));
        assertEquals("具备二级资质",
                ReviewComparePipeline.canonicalQuoteFromContent("本公司具备二级资质证书", "具备 二级 资质"));
    }

    @Test
    void resolveRecallQueriesUsesOnlyBidSearch() {
        ReviewComparePipeline pipeline = newPipeline();
        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setText("正文很长但不应作为召回词");
        req.setBidSearchJson("[\"词A\",\"词B\"]");
        List<String> qs = pipeline.resolveRecallQueries(req);
        assertEquals(2, qs.size());
        assertEquals("词A", qs.get(0));
    }

    @Test
    void resolveRecallQueriesEmptyWithoutBidSearch() {
        ReviewComparePipeline pipeline = newPipeline();
        RequirementChecklistPort.ItemView req = new RequirementChecklistPort.ItemView();
        req.setText("仅有正文");
        req.setBidSearchJson(null);
        assertEquals(0, pipeline.resolveRecallQueries(req).size());
    }

    @Test
    void runSimulationUsesInternalBidPackageWithoutProjectFilter() {
        stubPackage(88L, "模拟投标包");
        ReviewComparePipeline pipeline = newPipeline();

        RequirementChecklistPort.ItemDraft req = new RequirementChecklistPort.ItemDraft();
        req.setMainType(ReviewPort.MAIN_SCORING);
        req.setRequirementCode("S1");
        req.setTitle("技术方案");
        req.setText("技术方案满分10分");
        req.setBidSearchJson("[\"技术方案\"]");
        req.setScoreTotal(new BigDecimal("10.00"));

        when(embeddingPort.embed(anyList())).thenReturn(List.of(new float[]{0.1f}));
        when(vectorRecallPort.recall(any())).thenAnswer(invocation -> {
            VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
            hit.setChunkId(8101L);
            hit.setContent("技术方案覆盖总体架构、实施计划和运维保障。");
            hit.setDocumentId(801L);
            hit.setFileName("投标文件.pdf");
            hit.setPackageId(88L);
            hit.setPageNo(6);
            return List.of(hit);
        });
        RerankPort.RerankHit r0 = new RerankPort.RerankHit();
        r0.setIndex(0);
        when(rerankPort.rerank(eq("技术方案满分10分"), anyList(), anyInt(), eq("bid-instruct")))
                .thenReturn(List.of(r0));

        LlmPort.CompareResult compared = new LlmPort.CompareResult();
        compared.setRequirementId("S1");
        compared.setTitle("技术方案");
        compared.setReviewType("评分建议");
        compared.setSuggestedScore(new BigDecimal("8.00"));
        compared.setFullScore(new BigDecimal("10.00"));
        compared.setJudgement("方案较完整。");
        LlmPort.BidEvidence ev = new LlmPort.BidEvidence();
        ev.setFragmentId(8101L);
        ev.setPage(6);
        ev.setQuote("技术方案覆盖总体架构、实施计划和运维保障");
        compared.setBidEvidence(List.of(ev));
        when(llmPort.compareRequirement(any(LlmPort.CompareRequest.class))).thenReturn(compared);

        ReviewComparePipeline.PipelineResult result =
                pipeline.runSimulation(2L, List.of(req), 88L, 30, 6, null);

        ReviewPort.ItemDraft item = result.getItems().get(0);
        assertEquals(Long.valueOf(88L), item.getPackageId());
        assertEquals("S1", item.getRequirementCode());
        assertEquals(new BigDecimal("8.00"), item.getSuggestedScore());
        assertEquals(new BigDecimal("10.00"), item.getFullScore());
        ArgumentCaptor<VectorRecallPort.RecallQuery> queryCaptor =
                ArgumentCaptor.forClass(VectorRecallPort.RecallQuery.class);
        verify(vectorRecallPort).recall(queryCaptor.capture());
        assertEquals(0L, queryCaptor.getValue().getProjectId());
        assertEquals(List.of(88L), queryCaptor.getValue().getPackageIds());
    }

    private ReviewComparePipeline newPipeline() {
        return new ReviewComparePipeline(
                embeddingPort, vectorRecallPort, null, rerankPort, llmPort, packageLookupPort,
                objectMapper, 30, 6, "bid-instruct", false, 0, 60, 4);
    }

    private static List<RequirementChecklistPort.ItemView> items(RequirementChecklistPort.ItemView req) {
        List<RequirementChecklistPort.ItemView> list = new ArrayList<>();
        list.add(req);
        return list;
    }

    private void stubPackage(long id, String name) {
        PackageLookupPort.PackageRef ref = new PackageLookupPort.PackageRef();
        ref.setId(id);
        ref.setName(name);
        when(packageLookupPort.findActiveById(id)).thenReturn(ref);
    }
}
