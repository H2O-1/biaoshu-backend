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
            String content = "{"
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
    void extractChecklistParsesLegacyItems() throws Exception {
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
    void extractChecklistReturnsRawContent() {
        LlmPort.ExtractChecklistResult result = llmPort.extractChecklist("候选原文…");
        assertEquals(1, result.getItems().size());
        assertEquals("须具备二级资质", result.getItems().get(0).getText());
        assertEquals(2, result.getItems().get(0).getBidSearch().size());
        assertTrue(result.getRawContent() != null && result.getRawContent().contains("须具备二级资质"));
    }
}
