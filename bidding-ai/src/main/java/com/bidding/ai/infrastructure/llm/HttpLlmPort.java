package com.bidding.ai.infrastructure.llm;

import com.bidding.common.port.LlmPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 百炼 OpenAI 兼容 Chat（compatible-mode/v1/chat/completions）：建清单抽取 + 审查对照。
 */
@Component
public class HttpLlmPort implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmPort.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String chatUrl;
    private final String apiKey;
    private final String modelName;
    private final String promptTemplate;
    private final String reviewPromptTemplate;
    private final Duration requestTimeout;

    public HttpLlmPort(
            ObjectMapper objectMapper,
            @Value("${bidding.llm.base-url:${bidding.index.embedding.base-url}}") String baseUrl,
            @Value("${bidding.llm.api-key:${bidding.index.embedding.api-key:${DASHSCOPE_API_KEY:}}}") String apiKey,
            @Value("${bidding.llm.model:qwen3.7-plus}") String modelName,
            @Value("${bidding.llm.extract-prompt-template:}") String promptTemplate,
            @Value("${bidding.llm.review-prompt-template:}") String reviewPromptTemplate,
            @Value("${bidding.llm.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${bidding.llm.read-timeout-ms:180000}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.chatUrl = normalized + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = modelName == null || modelName.isBlank() ? "qwen3.7-plus" : modelName.trim();
        if (promptTemplate == null || promptTemplate.isBlank()) {
            this.promptTemplate = defaultPromptTemplate();
        } else {
            this.promptTemplate = promptTemplate;
        }
        if (reviewPromptTemplate == null || reviewPromptTemplate.isBlank()) {
            this.reviewPromptTemplate = defaultReviewPromptTemplate();
        } else {
            this.reviewPromptTemplate = reviewPromptTemplate;
        }
        this.requestTimeout = Duration.ofMillis(readTimeoutMs < 1 ? 180000L : readTimeoutMs);
        Duration connectTimeout = Duration.ofMillis(connectTimeoutMs < 1 ? 3000L : connectTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        if (this.apiKey.isEmpty()) {
            log.warn("HttpLlmPort 已启用但未配置 bidding.llm.api-key（DASHSCOPE_API_KEY）");
        } else {
            log.info("HttpLlmPort 已启用，url={}, model={}", this.chatUrl, this.modelName);
        }
    }

    @Override
    public ExtractChecklistResult extractChecklist(String chunksText) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        String chunks = chunksText == null ? "" : chunksText;
        String userPrompt = promptTemplate.replace("{{recalled_chunks}}", chunks);
        String content = chatJsonContent(
                "你是招标投标规则归纳专家。只依据给定招标原文归纳，不得编造。只输出 JSON。",
                userPrompt);
        try {
            ExtractChecklistResult result = new ExtractChecklistResult();
            result.setRawContent(content);
            result.setItems(parseChecklistFromContent(content));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM 建清单结果失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public CompareResult compareRequirement(CompareRequest request) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        if (request == null) {
            throw new IllegalArgumentException("对照请求不能为空");
        }
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("招标要求不能为空");
        }
        String mainType = request.getMainType() == null ? "" : request.getMainType().trim();
        String fullScoreText = request.getFullScore() == null ? "null" : request.getFullScore().toPlainString();
        String userPrompt = reviewPromptTemplate
                .replace("{{supplier}}", nullToEmpty(request.getSupplier()))
                .replace("{{requirement_id}}", nullToEmpty(request.getRequirementId()))
                .replace("{{title}}", nullToEmpty(request.getTitle()))
                .replace("{{main_type}}", mainType)
                .replace("{{requirement_text}}", content)
                .replace("{{requirement_excerpt}}", nullToEmpty(request.getTenderQuote()))
                .replace("{{full_score}}", fullScoreText)
                .replace("{{bid_chunks_text}}", request.getBidChunksText() == null ? "" : request.getBidChunksText());
        String raw = chatJsonContent(
                "你是投标响应核验专家。只依据给定投标原文片段判定，不得编造。只输出 JSON。",
                userPrompt);
        try {
            CompareResult result = parseCompareFromContent(raw, mainType, request.getFullScore());
            result.setRawContent(raw);
            if (result.getRequirementId() == null || result.getRequirementId().isBlank()) {
                result.setRequirementId(request.getRequirementId());
            }
            if (result.getTitle() == null || result.getTitle().isBlank()) {
                result.setTitle(request.getTitle());
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM 对照结果失败: " + ex.getMessage(), ex);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String chatJsonContent(String systemContent, String userContent) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            ArrayNode messages = root.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemContent);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userContent);
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", "json_object");

            byte[] body = objectMapper.writeValueAsBytes(root);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("DashScope chat HTTP " + response.statusCode()
                        + ": " + truncate(response.body()));
            }
            JsonNode rootNode = objectMapper.readTree(response.body());
            String content = rootNode.path("choices").path(0).path("message").path("content").asText("");
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("LLM 返回 content 为空");
            }
            return stripMarkdownFence(content.trim());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("调用 LLM 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 解析建清单 LLM JSON。
     * 支持产品外形（qualificationRequirements + scoring）以及旧版 items[] / 纯数组，统一展平。
     */
    List<ExtractedRequirement> parseChecklistFromContent(String trimmed) throws IOException {
        JsonNode parsed = objectMapper.readTree(trimmed);
        List<ExtractedRequirement> result = new ArrayList<>();

        if (parsed.isObject()
                && (parsed.has("qualificationRequirements") || parsed.has("scoring"))) {
            Boolean scoreSumMatches = null;
            JsonNode scoringRoot = parsed.get("scoring");
            if (scoringRoot != null && scoringRoot.isObject()
                    && scoringRoot.has("scoreSumMatches")
                    && !scoringRoot.get("scoreSumMatches").isNull()) {
                scoreSumMatches = scoringRoot.get("scoreSumMatches").asBoolean();
            }

            JsonNode quals = parsed.get("qualificationRequirements");
            if (quals != null && quals.isArray()) {
                for (int i = 0; i < quals.size(); i++) {
                    ExtractedRequirement item = parseOneChecklistItem(quals.get(i), "QUALIFICATION", null);
                    if (item != null) {
                        result.add(item);
                    }
                }
            }

            if (scoringRoot != null && scoringRoot.isObject()) {
                JsonNode scoringItems = scoringRoot.get("items");
                if (scoringItems != null && scoringItems.isArray()) {
                    for (int i = 0; i < scoringItems.size(); i++) {
                        ExtractedRequirement item = parseOneChecklistItem(
                                scoringItems.get(i), "SCORING", scoreSumMatches);
                        if (item != null) {
                            result.add(item);
                        }
                    }
                }
            }
            return result;
        }

        JsonNode arrayNode;
        if (parsed.isArray()) {
            arrayNode = parsed;
        } else if (parsed.has("items") && parsed.get("items").isArray()) {
            arrayNode = parsed.get("items");
        } else {
            throw new IllegalStateException(
                    "建清单 LLM JSON 须为产品外形、items 数组或纯数组");
        }

        for (int i = 0; i < arrayNode.size(); i++) {
            ExtractedRequirement item = parseOneChecklistItem(arrayNode.get(i), null, null);
            if (item != null) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 解析单条清单；defaultMainType 非空时强制主类（产品分段用）。
     */
    private ExtractedRequirement parseOneChecklistItem(JsonNode node,
                                                       String defaultMainType,
                                                       Boolean scoreSumMatchesHint) throws IOException {
        if (node == null || !node.isObject()) {
            return null;
        }
        String content = textOf(node, "content");
        if (content == null || content.isBlank()) {
            content = textOf(node, "text");
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        // 产品：内容尽量原文，落库侧截断过长文案
        content = truncateByCodePoint(content.trim(), 200);

        ExtractedRequirement item = new ExtractedRequirement();
        item.setText(content);

        String title = textOf(node, "title");
        if (title != null) {
            item.setTitle(truncateByCodePoint(title.trim(), 15));
        }

        String mainType = normalizeMainType(textOf(node, "mainType"));
        if (mainType == null) {
            mainType = normalizeMainType(textOf(node, "main_type"));
        }
        if (mainType == null && defaultMainType != null) {
            mainType = defaultMainType;
        }
        if (mainType == null) {
            mainType = "QUALIFICATION";
        }
        item.setMainType(mainType);

        item.setSubType(normalizeSubType(textOf(node, "subType")));
        if (item.getSubType() == null) {
            item.setSubType(normalizeSubType(textOf(node, "sub_type")));
        }
        item.setMust("QUALIFICATION".equals(item.getMainType()));
        item.setCategory(item.getMainType());

        List<String> searches = parseBidSearchQueries(node.get("bidSearch"));
        if (searches.isEmpty()) {
            searches = parseBidSearchQueries(node.get("bid_search"));
        }
        item.setBidSearch(searches);

        JsonNode evidenceNode = node.get("evidence");
        if (evidenceNode == null || evidenceNode.isNull()) {
            evidenceNode = node.get("evidences");
        }
        fillEvidence(item, evidenceNode);

        // 评分满分：产品 fullScore / 旧 scoreTotal；未明确保持 null，不用 0 顶替
        java.math.BigDecimal score = decimalOf(node, "fullScore");
        if (score == null) {
            score = decimalOf(node, "full_score");
        }
        if (score == null) {
            score = decimalOf(node, "scoreTotal");
        }
        if (score == null) {
            score = decimalOf(node, "score_total");
        }
        item.setScoreTotal(score);

        JsonNode parts = node.get("scoreParts");
        if (parts == null || parts.isNull()) {
            parts = node.get("score_parts");
        }
        if (parts != null && parts.isArray()) {
            item.setScorePartsJson(parts.toString());
        }

        if (scoreSumMatchesHint != null && "SCORING".equals(item.getMainType())) {
            item.setScoreSumMatches(scoreSumMatchesHint);
        } else if (node.has("scoreSumMatches") && !node.get("scoreSumMatches").isNull()) {
            item.setScoreSumMatches(node.get("scoreSumMatches").asBoolean());
        } else if (node.has("score_sum_matches") && !node.get("score_sum_matches").isNull()) {
            item.setScoreSumMatches(node.get("score_sum_matches").asBoolean());
        }

        return item;
    }

    /**
     * bidSearch 支持字符串数组，或产品外形 {"queries":[...]}。
     */
    private List<String> parseBidSearchQueries(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            return parseStringList(node);
        }
        if (node.isObject()) {
            JsonNode queries = node.get("queries");
            if (queries == null || queries.isNull()) {
                queries = node.get("query");
            }
            if (queries != null && queries.isArray()) {
                return parseStringList(queries);
            }
        }
        return result;
    }

    private static String truncateByCodePoint(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        int count = text.codePointCount(0, text.length());
        if (count <= maxChars) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, end);
    }

    private void fillEvidence(ExtractedRequirement item, JsonNode evidenceNode) throws IOException {
        if (evidenceNode == null || !evidenceNode.isArray() || evidenceNode.isEmpty()) {
            return;
        }
        item.setEvidenceJson(evidenceNode.toString());
        List<Long> chunkIds = new ArrayList<>();
        for (int i = 0; i < evidenceNode.size(); i++) {
            JsonNode ev = evidenceNode.get(i);
            if (ev == null || !ev.isObject()) {
                continue;
            }
            Long chunkId = null;
            if (ev.has("chunkId") && !ev.get("chunkId").isNull()) {
                chunkId = ev.get("chunkId").asLong();
            } else if (ev.has("chunk_id") && !ev.get("chunk_id").isNull()) {
                chunkId = ev.get("chunk_id").asLong();
            } else if (ev.has("fragmentId") && !ev.get("fragmentId").isNull()) {
                JsonNode fid = ev.get("fragmentId");
                if (fid.isNumber()) {
                    chunkId = fid.asLong();
                } else {
                    try {
                        chunkId = Long.parseLong(fid.asText().trim());
                    } catch (NumberFormatException ignored) {
                        // 非数字 fragmentId 忽略
                    }
                }
            }
            if (chunkId != null) {
                chunkIds.add(chunkId);
            }
            if (item.getSourceExcerpt() == null) {
                String quote = textOf(ev, "quote");
                if (quote != null) {
                    item.setSourceExcerpt(quote);
                }
            }
            if (item.getPageNo() == null) {
                if (ev.has("page") && !ev.get("page").isNull()) {
                    item.setPageNo(ev.get("page").asInt());
                } else if (ev.has("pageNo") && !ev.get("pageNo").isNull()) {
                    item.setPageNo(ev.get("pageNo").asInt());
                }
            }
        }
        item.setChunkIds(chunkIds);
    }

    private static String normalizeMainType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        String upper = t.toUpperCase();
        if ("QUALIFICATION".equals(upper)
                || "资格要求".equals(t)
                || "招标资格要求".equals(t)
                || "资格".equals(t)) {
            return "QUALIFICATION";
        }
        if ("SCORING".equals(upper) || "SCORE".equals(upper)
                || "评分项".equals(t) || "评分".equals(t)) {
            return "SCORING";
        }
        return upper.replace('-', '_');
    }

    /**
     * 子类归一：认产品中文名与历史英文 code；无法识别则保留大写形式。
     */
    private static String normalizeSubType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        String upper = t.toUpperCase().replace('-', '_').replace(' ', '_');

        // 资格侧（产品中文）
        if ("资格要求".equals(t) || "资格".equals(t) || "准入".equals(t)
                || "ELIGIBILITY".equals(upper)) {
            return "ELIGIBILITY";
        }
        if ("资质证书".equals(t) || "资质".equals(t) || "证书".equals(t)
                || "CERTIFICATE".equals(upper)) {
            return "CERTIFICATE";
        }
        if ("人员要求".equals(t) || "人员".equals(t) || "PERSONNEL".equals(upper)) {
            return "PERSONNEL";
        }
        if ("废标否决".equals(t) || "废标".equals(t) || "否决".equals(t)
                || "REJECT".equals(upper)) {
            return "REJECT";
        }
        if ("商务条款".equals(t) || "商务".equals(t)
                || "BUSINESS".equals(upper) || "COMMERCIAL".equals(upper)) {
            return "BUSINESS";
        }
        if ("技术要求".equals(t) || "技术".equals(t) || "技术方案".equals(t)
                || "TECH".equals(upper)) {
            return "TECH";
        }
        if ("报价要求".equals(t) || "报价".equals(t) || "价格".equals(t)
                || "PRICE".equals(upper) || "QUOTE".equals(upper)) {
            return "PRICE";
        }
        if ("格式封装".equals(t) || "格式".equals(t) || "FORMAT".equals(upper)) {
            return "FORMAT";
        }
        if ("证明材料".equals(t) || "证明".equals(t) || "EVIDENCE".equals(upper)) {
            return "EVIDENCE";
        }
        if ("电子标流程".equals(t) || "电子标".equals(t)
                || "E_BID".equals(upper) || "EBID".equals(upper)) {
            return "E_BID";
        }
        if ("合规风险".equals(t) || "合规".equals(t) || "COMPLIANCE".equals(upper)) {
            return "COMPLIANCE";
        }

        // 评分侧
        if ("技术评分".equals(t) || "TECH_SCORE".equals(upper)) {
            return "TECH_SCORE";
        }
        if ("商务评分".equals(t) || "BUSINESS_SCORE".equals(upper)) {
            return "BUSINESS_SCORE";
        }
        if ("价格评分".equals(t) || "PRICE_SCORE".equals(upper)) {
            return "PRICE_SCORE";
        }
        if ("售后服务评分".equals(t) || "售后评分".equals(t) || "SERVICE_SCORE".equals(upper)) {
            return "SERVICE_SCORE";
        }
        if ("业绩评分".equals(t) || "PERFORMANCE_SCORE".equals(upper)) {
            return "PERFORMANCE_SCORE";
        }
        if ("评分项".equals(t) || "OTHER".equals(upper)) {
            return "OTHER";
        }
        return upper;
    }

    private List<String> parseStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isNull() || !node.isArray()) {
            return result;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode el = node.get(i);
            if (el == null || el.isNull()) {
                continue;
            }
            String s = el.asText();
            if (s != null && !s.isBlank()) {
                result.add(s.trim());
            }
        }
        return result;
    }

    private BigDecimal decimalOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        String s = v.asText();
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    CompareResult parseCompareFromContent(String trimmed) throws IOException {
        return parseCompareFromContent(trimmed, "QUALIFICATION", null);
    }

    CompareResult parseCompareFromContent(String trimmed,
                                          String mainType,
                                          java.math.BigDecimal fullScoreHint) throws IOException {
        JsonNode node = objectMapper.readTree(trimmed);
        if (node.has("result") && node.get("result").isObject()) {
            node = node.get("result");
        }
        // 兼容模型偶发包在数组里
        if (node.has("qualificationResponses") && node.get("qualificationResponses").isArray()
                && node.get("qualificationResponses").size() > 0) {
            node = node.get("qualificationResponses").get(0);
        } else if (node.has("scoringResponses") && node.get("scoringResponses").isArray()
                && node.get("scoringResponses").size() > 0) {
            node = node.get("scoringResponses").get(0);
        }

        boolean scoring = "SCORING".equalsIgnoreCase(mainType == null ? "" : mainType.trim());
        CompareResult result = new CompareResult();
        result.setRequirementId(textOf(node, "requirementId"));
        if (result.getRequirementId() == null) {
            result.setRequirementId(textOf(node, "requirement_id"));
        }
        result.setTitle(textOf(node, "title"));
        result.setReviewType(textOf(node, "reviewType"));
        if (result.getReviewType() == null) {
            result.setReviewType(textOf(node, "review_type"));
        }
        result.setJudgement(textOf(node, "judgement"));
        if (result.getJudgement() == null) {
            result.setJudgement(textOf(node, "reason"));
        }
        result.setNote(textOf(node, "note"));
        if (node.has("vetoRisk") && !node.get("vetoRisk").isNull()) {
            result.setVetoRisk(node.get("vetoRisk").asBoolean(false));
        } else if (node.has("veto_risk") && !node.get("veto_risk").isNull()) {
            result.setVetoRisk(node.get("veto_risk").asBoolean(false));
        } else {
            result.setVetoRisk(Boolean.FALSE);
        }

        List<BidEvidence> evidences = parseBidEvidence(node.get("bidEvidence"));
        if (evidences.isEmpty()) {
            evidences = parseBidEvidence(node.get("bid_evidence"));
        }
        result.setBidEvidence(evidences);

        if (scoring) {
            result.setStatus(null);
            result.setReviewType(result.getReviewType() == null ? "评分建议" : result.getReviewType());
            java.math.BigDecimal full = readDecimal(node, "fullScore");
            if (full == null) {
                full = readDecimal(node, "full_score");
            }
            if (full == null) {
                full = fullScoreHint;
            }
            result.setFullScore(full);
            if (full == null) {
                result.setSuggestedScore(null);
                result.setDeductionReason(null);
            } else {
                java.math.BigDecimal suggested = readDecimal(node, "suggestedScore");
                if (suggested == null) {
                    suggested = readDecimal(node, "suggested_score");
                }
                if (suggested == null) {
                    suggested = java.math.BigDecimal.ZERO;
                }
                if (suggested.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    suggested = java.math.BigDecimal.ZERO;
                }
                if (suggested.compareTo(full) > 0) {
                    suggested = full;
                }
                result.setSuggestedScore(suggested);
                result.setDeductionReason(textOf(node, "deductionReason"));
                if (result.getDeductionReason() == null) {
                    result.setDeductionReason(textOf(node, "deduction_reason"));
                }
            }
            result.setVerdict(null);
        } else {
            String status = textOf(node, "status");
            status = normalizeStatus(status);
            result.setStatus(status);
            result.setVerdict(mapStatusToVerdict(status));
            if (result.getReviewType() == null) {
                result.setReviewType(Boolean.TRUE.equals(result.getVetoRisk()) ? "废标风险" : "响应性审查");
            }
        }

        result.setReason(result.getJudgement());
        if (!evidences.isEmpty()) {
            BidEvidence first = evidences.get(0);
            result.setBidExcerpt(first.getQuote());
            result.setPageNo(first.getPage());
            result.setChunkId(first.getFragmentId());
        }
        return result;
    }

    private List<BidEvidence> parseBidEvidence(JsonNode arr) {
        List<BidEvidence> list = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return list;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonNode el = arr.get(i);
            if (el == null || el.isNull() || !el.isObject()) {
                continue;
            }
            BidEvidence ev = new BidEvidence();
            if (el.has("fragmentId") && !el.get("fragmentId").isNull()) {
                ev.setFragmentId(el.get("fragmentId").asLong());
            } else if (el.has("chunkId") && !el.get("chunkId").isNull()) {
                ev.setFragmentId(el.get("chunkId").asLong());
            }
            if (el.has("page") && !el.get("page").isNull()) {
                ev.setPage(el.get("page").asInt());
            } else if (el.has("pageNo") && !el.get("pageNo").isNull()) {
                ev.setPage(el.get("pageNo").asInt());
            }
            String quote = textOf(el, "quote");
            if (quote == null) {
                quote = textOf(el, "bidExcerpt");
            }
            ev.setQuote(quote);
            if (ev.getQuote() != null && !ev.getQuote().isBlank() && !isPlaceholderExcerpt(ev.getQuote())) {
                list.add(ev);
            }
        }
        return list;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "无法判断";
        }
        String s = status.trim();
        if ("符合".equals(s) || "不符合".equals(s) || "无法判断".equals(s)) {
            return s;
        }
        return "无法判断";
    }

    private static String mapStatusToVerdict(String status) {
        if ("符合".equals(status)) {
            return "PASS";
        }
        if ("不符合".equals(status)) {
            return "FAIL";
        }
        return "UNCLEAR";
    }

    private static boolean isPlaceholderExcerpt(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.trim();
        return t.startsWith("无相关")
                || t.startsWith("未找到")
                || t.startsWith("找不到")
                || t.startsWith("没有相关")
                || t.startsWith("未检索到")
                || t.startsWith("未见");
    }

    private static java.math.BigDecimal readDecimal(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v.isNumber()) {
            return v.decimalValue();
        }
        if (v.isTextual()) {
            try {
                return new java.math.BigDecimal(v.asText().trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stripMarkdownFence(String trimmed) {
        String text = trimmed;
        if (text.startsWith("```")) {
            int firstNl = text.indexOf('\n');
            if (firstNl > 0) {
                text = text.substring(firstNl + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }

    private List<Long> parseLongList(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        if (node == null || node.isNull()) {
            return ids;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode el = node.get(i);
                if (el != null && el.isNumber()) {
                    ids.add(el.asLong());
                } else if (el != null && el.isTextual()) {
                    try {
                        ids.add(Long.parseLong(el.asText().trim()));
                    } catch (NumberFormatException ignored) {
                        // 跳过非法 id
                    }
                }
            }
        }
        return ids;
    }

    private String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String t = v.asText();
        if (t == null || t.isBlank()) {
            return null;
        }
        return t;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= 500) {
            return body;
        }
        return body.substring(0, 500);
    }

    /**
     * 建清单默认提示词：产品外形（资格列表 + 评分块）+ 规则；落库前由程序展平。
     */
    private static String defaultPromptTemplate() {
        return "【角色】招投标规则归纳专家。把 RAG 召回并重排后的招标文件片段，"
                + "整理成结构化的「招标要求清单」和「评分项清单」。只依据原文，不得编造。只输出 JSON 本体。\n"
                + "【任务】\n"
                + "1) 阅读全部片段；\n"
                + "2) 按主类规则判「资格要求」或「评分项」；\n"
                + "3) 再判子类；\n"
                + "4) 评分项须给出各条目满分 fullScore，并给出评分块 totalScore；\n"
                + "5) 同类归纳、合并、去重；同一规则多出处时 evidence 保留全部；\n"
                + "6) 每条生成 bidSearch.queries（2～4 个词）；\n"
                + "7) 按产品外形输出 qualificationRequirements 与 scoring。\n"
                + "【主类规则】\n"
                + "- 含★号/否则否决/不予受理等 → 资格要求，子类优先「废标否决」；\n"
                + "- 含分值/权重/打分 → 评分项；\n"
                + "- 既无否决也无分值但含须/应/应当 → 资格要求；\n"
                + "- 其余默认可归资格要求。\n"
                + "【子类】资格（判不出→资格要求）：资格要求、资质证书、人员要求、废标否决、商务条款、"
                + "技术要求、报价要求、格式封装、证明材料、电子标流程、合规风险。"
                + "评分（判不出→评分项）：技术评分、商务评分、价格评分、售后服务评分、业绩评分等。\n"
                + "【输出规则】\n"
                + "1) 标题≤15字，content≤200字（尽量原文）；\n"
                + "2) 评分项须有 fullScore；scoring.totalScore 为各项满分合计目标；\n"
                + "3) 字段未明确（含满分、总分）一律 null，禁止用 0 或编造顶替；\n"
                + "4) 各项满分合计与 totalScore 一致则 scoreSumMatches=true，否则 false（不得自行调平）；"
                + "总分或任一满分缺失则 scoreSumMatches=null；\n"
                + "5) bidSearch.queries 每条 2～4 词：第1个原文术语，其余投标侧措辞"
                + "（承诺函/扫描件/附件/偏离表/方案章节名/关键指标值），每词≤30字，只含检索对象，"
                + "不含「否则否决」「不得」等后果语；不同条目检索词不得复用拼凑；\n"
                + "6) evidence 用 fragmentId（=候选 chunkId）、page、quote；quote 须摘自原文。\n"
                + "【约束】只输出 JSON；不编造片段中不存在的信息；无内容则 "
                + "{\"qualificationRequirements\":[],\"scoring\":{\"totalScore\":null,\"scoreSumMatches\":null,\"items\":[]}}。\n"
                + "【候选原文】\n{{recalled_chunks}}\n"
                + "【输出外形】\n"
                + "{\"qualificationRequirements\":[{"
                + "\"id\":\"Q1\",\"title\":\"...\",\"mainType\":\"资格要求\",\"subType\":\"...\","
                + "\"content\":\"...\",\"bidSearch\":{\"queries\":[\"...\"]},"
                + "\"evidence\":[{\"fragmentId\":1,\"page\":1,\"quote\":\"...\"}]}],"
                + "\"scoring\":{\"totalScore\":100,\"scoreSumMatches\":true,\"items\":[{"
                + "\"id\":\"S1\",\"title\":\"...\",\"mainType\":\"评分项\",\"subType\":\"...\","
                + "\"content\":\"...\",\"fullScore\":15,\"bidSearch\":{\"queries\":[\"...\"]},"
                + "\"evidence\":[{\"fragmentId\":2,\"page\":2,\"quote\":\"...\"}]}]}}";
    }

    private static String defaultReviewPromptTemplate() {
        return "【角色】投标响应核验专家。拿结构化招标要求/评分项，在 RAG 召回的投标片段中核验响应，"
                + "输出核验结论、废标风险与评分建议。一次只核验当前这一条、当前这一家投标单位。\n"
                + "【任务】\n"
                + "1) 阅读 title、content、tenderQuote（评分项含满分 fullScore）及投标候选片段；\n"
                + "2) 资格：判符合/不符合/无法判断；评分：不设 status，在满分内给建议分；\n"
                + "3) 提取支撑结论的原文证据；\n"
                + "4) 评分项在 fullScore 范围内给 suggestedScore 与 deductionReason；无证据部分按未响应计 0；\n"
                + "5) 发现前后矛盾时写入 note；\n"
                + "6) 只输出当前条目的一个 JSON 对象（不要输出整家数组）。\n"
                + "【供应商】{{supplier}}\n"
                + "【条目】requirementId={{requirement_id}} title={{title}} mainType={{main_type}} fullScore={{full_score}}\n"
                + "【招标要求 content】{{requirement_text}}\n"
                + "【招标原文 tenderQuote】{{requirement_excerpt}}\n"
                + "【投标候选原文】\n{{bid_chunks_text}}\n"
                + "【判定】符合=明确响应且达标；不符合=有响应未达标/负偏离/触废标否决；"
                + "无法判断=未检索到或证据不足（宁可无法判断，不得推测）。"
                + "判定以 tenderQuote 为准；content 与原文不一致时按原文。\n"
                + "评分：完全达到给满分档，部分达到按档扣分并写扣分点；无证据部分计 0；"
                + "suggestedScore 不得为负且≤fullScore；fullScore 为 null 时不输出 suggestedScore/deductionReason（前端暂无评分）。\n"
                + "【输出规则】requirementId 关联 Q/S；judgement≤200 字先结论后依据；"
                + "资格无证据→status=无法判断；评分不设 status；"
                + "评分无响应→suggestedScore=0 且 judgement 含「未检索到响应，按未响应计 0 分」；"
                + "多处证据全部保留；不同条目证据不得混用；未明确字段填 null。\n"
                + "【输出字段】requirementId,title,reviewType(响应性审查|废标风险|评分建议),"
                + "status(仅资格),vetoRisk,judgement,bidEvidence([{fragmentId,page,quote}]),note,"
                + "suggestedScore,fullScore,deductionReason(仅评分且 fullScore 非 null)。\n"
                + "【约束】只输出 JSON 本体；不编造；除无法判断外须有 bidEvidence。";
    }
}
