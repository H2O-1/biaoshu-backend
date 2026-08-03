package com.bidding.ai.infrastructure.rerank;

import com.bidding.common.port.RerankPort;
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

class HttpRerankPortTest {

    private HttpServer server;
    private int port;
    private HttpRerankPort rerankPort;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/reranks", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] resp = ("{\"object\":\"list\",\"results\":["
                    + "{\"index\":2,\"relevance_score\":0.9},"
                    + "{\"index\":0,\"relevance_score\":0.5}"
                    + "],\"model\":\"qwen3-rerank\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        rerankPort = new HttpRerankPort(
                new ObjectMapper(),
                "http://127.0.0.1:" + port,
                "test-key",
                "qwen3-rerank",
                "给定检索查询，找出能够回答该查询的相关段落。",
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
    void rerankReturnsIndexAndScore() throws Exception {
        List<String> docs = new ArrayList<>();
        docs.add("a");
        docs.add("b");
        docs.add("c");
        List<RerankPort.RerankHit> hits = rerankPort.rerank("什么是排序", docs, 2, null);
        assertEquals(2, hits.size());
        assertEquals(2, hits.get(0).getIndex());
        assertEquals(0.9f, hits.get(0).getScore(), 0.0001f);
        assertEquals(0, hits.get(1).getIndex());

        JsonNode req = new ObjectMapper().readTree(lastBody.get());
        assertEquals("qwen3-rerank", req.path("model").asText());
        assertEquals(2, req.path("top_n").asInt());
        assertEquals(3, req.path("documents").size());
        assertTrue(req.path("instruct").asText().length() > 0);
    }

    @Test
    void rerankRequiresApiKey() {
        HttpRerankPort noKey = new HttpRerankPort(
                new ObjectMapper(),
                "http://127.0.0.1:" + port,
                "",
                "qwen3-rerank",
                "x",
                3000L,
                10000L
        );
        List<String> docs = new ArrayList<>();
        docs.add("a");
        assertThrows(IllegalStateException.class, () -> noKey.rerank("q", docs, 1, null));
    }
}
