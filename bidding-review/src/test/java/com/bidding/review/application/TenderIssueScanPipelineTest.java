package com.bidding.review.application;

import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.common.port.LlmPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenderIssueScanPipelineTest {

    @Mock
    private LlmPort llmPort;

    @Test
    void scanReturnsEmptyWhenNoChunks() {
        TenderIssueScanPipeline pipeline = newPipeline();

        List<TenderAnalysisPort.ItemDraft> result = pipeline.scan(new ArrayList<>());

        assertEquals(0, result.size());
        verify(llmPort, never()).scanTenderIssues(anyString());
    }

    @Test
    void scanConvertsLogicIssueAndTypoToItemDrafts() {
        TenderIssueScanPipeline pipeline = newPipeline();
        when(llmPort.scanTenderIssues(anyString())).thenReturn(scanResult());

        List<TenderAnalysisPort.ItemDraft> result = pipeline.scan(chunks(2));

        assertEquals(2, result.size());
        TenderAnalysisPort.ItemDraft logic = result.get(0);
        assertEquals(TenderAnalysisPort.ITEM_LOGIC_ISSUE, logic.getItemType());
        assertEquals("逻辑问题-1", logic.getRequirementCode());
        assertEquals("业绩门槛与项目预算规模不匹配", logic.getTitle());
        assertEquals("THRESHOLD_INCONSISTENCY", logic.getSubType());
        assertEquals(1, logic.getPageNo());
        assertEquals("[9001,9002]", logic.getChunkIdsJson());
        assertTrue(logic.getEvidenceJson().contains("\"severity\":\"MEDIUM\""));

        TenderAnalysisPort.ItemDraft typo = result.get(1);
        assertEquals(TenderAnalysisPort.ITEM_TYPO, typo.getItemType());
        assertEquals("错别字-1", typo.getRequirementCode());
        assertEquals("疑似错别字：解折", typo.getTitle());
        assertEquals("解折失败，请重新解析", typo.getSourceExcerpt());
        assertTrue(typo.getEvidenceJson().contains("\"wrongText\":\"解折\""));
    }

    @Test
    void scanDedupesCandidatesAndMergesEvidence() {
        TenderIssueScanPipeline pipeline = newPipeline();
        LlmPort.TenderIssueScanResult first = scanResult();
        LlmPort.TenderIssueScanResult duplicate = scanResult();
        duplicate.getLogicIssues().get(0).getEvidence().add(evidence(9010L, 6, "补充证据"));
        when(llmPort.scanTenderIssues(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.contains("chunkId=9009") ? duplicate : first;
        });

        List<TenderAnalysisPort.ItemDraft> result = pipeline.scan(chunks(9));

        assertEquals(2, result.size());
        assertEquals("逻辑问题-1", result.get(0).getRequirementCode());
        assertEquals("错别字-1", result.get(1).getRequirementCode());
        assertTrue(result.get(0).getChunkIdsJson().contains("9010"));

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmPort, org.mockito.Mockito.times(2)).scanTenderIssues(inputCaptor.capture());
        assertTrue(containsChunk(inputCaptor.getAllValues(), "chunkId=9001"));
        assertTrue(containsChunk(inputCaptor.getAllValues(), "chunkId=9009"));
    }

    private TenderIssueScanPipeline newPipeline() {
        return new TenderIssueScanPipeline(llmPort, new ObjectMapper());
    }

    private static boolean containsChunk(List<String> texts, String chunkIdText) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i) != null && texts.get(i).contains(chunkIdText)) {
                return true;
            }
        }
        return false;
    }

    private static LlmPort.TenderIssueScanResult scanResult() {
        LlmPort.LogicIssueCandidate logic = new LlmPort.LogicIssueCandidate();
        logic.setTitle("业绩门槛与项目预算规模不匹配");
        logic.setSeverity("MEDIUM");
        logic.setIssueType("THRESHOLD_INCONSISTENCY");
        logic.setProblem("项目预算金额为1280万元，但类似业绩要求达到5000万元。");
        logic.setSuggestion("调整为与项目规模相称的合理区间。");
        List<LlmPort.IssueEvidence> logicEvidence = new ArrayList<>();
        logicEvidence.add(evidence(9001L, 1, "预算金额为人民币 1280 万元"));
        logicEvidence.add(evidence(9002L, 5, "近三年需具有 5000 万元以上类似项目业绩"));
        logic.setEvidence(logicEvidence);

        LlmPort.TypoCandidate typo = new LlmPort.TypoCandidate();
        typo.setWrongText("解折");
        typo.setSuggestedText("解析");
        typo.setContext("解折失败，请重新解析");
        typo.setConfidence("HIGH");
        typo.setSuggestion("建议改为“解析”。");
        List<LlmPort.IssueEvidence> typoEvidence = new ArrayList<>();
        typoEvidence.add(evidence(9003L, 6, "解折失败，请重新解析"));
        typo.setEvidence(typoEvidence);

        LlmPort.TenderIssueScanResult result = new LlmPort.TenderIssueScanResult();
        List<LlmPort.LogicIssueCandidate> logicIssues = new ArrayList<>();
        logicIssues.add(logic);
        result.setLogicIssues(logicIssues);
        List<LlmPort.TypoCandidate> typos = new ArrayList<>();
        typos.add(typo);
        result.setTypos(typos);
        result.setRawContent("{\"logicIssues\":[],\"typos\":[]}");
        return result;
    }

    private static LlmPort.IssueEvidence evidence(long chunkId, int pageNo, String quote) {
        LlmPort.IssueEvidence evidence = new LlmPort.IssueEvidence();
        evidence.setChunkId(chunkId);
        evidence.setPageNo(pageNo);
        evidence.setQuote(quote);
        return evidence;
    }

    private static List<DocumentIndexStorePort.ChunkView> chunks(int count) {
        List<DocumentIndexStorePort.ChunkView> chunks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            DocumentIndexStorePort.ChunkView chunk = new DocumentIndexStorePort.ChunkView();
            chunk.setChunkId(9000L + i);
            chunk.setDocumentId(7L);
            chunk.setFileName("招标文件.pdf");
            chunk.setChunkIndex(i - 1);
            chunk.setPageNo(i);
            chunk.setContent("第 " + i + " 个切块内容");
            chunks.add(chunk);
        }
        return chunks;
    }
}
