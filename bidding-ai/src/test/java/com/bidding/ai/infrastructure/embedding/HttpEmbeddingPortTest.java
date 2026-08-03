package com.bidding.ai.infrastructure.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpEmbeddingPortTest {

    private HttpServer server;
    private int port;
    private HttpEmbeddingPort embeddingPort;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/embeddings", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] req = exchange.getRequestBody().readAllBytes();
            lastBody.set(new String(req, StandardCharsets.UTF_8));
            int textCount = countInput(lastBody.get());

            StringBuilder sb = new StringBuilder();
            sb.append("{\"object\":\"list\",\"data\":[");
            for (int i = 0; i < textCount; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                // 故意倒序返回，验证按 index 还原
                int index = textCount - 1 - i;
                sb.append("{\"object\":\"embedding\",\"index\":").append(index).append(",\"embedding\":[");
                for (int d = 0; d < 1024; d++) {
                    if (d > 0) {
                        sb.append(',');
                    }
                    sb.append(d == 0 ? (0.01 * (index + 1)) : 0.0);
                }
                sb.append("]}");
            }
            sb.append("],\"model\":\"text-embedding-v4\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}");
            byte[] resp = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        embeddingPort = new HttpEmbeddingPort(
                new ObjectMapper(),
                "http://127.0.0.1:" + port,
                "test-api-key",
                "text-embedding-v4",
                1024,
                10,
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
    void embedReturns1024DimInOrder() throws Exception {
        List<String> texts = new ArrayList<>();
        texts.add("资格");
        texts.add("工期");
        List<float[]> vectors = embeddingPort.embed(texts);
        assertEquals(2, vectors.size());
        assertEquals(1024, vectors.get(0).length);
        assertEquals(1024, embeddingPort.dimension());
        assertEquals("text-embedding-v4", embeddingPort.modelName());
        assertEquals(0.01f, vectors.get(0)[0], 0.0001f);
        assertEquals(0.02f, vectors.get(1)[0], 0.0001f);
        assertEquals("Bearer test-api-key", lastAuth.get());

        JsonNode req = new ObjectMapper().readTree(lastBody.get());
        assertEquals("text-embedding-v4", req.path("model").asText());
        assertEquals(1024, req.path("dimensions").asInt());
        assertEquals(2, req.path("input").size());
    }

    @Test
    void embedRejectsWrongDim() throws IOException {
        server.removeContext("/embeddings");
        server.createContext("/embeddings", exchange -> {
            byte[] resp = "{\"data\":[{\"index\":0,\"embedding\":[1,2,3,4,5,6,7,8]}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        List<String> texts = new ArrayList<>();
        texts.add("a");
        assertThrows(IllegalStateException.class, () -> embeddingPort.embed(texts));
    }

    @Test
    void embedRequiresApiKey() {
        HttpEmbeddingPort noKey = new HttpEmbeddingPort(
                new ObjectMapper(),
                "http://127.0.0.1:" + port,
                "",
                "text-embedding-v4",
                1024,
                10,
                3000L,
                10000L
        );
        List<String> texts = new ArrayList<>();
        texts.add("a");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> noKey.embed(texts));
        assertTrue(ex.getMessage().contains("DASHSCOPE_API_KEY"));
    }

    private static int countInput(String reqJson) {
        int idx = reqJson.indexOf("\"input\"");
        if (idx < 0) {
            return 0;
        }
        int bracket = reqJson.indexOf('[', idx);
        int end = reqJson.indexOf(']', bracket);
        if (bracket < 0 || end <= bracket) {
            return 0;
        }
        String inner = reqJson.substring(bracket + 1, end).trim();
        if (inner.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == ',') {
                count++;
            }
        }
        return count;
    }
}
