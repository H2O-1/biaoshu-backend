package com.bidding.ai.infrastructure.embedding;

import com.bidding.common.port.EmbeddingPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 调阿里云百炼 OpenAI 兼容 Embedding（text-embedding-v4，默认 1024 维）。
 * 单次最多 10 条；响应按 data[].index 还原顺序。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.index", name = "mode", havingValue = "real")
public class HttpEmbeddingPort implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingPort.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String embedUrl;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;
    private final Duration requestTimeout;

    public HttpEmbeddingPort(
            ObjectMapper objectMapper,
            @Value("${bidding.index.embedding.base-url}") String baseUrl,
            @Value("${bidding.index.embedding.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${bidding.index.embedding.model:text-embedding-v4}") String modelName,
            @Value("${bidding.index.embedding.dimensions:1024}") int dimensions,
            @Value("${bidding.index.embedding.batch-size:10}") int batchSize,
            @Value("${bidding.index.embedding.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${bidding.index.embedding.read-timeout-ms:120000}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        this.embedUrl = normalized + "/embeddings";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = modelName == null || modelName.isBlank() ? "text-embedding-v4" : modelName.trim();
        this.dimensions = dimensions < 1 ? 1024 : dimensions;
        this.batchSize = batchSize < 1 ? 10 : Math.min(batchSize, 10);
        this.requestTimeout = Duration.ofMillis(readTimeoutMs < 1 ? 120000L : readTimeoutMs);
        Duration connectTimeout = Duration.ofMillis(connectTimeoutMs < 1 ? 3000L : connectTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        if (this.apiKey.isEmpty()) {
            log.warn("HttpEmbeddingPort 已启用但未配置 bidding.index.embedding.api-key（DASHSCOPE_API_KEY）");
        } else {
            log.info("HttpEmbeddingPort 已启用，url={}, model={}, dim={}", this.embedUrl, this.modelName, this.dimensions);
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> all = new ArrayList<>();
        if (texts == null || texts.isEmpty()) {
            return all;
        }
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("未配置 DASHSCOPE_API_KEY / bidding.index.embedding.api-key");
        }
        int from = 0;
        while (from < texts.size()) {
            int to = from + batchSize;
            if (to > texts.size()) {
                to = texts.size();
            }
            List<String> batch = new ArrayList<>();
            for (int i = from; i < to; i++) {
                batch.add(texts.get(i));
            }
            List<float[]> part = embedBatch(batch);
            for (int i = 0; i < part.size(); i++) {
                all.add(part.get(i));
            }
            from = to;
        }
        return all;
    }

    @Override
    public int dimension() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    private List<float[]> embedBatch(List<String> batch) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            root.put("dimensions", dimensions);
            root.put("encoding_format", "float");
            ArrayNode inputNode = root.putArray("input");
            for (int i = 0; i < batch.size(); i++) {
                String text = batch.get(i);
                inputNode.add(text == null ? "" : text);
            }
            byte[] body = objectMapper.writeValueAsBytes(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(embedUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "DashScope embedding HTTP " + response.statusCode() + " " + truncateBody(response.body()));
            }
            return parseEmbeddings(response.body(), batch.size());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("调用 DashScope embedding 失败: " + ex.getMessage(), ex);
        }
    }

    private List<float[]> parseEmbeddings(byte[] body, int expectedCount) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode dataNode = root.get("data");
        if (dataNode == null || !dataNode.isArray()) {
            throw new IllegalStateException("embedding 响应缺少 data 数组: " + truncateBody(body));
        }
        if (dataNode.size() != expectedCount) {
            throw new IllegalStateException(
                    "embedding 条数不符: 返回 " + dataNode.size() + "，期望 " + expectedCount);
        }

        float[][] ordered = new float[expectedCount][];
        for (int i = 0; i < dataNode.size(); i++) {
            JsonNode item = dataNode.get(i);
            int index = item.path("index").asInt(i);
            if (index < 0 || index >= expectedCount) {
                throw new IllegalStateException("embedding index 越界: " + index);
            }
            JsonNode embeddingNode = item.get("embedding");
            if (embeddingNode == null || !embeddingNode.isArray() || embeddingNode.size() != dimensions) {
                throw new IllegalStateException(
                        "第 " + index + " 条向量长度不是 " + dimensions
                                + "（实际 " + (embeddingNode == null ? 0 : embeddingNode.size()) + "）");
            }
            float[] vector = new float[dimensions];
            for (int d = 0; d < dimensions; d++) {
                vector[d] = (float) embeddingNode.get(d).asDouble();
            }
            ordered[index] = vector;
        }

        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) {
            if (ordered[i] == null) {
                throw new IllegalStateException("embedding 缺少 index=" + i);
            }
            vectors.add(ordered[i]);
        }
        return vectors;
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
