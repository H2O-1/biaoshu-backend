package com.bidding.ai.infrastructure.llm;

import com.bidding.common.port.LlmPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 建清单按产物拆分：资格条目 A ∥ 评分条目 B → bidSearch C。
 */
@Component
public class HttpLlmPort implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmPort.class);

    private static final String SYSTEM_EXTRACT_QUAL =
            "你是招标资格要求归纳专家。只依据给定招标原文归纳资格/准入/否决类条目，不得编造。只输出 JSON。";

    private static final String SYSTEM_EXTRACT_SCORE =
            "你是招标评分项归纳专家。只依据给定招标原文归纳含分值的评分条目，不得编造。只输出 JSON。";

    private static final String SYSTEM_BID_SEARCH =
            "你是投标侧检索词专家。根据已编号的招标清单条目生成投标文件召回词，不得编造条目。只输出 JSON。";

    private static final String SYSTEM_EVIDENCE =
            "你是招标原文证据摘录专家。只从给定候选原文摘录支撑条目的证据，不得编造。只输出 JSON。";

    private static final String SYSTEM_TENDER_BASIC =
            "你是招标文件基础信息解析助手。只依据给定候选原文抽取，不得编造。只输出 JSON。";

    private static final String SYSTEM_TENDER_ISSUE =
            "你是招标文件文本质检助手。只依据给定招标原文片段识别逻辑问题和错别字，不输出法律风险。只输出 JSON。";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String chatUrl;
    private final String apiKey;
    private final String modelName;
    private final String qualPromptTemplate;
    private final String scorePromptTemplate;
    private final String bidSearchPromptTemplate;
    private final String evidencePromptTemplate;
    private final String reviewPromptTemplate;
    private final Duration requestTimeout;

    /** Spring 注入入口（多构造器时必须显式指定）。 */
    @Autowired
    public HttpLlmPort(
            ObjectMapper objectMapper,
            @Value("${bidding.llm.base-url:${bidding.index.embedding.base-url}}") String baseUrl,
            @Value("${bidding.llm.api-key:${bidding.index.embedding.api-key:${DASHSCOPE_API_KEY:}}}") String apiKey,
            @Value("${bidding.llm.model:qwen3.7-plus}") String modelName,
            @Value("${bidding.llm.extract-qual-prompt-template:}") String qualPromptTemplate,
            @Value("${bidding.llm.extract-score-prompt-template:}") String scorePromptTemplate,
            @Value("${bidding.llm.bid-search-prompt-template:}") String bidSearchPromptTemplate,
            @Value("${bidding.llm.evidence-prompt-template:}") String evidencePromptTemplate,
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
        if (qualPromptTemplate == null || qualPromptTemplate.isBlank()) {
            this.qualPromptTemplate = defaultQualPromptTemplate();
        } else {
            this.qualPromptTemplate = qualPromptTemplate;
        }
        if (scorePromptTemplate == null || scorePromptTemplate.isBlank()) {
            this.scorePromptTemplate = defaultScorePromptTemplate();
        } else {
            this.scorePromptTemplate = scorePromptTemplate;
        }
        if (bidSearchPromptTemplate == null || bidSearchPromptTemplate.isBlank()) {
            this.bidSearchPromptTemplate = defaultBidSearchPromptTemplate();
        } else {
            this.bidSearchPromptTemplate = bidSearchPromptTemplate;
        }
        if (evidencePromptTemplate == null || evidencePromptTemplate.isBlank()) {
            this.evidencePromptTemplate = defaultEvidencePromptTemplate();
        } else {
            this.evidencePromptTemplate = evidencePromptTemplate;
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
    public ExtractChecklistResult extractQualificationChecklist(String chunksText) {
        ExtractChecklistResult result = runExtract(
                SYSTEM_EXTRACT_QUAL, qualPromptTemplate, chunksText, "资格建清单");
        keepOnlyMainType(result, "QUALIFICATION");
        return result;
    }

    @Override
    public ExtractChecklistResult extractScoringChecklist(String chunksText) {
        ExtractChecklistResult result = runExtract(
                SYSTEM_EXTRACT_SCORE, scorePromptTemplate, chunksText, "评分建清单");
        keepOnlyMainType(result, "SCORING");
        return result;
    }

    @Override
    public BidSearchResult generateBidSearch(String checklistItemsText) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        String items = checklistItemsText == null ? "" : checklistItemsText;
        String userPrompt = bidSearchPromptTemplate.replace("{{checklist_items}}", items);
        String content = chatJsonContent(SYSTEM_BID_SEARCH, userPrompt);
        try {
            BidSearchResult result = new BidSearchResult();
            result.setRawContent(content);
            result.setRows(parseBidSearchFromContent(content));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM bidSearch 结果失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public EvidenceResult generateEvidence(String checklistItemsText, String relatedChunksText) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        String items = checklistItemsText == null ? "" : checklistItemsText;
        String chunks = relatedChunksText == null ? "" : relatedChunksText;
        String userPrompt = evidencePromptTemplate
                .replace("{{checklist_items}}", items)
                .replace("{{related_chunks}}", chunks);
        String content = chatJsonContent(SYSTEM_EVIDENCE, userPrompt);
        try {
            EvidenceResult result = new EvidenceResult();
            result.setRawContent(content);
            result.setRows(parseEvidenceFromContent(content));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM evidence 结果失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public TenderBasicInfoResult extractTenderBasicInfo(String chunksText) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        String chunks = chunksText == null ? "" : chunksText;
        String content = chatJsonContent(
                SYSTEM_TENDER_BASIC,
                defaultTenderBasicPromptTemplate().replace("{{recalled_chunks}}", chunks));
        try {
            TenderBasicInfoResult result = new TenderBasicInfoResult();
            result.setRawContent(content);
            result.setInfo(parseTenderBasicInfoFromContent(content));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM 招标基础信息结果失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public TenderIssueScanResult scanTenderIssues(String chunksText) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        String chunks = chunksText == null ? "" : chunksText;
        String content = chatJsonContent(
                SYSTEM_TENDER_ISSUE,
                defaultTenderIssueScanPromptTemplate().replace("{{chunksText}}", chunks));
        try {
            TenderIssueScanResult result = parseTenderIssueScanFromContent(content);
            result.setRawContent(content);
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM 逻辑问题和错别字识别结果失败: " + ex.getMessage(), ex);
        }
    }

    private ExtractChecklistResult runExtract(
            String system, String template, String chunksText, String scene) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.llm.api-key");
        }
        String chunks = chunksText == null ? "" : chunksText;
        String userPrompt = template.replace("{{recalled_chunks}}", chunks);
        String content = chatJsonContent(system, userPrompt);
        try {
            ExtractChecklistResult result = new ExtractChecklistResult();
            result.setRawContent(content);
            result.setItems(parseChecklistFromContent(content));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 LLM " + scene + "结果失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 拆分路径：只保留目标主类；若模型误出另一侧则丢弃。
     * 未标主类或主类与目标一致的条目强制为 mainType。
     */
    private static void keepOnlyMainType(ExtractChecklistResult result, String mainType) {
        if (result == null || result.getItems() == null) {
            return;
        }
        String other = "QUALIFICATION".equals(mainType) ? "SCORING" : "QUALIFICATION";
        List<ExtractedRequirement> kept = new ArrayList<>();
        List<ExtractedRequirement> items = result.getItems();
        for (int i = 0; i < items.size(); i++) {
            ExtractedRequirement item = items.get(i);
            if (item == null) {
                continue;
            }
            String mt = item.getMainType();
            if (mt != null && other.equalsIgnoreCase(mt.trim())) {
                continue;
            }
            item.setMainType(mainType);
            item.setMust("QUALIFICATION".equals(mainType));
            item.setCategory(mainType);
            kept.add(item);
        }
        result.setItems(kept);
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
            log.error("调用 LLM 失败 model={} url={} cause={}",
                    modelName, chatUrl, describeThrowable(ex), ex);
            throw new IllegalStateException("调用 LLM 失败: " + describeThrowable(ex), ex);
        }
    }

    /** 避免 InterruptedException 等 getMessage() 为 null 时日志/任务失败原因不可读。 */
    private static String describeThrowable(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        if (ex instanceof InterruptedException) {
            return "请求被中断（常见于服务重启或线程取消）";
        }
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return msg;
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

        // 产物拆分扁平字段：chunkIds 可为 "101,108" 或 [101,108]；与 evidence 抽取的 id 合并
        List<Long> flatChunkIds = parseChunkIdsField(node.get("chunkIds"));
        if (flatChunkIds.isEmpty()) {
            flatChunkIds = parseChunkIdsField(node.get("chunk_ids"));
        }
        if (!flatChunkIds.isEmpty()) {
            List<Long> merged = item.getChunkIds() == null
                    ? new ArrayList<>() : new ArrayList<>(item.getChunkIds());
            for (int i = 0; i < flatChunkIds.size(); i++) {
                Long id = flatChunkIds.get(i);
                if (id != null && !merged.contains(id)) {
                    merged.add(id);
                }
            }
            item.setChunkIds(merged);
        }

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
     * 解析扁平 chunkIds：数字数组，或逗号分隔字符串。
     */
    private List<Long> parseChunkIdsField(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode el = node.get(i);
                if (el == null || el.isNull()) {
                    continue;
                }
                if (el.isNumber()) {
                    result.add(el.asLong());
                } else {
                    String t = el.asText();
                    if (t != null && !t.isBlank()) {
                        try {
                            result.add(Long.parseLong(t.trim()));
                        } catch (NumberFormatException ignored) {
                            // 跳过非法 id
                        }
                    }
                }
            }
            return result;
        }
        if (node.isNumber()) {
            result.add(node.asLong());
            return result;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return result;
        }
        String[] parts = text.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.parseLong(p));
            } catch (NumberFormatException ignored) {
                // 跳过非法 id
            }
        }
        return result;
    }

    /**
     * 解析 bidSearch 产物 C：{"rows":[{"id":"Q1","q1":"...","q2":"...","q3":"","q4":""}]}。
     * 亦兼容 items[] / 纯数组，以及 requirementId、queries 数组。
     */
    List<BidSearchRow> parseBidSearchFromContent(String trimmed) throws IOException {
        JsonNode parsed = objectMapper.readTree(trimmed);
        JsonNode arrayNode = null;
        if (parsed.isArray()) {
            arrayNode = parsed;
        } else if (parsed.isObject()) {
            if (parsed.has("rows") && parsed.get("rows").isArray()) {
                arrayNode = parsed.get("rows");
            } else if (parsed.has("items") && parsed.get("items").isArray()) {
                arrayNode = parsed.get("items");
            }
        }
        if (arrayNode == null) {
            throw new IllegalStateException("bidSearch LLM JSON 须含 rows/items 数组或为纯数组");
        }
        List<BidSearchRow> rows = new ArrayList<>();
        for (int i = 0; i < arrayNode.size(); i++) {
            BidSearchRow row = parseOneBidSearchRow(arrayNode.get(i));
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private BidSearchRow parseOneBidSearchRow(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = textOf(node, "id");
        if (id == null || id.isBlank()) {
            id = textOf(node, "requirementId");
        }
        if (id == null || id.isBlank()) {
            id = textOf(node, "requirement_id");
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        List<String> queries = new ArrayList<>();
        appendNonBlankQuery(queries, textOf(node, "q1"));
        appendNonBlankQuery(queries, textOf(node, "q2"));
        appendNonBlankQuery(queries, textOf(node, "q3"));
        appendNonBlankQuery(queries, textOf(node, "q4"));
        if (queries.isEmpty()) {
            List<String> fromArray = parseBidSearchQueries(node.get("bidSearch"));
            if (fromArray.isEmpty()) {
                fromArray = parseBidSearchQueries(node.get("queries"));
            }
            for (int i = 0; i < fromArray.size(); i++) {
                appendNonBlankQuery(queries, fromArray.get(i));
            }
        }
        BidSearchRow row = new BidSearchRow();
        row.setId(id.trim());
        row.setQueries(queries);
        return row;
    }

    private static void appendNonBlankQuery(List<String> queries, String raw) {
        if (raw == null) {
            return;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return;
        }
        if (t.length() > 30) {
            t = t.substring(0, 30);
        }
        if (!queries.contains(t)) {
            queries.add(t);
        }
    }

    /**
     * 解析 evidence 产物 D：{"rows":[{"id":"Q1","fragmentId":101,"page":12,"quote":"..."}]}。
     */
    List<EvidenceRow> parseEvidenceFromContent(String trimmed) throws IOException {
        JsonNode parsed = objectMapper.readTree(trimmed);
        JsonNode arrayNode = null;
        if (parsed.isArray()) {
            arrayNode = parsed;
        } else if (parsed.isObject()) {
            if (parsed.has("rows") && parsed.get("rows").isArray()) {
                arrayNode = parsed.get("rows");
            } else if (parsed.has("items") && parsed.get("items").isArray()) {
                arrayNode = parsed.get("items");
            } else if (parsed.has("evidence") && parsed.get("evidence").isArray()) {
                arrayNode = parsed.get("evidence");
            }
        }
        if (arrayNode == null) {
            throw new IllegalStateException("evidence LLM JSON 须含 rows/items/evidence 数组或为纯数组");
        }
        List<EvidenceRow> rows = new ArrayList<>();
        for (int i = 0; i < arrayNode.size(); i++) {
            EvidenceRow row = parseOneEvidenceRow(arrayNode.get(i));
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private EvidenceRow parseOneEvidenceRow(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String id = textOf(node, "id");
        if (id == null || id.isBlank()) {
            id = textOf(node, "requirementId");
        }
        if (id == null || id.isBlank()) {
            id = textOf(node, "requirement_id");
        }
        if (id == null || id.isBlank()) {
            return null;
        }
        String quote = textOf(node, "quote");
        if (quote == null || quote.isBlank()) {
            return null;
        }
        Long fragmentId = null;
        if (node.has("fragmentId") && !node.get("fragmentId").isNull()) {
            JsonNode fid = node.get("fragmentId");
            if (fid.isNumber()) {
                fragmentId = fid.asLong();
            } else {
                try {
                    fragmentId = Long.parseLong(fid.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 非法 id
                }
            }
        }
        if (fragmentId == null && node.has("chunkId") && !node.get("chunkId").isNull()) {
            JsonNode cid = node.get("chunkId");
            if (cid.isNumber()) {
                fragmentId = cid.asLong();
            }
        }
        if (fragmentId == null && node.has("chunk_id") && !node.get("chunk_id").isNull()
                && node.get("chunk_id").isNumber()) {
            fragmentId = node.get("chunk_id").asLong();
        }
        if (fragmentId == null) {
            return null;
        }
        Integer page = null;
        if (node.has("page") && !node.get("page").isNull() && node.get("page").isNumber()) {
            page = node.get("page").asInt();
        } else if (node.has("pageNo") && !node.get("pageNo").isNull() && node.get("pageNo").isNumber()) {
            page = node.get("pageNo").asInt();
        }
        EvidenceRow row = new EvidenceRow();
        row.setId(id.trim());
        row.setFragmentId(fragmentId);
        row.setPage(page);
        row.setQuote(truncateByCodePoint(quote.trim(), 200));
        return row;
    }

    TenderBasicInfo parseTenderBasicInfoFromContent(String trimmed) throws IOException {
        JsonNode node = objectMapper.readTree(trimmed);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("招标基础信息 LLM JSON 须为对象");
        }
        TenderBasicInfo info = new TenderBasicInfo();
        info.setTenderNo(parseFieldEvidence(node.get("tenderNo")));
        info.setTenderer(parseFieldEvidence(node.get("tenderer")));
        info.setAgency(parseFieldEvidence(node.get("agency")));
        info.setBudgetAmount(parseFieldEvidence(node.get("budgetAmount")));
        info.setBidDeadline(parseDeadlineEvidence(node.get("bidDeadline")));
        info.setProjectIntro(parseFieldEvidence(node.get("projectIntro")));
        info.setProjectBackground(parseFieldEvidence(node.get("projectBackground")));
        info.setProjectOverview(parseFieldEvidence(node.get("projectOverview")));
        return info;
    }

    TenderIssueScanResult parseTenderIssueScanFromContent(String trimmed) throws IOException {
        JsonNode node = objectMapper.readTree(trimmed);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("逻辑问题和错别字识别 LLM JSON 须为对象");
        }
        JsonNode logicNode = node.get("logicIssues");
        JsonNode typoNode = node.get("typos");
        if (logicNode == null || !logicNode.isArray() || typoNode == null || !typoNode.isArray()) {
            throw new IllegalStateException("逻辑问题和错别字识别 LLM JSON 须包含 logicIssues 和 typos 数组");
        }

        TenderIssueScanResult result = new TenderIssueScanResult();
        List<LogicIssueCandidate> logicIssues = new ArrayList<>();
        for (int i = 0; i < logicNode.size(); i++) {
            LogicIssueCandidate item = parseOneLogicIssue(logicNode.get(i));
            if (item != null) {
                logicIssues.add(item);
            }
        }
        result.setLogicIssues(logicIssues);

        List<TypoCandidate> typos = new ArrayList<>();
        for (int i = 0; i < typoNode.size(); i++) {
            TypoCandidate item = parseOneTypo(typoNode.get(i));
            if (item != null) {
                typos.add(item);
            }
        }
        result.setTypos(typos);
        return result;
    }

    private LogicIssueCandidate parseOneLogicIssue(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        LogicIssueCandidate item = new LogicIssueCandidate();
        item.setTitle(textOf(node, "title"));
        item.setSeverity(textOf(node, "severity"));
        item.setIssueType(textOf(node, "issueType"));
        item.setProblem(textOf(node, "problem"));
        item.setSuggestion(textOf(node, "suggestion"));
        item.setEvidence(parseIssueEvidence(node.get("evidence")));
        return item;
    }

    private TypoCandidate parseOneTypo(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        TypoCandidate item = new TypoCandidate();
        item.setWrongText(textOf(node, "wrongText"));
        item.setSuggestedText(textOf(node, "suggestedText"));
        item.setContext(textOf(node, "context"));
        item.setConfidence(textOf(node, "confidence"));
        item.setSuggestion(textOf(node, "suggestion"));
        item.setEvidence(parseIssueEvidence(node.get("evidence")));
        return item;
    }

    private List<IssueEvidence> parseIssueEvidence(JsonNode node) {
        List<IssueEvidence> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode el = node.get(i);
            if (el == null || !el.isObject()) {
                continue;
            }
            IssueEvidence evidence = new IssueEvidence();
            evidence.setChunkId(longOf(el, "chunkId"));
            evidence.setPageNo(intOf(el, "pageNo"));
            evidence.setQuote(textOf(el, "quote"));
            result.add(evidence);
        }
        return result;
    }

    private FieldEvidence parseFieldEvidence(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        FieldEvidence field = new FieldEvidence();
        field.setValue(textOf(node, "value"));
        field.setQuote(textOf(node, "quote"));
        field.setChunkId(longOf(node, "chunkId"));
        field.setPageNo(intOf(node, "pageNo"));
        return field;
    }

    private DeadlineEvidence parseDeadlineEvidence(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        DeadlineEvidence field = new DeadlineEvidence();
        field.setValue(textOf(node, "value"));
        field.setText(textOf(node, "text"));
        field.setIso(textOf(node, "iso"));
        field.setQuote(textOf(node, "quote"));
        field.setChunkId(longOf(node, "chunkId"));
        field.setPageNo(intOf(node, "pageNo"));
        return field;
    }

    private Long longOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asLong();
    }

    private Integer intOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.asInt();
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
     * 资格条目默认提示词（产物 A）：扁平 items，不含 bidSearch / evidence。
     */
    private static String defaultQualPromptTemplate() {
        return "【任务】从候选招标原文中归纳「资格/准入/否决」类条目。"
                + "只输出资格侧，不要输出评分项、bidSearch、evidence。\n"
                + "【纳入】★号/否则否决/不予受理/废标；须/应/应当/必须等强制性资格、资质、人员、商务、"
                + "技术、报价、格式、证明、电子标、合规要求。含分值/权重/打分的条款一律跳过。\n"
                + "【拆分原则】\n"
                + "- 一条 = 一个独立的、可单独响应的要求\n"
                + "- 同一段落中出现多个独立要求时，必须拆为多条，不得粘连\n"
                + "- 判定「独立要求」的标准：该要求可以单独提供证明材料 / 单独响应 / 单独不满足时产生不同后果\n"
                + "- 仅当同一要求在文档不同位置重复表述时才合并为一条\n"
                + "- 逐句扫描，遇到「须/应/应当/不得/须具备/须提供」等要求性表述时，"
                + "每个独立要求拆为一条；禁止将多个不同要求塞入同一条 content\n"
                + "【子类】判不出→资格要求：资格要求、资质证书、人员要求、废标否决、商务条款、"
                + "技术要求、报价要求、格式封装、证明材料、电子标流程、合规风险。\n"
                + "【输出规则】title≤15字；content≤200字尽量原文；字段未明确填 null；"
                + "chunkIds 填出处 chunkId，多个用逗号拼接（如 \"101,108\"）；禁止嵌套对象。\n"
                + "【约束】只输出 JSON；不编造；无资格内容则 {\"items\":[]}。\n"
                + "【候选原文】\n{{recalled_chunks}}\n"
                + "【输出外形】\n"
                + "{\"items\":[{\"id\":\"Q1\",\"title\":\"...\",\"mainType\":\"资格要求\","
                + "\"subType\":\"...\",\"content\":\"...\",\"chunkIds\":\"101,108\"}]}";
    }

    /**
     * 评分条目默认提示词（产物 B / extract-score-v2）：扁平 items，不含 bidSearch / evidence。
     */
    private static String defaultScorePromptTemplate() {
        return "【任务】从候选招标原文中归纳「评分项」。"
                + "只输出含分值/权重/打分的条目，不要输出资格/否决、bidSearch、evidence。\n"
                + "【子类】判不出→评分项：技术评分、商务评分、价格评分、售后服务评分、业绩评分等。\n"
                + "【输出规则】title≤15字；content≤200字尽量原文；每条须有 fullScore（未明确则 null，禁止用 0 顶替）；"
                + "可另给 scoreSumMatches（各项满分合计与文中总分一致 true/false，缺失则 null）；"
                + "chunkIds 填出处 chunkId，多个用逗号拼接；禁止嵌套对象。\n"
                + "【约束】只输出 JSON；不编造；无评分内容则 {\"items\":[]}。\n"
                + "【候选原文】\n{{recalled_chunks}}\n"
                + "【输出外形】\n"
                + "{\"items\":[{\"id\":\"S1\",\"title\":\"...\",\"mainType\":\"评分项\","
                + "\"subType\":\"...\",\"content\":\"...\",\"fullScore\":15,\"chunkIds\":\"201\"}]}";
    }

    /**
     * bidSearch 默认提示词（产物 C）：输入已编号条目摘要，输出扁平 q1~q4。
     */
    private static String defaultBidSearchPromptTemplate() {
        return "【任务】为每条已编号清单条目生成投标文件侧召回词 q1~q4（可空字符串表示不用）。\n"
                + "【规则】每条有效词 2～4 个；第1个优先用原文术语；其余用投标侧常见措辞"
                + "（承诺函/扫描件/附件/偏离表/方案章节名等）；每词≤30字；只含检索对象，"
                + "不含「否则否决」「不得」等后果语；不同条目检索词不得简单复用拼凑；"
                + "id 必须与输入一致（Q1/S1…）。\n"
                + "【约束】只输出 JSON；不编造条目；输入为空则 {\"rows\":[]}。\n"
                + "【清单条目】\n{{checklist_items}}\n"
                + "【输出外形】\n"
                + "{\"rows\":[{\"id\":\"Q1\",\"q1\":\"...\",\"q2\":\"...\",\"q3\":\"...\",\"q4\":\"\"}]}";
    }

    /**
     * evidence 默认提示词（产物 D）：条目 + 相关候选原文 → 扁平 fragmentId/quote。
     */
    private static String defaultEvidencePromptTemplate() {
        return "【任务】为每条已编号清单条目，从相关候选原文中摘录支撑该条的证据。\n"
                + "【规则】fragmentId 必须等于输入中出现的 chunkId；quote 须是原文连续摘录（≤200字）；"
                + "页码由系统根据 fragmentId 回填，不要输出 page/pageNo；一条可多条证据；无对应原文则该 id 不输出；"
                + "不得编造、不得跨条目混用证据；id 必须与输入一致。\n"
                + "【约束】只输出 JSON；输入为空则 {\"rows\":[]}。\n"
                + "【清单条目】\n{{checklist_items}}\n"
                + "【相关原文】\n{{related_chunks}}\n"
                + "【输出外形】\n"
                + "{\"rows\":[{\"id\":\"Q1\",\"fragmentId\":101,\"quote\":\"...\"}]}";
    }

    private static String defaultTenderBasicPromptTemplate() {
        return "【任务】从候选招标原文中抽取招标基础信息。\n"
                + "【字段】招标编号 tenderNo；招标人 tenderer；招标代理 agency；预算金额 budgetAmount；"
                + "投标截止时间 bidDeadline；项目简介 projectIntro；项目背景 projectBackground；项目概况 projectOverview。\n"
                + "【规则】预算金额不拆分，按原文整体填 value；投标截止时间 text 填原文，"
                + "iso 填 ISO-8601 UTC 时间（示例 2026-08-15T09:00:00Z），无法确定完整年月日时分则 iso=null；"
                + "quote 必须是候选原文连续摘录；chunkId 必须来自候选标签；页码由系统根据 chunkId 回填，不要输出 pageNo；字段未明确填 null。\n"
                + "【约束】只输出 JSON；不得编造；不要输出多余字段；不要用未知/无等占位文字。\n"
                + "【候选原文】\n{{recalled_chunks}}\n"
                + "【输出外形】\n"
                + "{\"tenderNo\":{\"value\":null,\"quote\":null,\"chunkId\":null},"
                + "\"tenderer\":{\"value\":null,\"quote\":null,\"chunkId\":null},"
                + "\"agency\":{\"value\":null,\"quote\":null,\"chunkId\":null},"
                + "\"budgetAmount\":{\"value\":null,\"quote\":null,\"chunkId\":null},"
                + "\"bidDeadline\":{\"text\":null,\"iso\":null,\"quote\":null,\"chunkId\":null},"
                + "\"projectIntro\":{\"value\":null,\"quote\":null,\"chunkId\":null},"
                + "\"projectBackground\":{\"value\":null,\"quote\":null,\"chunkId\":null},"
                + "\"projectOverview\":{\"value\":null,\"quote\":null,\"chunkId\":null}}";
    }

    private static String defaultTenderIssueScanPromptTemplate() {
        return "你是招标文件文本质检助手。请只根据用户提供的招标文件原文片段，识别两类问题：逻辑问题、错别字识别。\n"
                + "重要规则：\n"
                + "1. 只根据输入片段判断，不引用外部法律法规，不输出法律风险。\n"
                + "2. 没有明确原文证据的问题不要输出。\n"
                + "3. 不要编造 chunkId、原文摘录；页码由系统根据 chunkId 回填，evidence 不要输出 pageNo。\n"
                + "4. 不要把公司名称、项目名称、人名、地名、证书名称、产品名称等专有名词轻易判为错别字。\n"
                + "5. 不要把 OCR 断行、页眉页脚、空格、编号格式差异当作错别字。\n"
                + "6. 没发现问题时返回空数组。\n"
                + "7. 只返回 JSON 对象，不要 Markdown，不要解释文字。\n"
                + "逻辑问题识别范围：\n"
                + "- 同一批片段内金额、时间、数量、比例、分值、门槛前后不一致。\n"
                + "- 条款之间存在明显矛盾。\n"
                + "- 评分总分与分项加总明显不一致。\n"
                + "- 投标截止时间、开标时间、保证金截止时间等时间安排明显冲突。\n"
                + "- 同一项目名称、标段名称、文件名称、采购方式、评标方法前后明显不一致。\n"
                + "- 资格门槛、业绩门槛、人员要求与同批片段中的项目规模信息明显不匹配时，可以作为逻辑问题候选输出。\n"
                + "错别字识别范围：\n"
                + "- 明显中文错别字。\n"
                + "- 明显术语错误。\n"
                + "- 明显多字、漏字、重复字。\n"
                + "- 明显影响理解的文字错误。\n"
                + "输出 JSON 格式必须严格如下：\n"
                + "{\"logicIssues\":[{\"title\":\"简短问题标题\",\"severity\":\"HIGH|MEDIUM|LOW\","
                + "\"issueType\":\"AMOUNT_INCONSISTENCY|TIME_CONFLICT|SCORE_SUM_MISMATCH|TERM_INCONSISTENCY|THRESHOLD_INCONSISTENCY|OTHER\","
                + "\"problem\":\"说明问题是什么，只描述输入片段能支持的内容\","
                + "\"suggestion\":\"修改建议；不确定时写空字符串\","
                + "\"evidence\":[{\"chunkId\":123,\"quote\":\"必须逐字摘录输入片段中的关键原文\"}]}],"
                + "\"typos\":[{\"wrongText\":\"疑似错误文字\",\"suggestedText\":\"建议文字\","
                + "\"context\":\"包含错误文字的短上下文\",\"confidence\":\"HIGH|MEDIUM|LOW\","
                + "\"suggestion\":\"修改建议；不确定时写空字符串\","
                + "\"evidence\":[{\"chunkId\":123,\"quote\":\"必须逐字摘录输入片段中的关键原文\"}]}]}\n"
                + "现在请扫描以下招标文件片段：\n{{chunksText}}";
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
                + "bidEvidence.quote 必须从【投标候选原文】逐字连续摘录，禁止改写、概括或补字；"
                + "页码由系统根据 fragmentId 回填，bidEvidence 不要输出 page/pageNo；"
                + "做不到则 status=无法判断，judgement 不得写「符合」；"
                + "多处证据全部保留；不同条目证据不得混用；未明确字段填 null。\n"
                + "【输出字段】requirementId,title,reviewType(响应性审查|废标风险|评分建议),"
                + "status(仅资格),vetoRisk,judgement,bidEvidence([{fragmentId,quote}]),note,"
                + "suggestedScore,fullScore,deductionReason(仅评分且 fullScore 非 null)。\n"
                + "【约束】只输出 JSON 本体；不编造；除无法判断外须有 bidEvidence。";
    }
}
