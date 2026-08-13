package com.bidding.ai.infrastructure.llm;

import com.bidding.common.port.LlmPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLlmPortTest {

    private HttpServer server;
    private HttpLlmPort llmPort;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String content;
            if (request.contains("logicIssues") && request.contains("typos")) {
                content = "{\"logicIssues\":[],\"typos\":[]}";
            } else {
                content = "{"
                        + "\"qualificationRequirements\":[{"
                        + "\"id\":\"Q1\","
                        + "\"title\":\"资质\","
                        + "\"mainType\":\"资格要求\","
                        + "\"subType\":\"资质证书\","
                        + "\"content\":\"须具备二级资质\","
                        + "\"bidSearch\":{\"queries\":[\"资质证书\",\"资质扫描件\"]},"
                        + "\"evidence\":[{\"fragmentId\":11,\"page\":3,\"quote\":\"具备二级\"}]"
                        + "}],"
                        + "\"scoring\":{\"totalScore\":null,\"scoreSumMatches\":null,\"items\":[]}"
                        + "}";
            }
            String body = "{\"choices\":[{\"message\":{\"content\":"
                    + new ObjectMapper().writeValueAsString(content)
                    + "}}]}";
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        llmPort = new HttpLlmPort(
                new ObjectMapper(),
                "http://127.0.0.1:" + port,
                "test-key",
                "qwen3.7-plus",
                "",
                "",
                "",
                "",
                "",
                3000L,
                10000L
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parseChecklistParsesLegacyItems() throws Exception {
        String json = "{"
                + "\"items\":[{"
                + "\"title\":\"资质等级\","
                + "\"mainType\":\"资格要求\","
                + "\"subType\":\"证书\","
                + "\"content\":\"须具备二级资质\","
                + "\"bidSearch\":[\"资质证书\",\"二级资质\"],"
                + "\"evidence\":[{\"chunkId\":11,\"page\":3,\"quote\":\"具备二级\"}],"
                + "\"scoreTotal\":null"
                + "}]}";
        List<LlmPort.ExtractedRequirement> items = llmPort.parseChecklistFromContent(json);
        assertEquals(1, items.size());
        assertEquals("须具备二级资质", items.get(0).getText());
        assertEquals("QUALIFICATION", items.get(0).getMainType());
        assertEquals("CERTIFICATE", items.get(0).getSubType());
        assertEquals("资质等级", items.get(0).getTitle());
        assertEquals(2, items.get(0).getBidSearch().size());
        assertEquals(11L, items.get(0).getChunkIds().get(0).longValue());
        assertTrue(items.get(0).isMust());
    }

    @Test
    void parseChecklistProductShapeFlattens() throws Exception {
        String json = "{"
                + "\"qualificationRequirements\":[{"
                + "\"id\":\"Q1\","
                + "\"title\":\"资质等级证书\","
                + "\"mainType\":\"资格要求\","
                + "\"subType\":\"资质证书\","
                + "\"content\":\"须具备有效资质证书\","
                + "\"bidSearch\":{\"queries\":[\"资质证书\",\"资质扫描件\",\"承诺函\"]},"
                + "\"evidence\":[{\"fragmentId\":3,\"page\":31,\"quote\":\"须具备有效资质证书\"}]"
                + "}],"
                + "\"scoring\":{"
                + "\"totalScore\":15,"
                + "\"scoreSumMatches\":true,"
                + "\"items\":[{"
                + "\"id\":\"S1\","
                + "\"title\":\"总体技术方案\","
                + "\"mainType\":\"评分项\","
                + "\"subType\":\"技术评分\","
                + "\"content\":\"根据技术架构综合评分，满分15分。\","
                + "\"fullScore\":15,"
                + "\"bidSearch\":{\"queries\":[\"技术方案\",\"实施方案\"]},"
                + "\"evidence\":[{\"fragmentId\":12,\"page\":42,\"quote\":\"满分15分\"}]"
                + "}]"
                + "}"
                + "}";
        List<LlmPort.ExtractedRequirement> items = llmPort.parseChecklistFromContent(json);
        assertEquals(2, items.size());
        assertEquals("QUALIFICATION", items.get(0).getMainType());
        assertEquals("CERTIFICATE", items.get(0).getSubType());
        assertEquals(3, items.get(0).getBidSearch().size());
        assertEquals(3L, items.get(0).getChunkIds().get(0).longValue());
        assertEquals("SCORING", items.get(1).getMainType());
        assertEquals("TECH_SCORE", items.get(1).getSubType());
        assertEquals(0, items.get(1).getScoreTotal().compareTo(new BigDecimal("15")));
        assertEquals(Boolean.TRUE, items.get(1).getScoreSumMatches());
    }

    @Test
    void parseChecklistMapsProductChineseSubTypes() throws Exception {
        String json = "{"
                + "\"items\":[{"
                + "\"title\":\"废标\","
                + "\"mainType\":\"招标资格要求\","
                + "\"subType\":\"废标否决\","
                + "\"content\":\"★号条款不满足作废标处理\","
                + "\"bidSearch\":[\"星号条款\",\"实质性响应\"]"
                + "},{"
                + "\"title\":\"技术分\","
                + "\"mainType\":\"评分项\","
                + "\"subType\":\"技术评分\","
                + "\"content\":\"技术方案满分 40 分\","
                + "\"bidSearch\":[\"技术方案\",\"实施方案\"],"
                + "\"scoreTotal\":40"
                + "}]}";
        List<LlmPort.ExtractedRequirement> items = llmPort.parseChecklistFromContent(json);
        assertEquals(2, items.size());
        assertEquals("QUALIFICATION", items.get(0).getMainType());
        assertEquals("REJECT", items.get(0).getSubType());
        assertEquals("SCORING", items.get(1).getMainType());
        assertEquals("TECH_SCORE", items.get(1).getSubType());
    }

    @Test
    void parseChecklistKeepsNullScoreWhenMissing() throws Exception {
        String json = "{"
                + "\"qualificationRequirements\":[],"
                + "\"scoring\":{"
                + "\"totalScore\":null,"
                + "\"scoreSumMatches\":null,"
                + "\"items\":[{"
                + "\"title\":\"技术方案\","
                + "\"mainType\":\"评分项\","
                + "\"subType\":\"技术评分\","
                + "\"content\":\"有评分描述但未写明满分\","
                + "\"fullScore\":null,"
                + "\"bidSearch\":{\"queries\":[\"技术方案\",\"实施方案\"]}"
                + "}]"
                + "}"
                + "}";
        List<LlmPort.ExtractedRequirement> items = llmPort.parseChecklistFromContent(json);
        assertEquals(1, items.size());
        assertNull(items.get(0).getScoreTotal());
        assertNull(items.get(0).getScoreSumMatches());
    }

    @Test
    void parseFlatArtifactItemsWithChunkIdsString() throws Exception {
        String json = "{\"items\":[{"
                + "\"id\":\"Q1\","
                + "\"title\":\"资质等级\","
                + "\"mainType\":\"资格要求\","
                + "\"subType\":\"资质证书\","
                + "\"content\":\"须具备二级资质\","
                + "\"chunkIds\":\"11,12\""
                + "}]}";
        List<LlmPort.ExtractedRequirement> items = llmPort.parseChecklistFromContent(json);
        assertEquals(1, items.size());
        assertEquals("须具备二级资质", items.get(0).getText());
        assertEquals(2, items.get(0).getChunkIds().size());
        assertEquals(11L, items.get(0).getChunkIds().get(0).longValue());
        assertEquals(12L, items.get(0).getChunkIds().get(1).longValue());
        assertTrue(items.get(0).getBidSearch() == null || items.get(0).getBidSearch().isEmpty());
    }

    @Test
    void parseBidSearchRowsFromFlatQFields() throws Exception {
        String json = "{\"rows\":[{"
                + "\"id\":\"Q1\",\"q1\":\"资质证书\",\"q2\":\"资质扫描件\",\"q3\":\"\",\"q4\":\"\""
                + "},{"
                + "\"id\":\"S1\",\"q1\":\"技术方案\",\"q2\":\"实施方案\",\"q3\":\"总体方案\",\"q4\":\"\""
                + "}]}";
        List<LlmPort.BidSearchRow> rows = llmPort.parseBidSearchFromContent(json);
        assertEquals(2, rows.size());
        assertEquals("Q1", rows.get(0).getId());
        assertEquals(2, rows.get(0).getQueries().size());
        assertEquals("资质证书", rows.get(0).getQueries().get(0));
        assertEquals("S1", rows.get(1).getId());
        assertEquals(3, rows.get(1).getQueries().size());
    }

    @Test
    void parseEvidenceRowsFromFlatFields() throws Exception {
        String json = "{\"rows\":[{"
                + "\"id\":\"Q1\",\"fragmentId\":101,\"page\":12,\"quote\":\"须具备二级资质\""
                + "},{"
                + "\"id\":\"S1\",\"chunkId\":201,\"page\":20,\"quote\":\"满分15分\""
                + "}]}";
        List<LlmPort.EvidenceRow> rows = llmPort.parseEvidenceFromContent(json);
        assertEquals(2, rows.size());
        assertEquals("Q1", rows.get(0).getId());
        assertEquals(Long.valueOf(101L), rows.get(0).getFragmentId());
        assertEquals(Integer.valueOf(12), rows.get(0).getPage());
        assertEquals("须具备二级资质", rows.get(0).getQuote());
        assertEquals("S1", rows.get(1).getId());
        assertEquals(Long.valueOf(201L), rows.get(1).getFragmentId());
    }

    @Test
    void parseTenderBasicInfoReadsFieldsAndEvidence() throws Exception {
        String json = "{"
                + "\"tenderer\":{\"value\":\"采购人A\",\"quote\":\"采购人：采购人A\",\"chunkId\":11,\"pageNo\":1},"
                + "\"agency\":{\"value\":\"代理B\",\"quote\":\"代理机构：代理B\",\"chunkId\":12,\"pageNo\":2},"
                + "\"budgetAmount\":{\"value\":\"预算金额100万元\",\"quote\":\"预算金额100万元\",\"chunkId\":13,\"pageNo\":3},"
                + "\"bidDeadline\":{\"text\":\"2026年8月15日17:00\",\"iso\":\"2026-08-15T09:00:00Z\","
                + "\"quote\":\"截止时间：2026年8月15日17:00\",\"chunkId\":14,\"pageNo\":4},"
                + "\"projectIntro\":{\"value\":\"项目简介\",\"quote\":\"项目简介原文\",\"chunkId\":15,\"pageNo\":5},"
                + "\"projectBackground\":{\"value\":\"项目背景\",\"quote\":\"项目背景原文\",\"chunkId\":16,\"pageNo\":6},"
                + "\"projectOverview\":{\"value\":\"项目概况\",\"quote\":\"项目概况原文\",\"chunkId\":17,\"pageNo\":7}"
                + "}";
        LlmPort.TenderBasicInfo info = llmPort.parseTenderBasicInfoFromContent(json);
        assertEquals("采购人A", info.getTenderer().getValue());
        assertEquals(Long.valueOf(11L), info.getTenderer().getChunkId());
        assertEquals("代理B", info.getAgency().getValue());
        assertEquals("预算金额100万元", info.getBudgetAmount().getValue());
        assertEquals("2026年8月15日17:00", info.getBidDeadline().getText());
        assertEquals("2026-08-15T09:00:00Z", info.getBidDeadline().getIso());
        assertEquals(Integer.valueOf(4), info.getBidDeadline().getPageNo());
        assertEquals("项目简介", info.getProjectIntro().getValue());
        assertEquals("项目背景", info.getProjectBackground().getValue());
        assertEquals("项目概况", info.getProjectOverview().getValue());
    }

    @Test
    void parseTenderIssueScanReadsLogicIssuesAndTypos() throws Exception {
        String json = "{"
                + "\"logicIssues\":[{"
                + "\"title\":\"业绩门槛与项目预算规模不匹配\","
                + "\"severity\":\"MEDIUM\","
                + "\"issueType\":\"THRESHOLD_INCONSISTENCY\","
                + "\"problem\":\"预算为1280万元，业绩要求为5000万元。\","
                + "\"suggestion\":\"调整为合理区间。\","
                + "\"evidence\":["
                + "{\"chunkId\":101,\"pageNo\":1,\"quote\":\"预算金额为人民币 1280 万元\"},"
                + "{\"chunkId\":104,\"pageNo\":5,\"quote\":\"近三年需具有 5000 万元以上类似项目业绩\"}"
                + "]"
                + "}],"
                + "\"typos\":[{"
                + "\"wrongText\":\"解折\","
                + "\"suggestedText\":\"解析\","
                + "\"context\":\"解折失败，请重新解析\","
                + "\"confidence\":\"HIGH\","
                + "\"suggestion\":\"建议改为解析。\","
                + "\"evidence\":[{\"chunkId\":105,\"pageNo\":6,\"quote\":\"解折失败，请重新解析\"}]"
                + "}]"
                + "}";

        LlmPort.TenderIssueScanResult result = llmPort.parseTenderIssueScanFromContent(json);

        assertEquals(1, result.getLogicIssues().size());
        assertEquals("业绩门槛与项目预算规模不匹配", result.getLogicIssues().get(0).getTitle());
        assertEquals("MEDIUM", result.getLogicIssues().get(0).getSeverity());
        assertEquals("THRESHOLD_INCONSISTENCY", result.getLogicIssues().get(0).getIssueType());
        assertEquals(2, result.getLogicIssues().get(0).getEvidence().size());
        assertEquals(Long.valueOf(101L), result.getLogicIssues().get(0).getEvidence().get(0).getChunkId());
        assertEquals(Integer.valueOf(1), result.getLogicIssues().get(0).getEvidence().get(0).getPageNo());
        assertEquals("预算金额为人民币 1280 万元", result.getLogicIssues().get(0).getEvidence().get(0).getQuote());

        assertEquals(1, result.getTypos().size());
        assertEquals("解折", result.getTypos().get(0).getWrongText());
        assertEquals("解析", result.getTypos().get(0).getSuggestedText());
        assertEquals("HIGH", result.getTypos().get(0).getConfidence());
        assertEquals(Long.valueOf(105L), result.getTypos().get(0).getEvidence().get(0).getChunkId());
    }

    @Test
    void parseTenderIssueScanReadsEmptyArrays() throws Exception {
        LlmPort.TenderIssueScanResult result =
                llmPort.parseTenderIssueScanFromContent("{\"logicIssues\":[],\"typos\":[]}");

        assertEquals(0, result.getLogicIssues().size());
        assertEquals(0, result.getTypos().size());
    }

    @Test
    void parseCompareFromContent() throws Exception {
        String json = "{\"requirementId\":\"Q1\",\"title\":\"资质\",\"reviewType\":\"响应性审查\","
                + "\"status\":\"符合\",\"vetoRisk\":false,\"judgement\":\"已响应\","
                + "\"bidEvidence\":[{\"fragmentId\":99,\"page\":5,\"quote\":\"摘录\"}],\"note\":null}";
        LlmPort.CompareResult result = llmPort.parseCompareFromContent(json, "QUALIFICATION", null);
        assertEquals("符合", result.getStatus());
        assertEquals("PASS", result.getVerdict());
        assertEquals("已响应", result.getJudgement());
        assertEquals(1, result.getBidEvidence().size());
        assertEquals(Long.valueOf(99L), result.getBidEvidence().get(0).getFragmentId());
        assertEquals("摘录", result.getBidExcerpt());
    }

    @Test
    void scanTenderIssuesReturnsRawContent() {
        LlmPort.TenderIssueScanResult result = llmPort.scanTenderIssues("候选原文…");

        assertEquals(0, result.getLogicIssues().size());
        assertEquals(0, result.getTypos().size());
        assertTrue(result.getRawContent() != null && result.getRawContent().contains("logicIssues"));
    }

    @Test
    void extractQualificationReturnsRawContent() {
        LlmPort.ExtractChecklistResult result = llmPort.extractQualificationChecklist("候选原文…");
        assertEquals(1, result.getItems().size());
        assertEquals("须具备二级资质", result.getItems().get(0).getText());
        assertEquals(2, result.getItems().get(0).getBidSearch().size());
        assertTrue(result.getRawContent() != null && result.getRawContent().contains("须具备二级资质"));
    }

    @Test
    void extractQualificationForcesMainType() {
        LlmPort.ExtractChecklistResult result = llmPort.extractQualificationChecklist("候选原文…");
        assertEquals(1, result.getItems().size());
        assertEquals("QUALIFICATION", result.getItems().get(0).getMainType());
        assertTrue(result.getItems().get(0).isMust());
        assertTrue(result.getRawContent() != null && result.getRawContent().contains("须具备二级资质"));
    }

    @Test
    void extractScoringDropsQualificationItemsFromWrongShape() {
        // mock 返回资格外形；评分拆分路径丢弃另一侧，避免误改主类
        LlmPort.ExtractChecklistResult result = llmPort.extractScoringChecklist("候选原文…");
        assertEquals(0, result.getItems().size());
        assertTrue(result.getRawContent() != null && result.getRawContent().contains("须具备二级资质"));
    }

    @Test
    void parseQualOnlyAndScoreOnlyShapes() throws Exception {
        String qualOnly = "{\"qualificationRequirements\":[{"
                + "\"id\":\"Q1\",\"title\":\"资质\",\"mainType\":\"资格要求\",\"subType\":\"资质证书\","
                + "\"content\":\"须具备二级资质\","
                + "\"bidSearch\":{\"queries\":[\"资质证书\"]},"
                + "\"evidence\":[{\"fragmentId\":1,\"page\":1,\"quote\":\"二级\"}]"
                + "}]}";
        List<LlmPort.ExtractedRequirement> quals = llmPort.parseChecklistFromContent(qualOnly);
        assertEquals(1, quals.size());
        assertEquals("QUALIFICATION", quals.get(0).getMainType());

        String scoreOnly = "{\"scoring\":{\"totalScore\":15,\"scoreSumMatches\":true,\"items\":[{"
                + "\"id\":\"S1\",\"title\":\"技术方案\",\"mainType\":\"评分项\",\"subType\":\"技术评分\","
                + "\"content\":\"满分15分\",\"fullScore\":15,"
                + "\"bidSearch\":{\"queries\":[\"技术方案\"]},"
                + "\"evidence\":[{\"fragmentId\":2,\"page\":2,\"quote\":\"15分\"}]"
                + "}]}}";
        List<LlmPort.ExtractedRequirement> scores = llmPort.parseChecklistFromContent(scoreOnly);
        assertEquals(1, scores.size());
        assertEquals("SCORING", scores.get(0).getMainType());
        assertEquals(0, scores.get(0).getScoreTotal().compareTo(new BigDecimal("15")));
    }
}
