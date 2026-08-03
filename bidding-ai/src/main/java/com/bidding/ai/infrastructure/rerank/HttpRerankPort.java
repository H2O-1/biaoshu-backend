package com.bidding.ai.infrastructure.rerank;

import com.bidding.common.port.RerankPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调阿里云百炼 OpenAI 兼容重排（qwen3-rerank）。
 * 路径为 compatible-api/v1/reranks（与 embedding 的 compatible-mode 不同）。
 * 单次最多 500 段；响应 results[].index / relevance_score。
 */
@Component
public class HttpRerankPort implements RerankPort {

    private static final Logger log = LoggerFactory.getLogger(HttpRerankPort.class);
    private static final int MAX_DOCUMENTS = 500;
    /** 单段过长时截断，避免超 Token（粗按字符估）。 */
    private static final int MAX_DOC_CHARS = 6000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String rerankUrl;
    private final String apiKey;
    private final String modelName;
    private final String defaultInstruct;
    private final Duration requestTimeout;

    public HttpRerankPort(
            ObjectMapper objectMapper,
            @Value("${bidding.rerank.base-url}") String baseUrl,
            @Value("${bidding.rerank.api-key:${bidding.index.embedding.api-key:${DASHSCOPE_API_KEY:}}}") String apiKey,
            @Value("${bidding.rerank.model:qwen3-rerank}") String modelName,
            @Value("${bidding.rerank.default-instruct:给定检索查询，找出能够回答该查询的相关段落。}")
            String defaultInstruct,
            @Value("${bidding.rerank.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${bidding.rerank.read-timeout-ms:120000}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.rerankUrl = normalized + "/reranks";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = modelName == null || modelName.isBlank() ? "qwen3-rerank" : modelName.trim();
        this.defaultInstruct = defaultInstruct == null || defaultInstruct.isBlank()
                ? "给定检索查询，找出能够回答该查询的相关段落。"
                : defaultInstruct.trim();
        this.requestTimeout = Duration.ofMillis(readTimeoutMs < 1 ? 120000L : readTimeoutMs);
        Duration connectTimeout = Duration.ofMillis(connectTimeoutMs < 1 ? 3000L : connectTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        if (this.apiKey.isEmpty()) {
            log.warn("HttpRerankPort 已启用但未配置 bidding.rerank.api-key（DASHSCOPE_API_KEY）");
        } else {
            log.info("HttpRerankPort 已启用，url={}, model={}", this.rerankUrl, this.modelName);
        }
    }

    @Override
    public List<RerankHit> rerank(String query, List<String> documents, int topN, String instruct) {
        List<RerankHit> empty = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return empty;
        }
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.rerank.api-key");
        }
        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isEmpty()) {
            throw new IllegalArgumentException("rerank query 不能为空");
        }

        List<String> docs = RerankPort.copyDocuments(documents);
        if (docs.size() > MAX_DOCUMENTS) {
            List<String> trimmed = new ArrayList<>();
            for (int i = 0; i < MAX_DOCUMENTS; i++) {
                trimmed.add(docs.get(i));
            }
            docs = trimmed;
            log.warn("rerank 候选超过 {}，已截断", MAX_DOCUMENTS);
        }
        for (int i = 0; i < docs.size(); i++) {
            docs.set(i, truncate(docs.get(i)));
        }

        int safeTopN = topN;
        if (safeTopN < 1) {
            safeTopN = docs.size();
        }
        if (safeTopN > docs.size()) {
            safeTopN = docs.size();
        }

        String safeInstruct = instruct == null || instruct.isBlank() ? defaultInstruct : instruct.trim();

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            root.put("query", safeQuery);
            root.put("top_n", safeTopN);
            root.put("instruct", safeInstruct);
            ArrayNode docsNode = root.putArray("documents");
            for (int i = 0; i < docs.size(); i++) {
                docsNode.add(docs.get(i));
            }
            byte[] body = objectMapper.writeValueAsBytes(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rerankUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "DashScope rerank HTTP " + response.statusCode() + " " + truncateBody(response.body()));
            }
            return parseResults(response.body(), docs.size());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("调用 DashScope rerank 失败: " + ex.getMessage(), ex);
        }
    }

    private List<RerankHit> parseResults(byte[] body, int docCount) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode resultsNode = root.get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            throw new IllegalStateException("rerank 响应缺少 results: " + truncateBody(body));
        }
        List<RerankHit> hits = new ArrayList<>();
        for (int i = 0; i < resultsNode.size(); i++) {
            JsonNode item = resultsNode.get(i);
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= docCount) {
                throw new IllegalStateException("rerank index 越界: " + index);
            }
            float score = (float) item.path("relevance_score").asDouble(0);
            RerankHit hit = new RerankHit();
            hit.setIndex(index);
            hit.setScore(score);
            hits.add(hit);
        }
        return hits;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_DOC_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DOC_CHARS);
    }

    private static String truncateBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String detail = new String(body, StandardCharsets.UTF_8);
        if (detail.length() > 500) {
            return detail.substring(0, 500);
        }
        return detail;
    }
}
