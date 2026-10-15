package com.bidding.ai.infrastructure.ocr;

import com.bidding.common.port.DocumentOcrPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpDocumentOcrPortTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void ocrPdfPostsMultipartAndMapsResult() {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/ocr/pdf", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] resp = ("""
                    {
                      "page_count": 2,
                      "duration_ms": 1234,
                      "engine": "rapidocr",
                      "pages": [
                        {
                          "page_no": 1,
                          "text": "第一页文字",
                          "confidence": 0.95,
                          "source": "ocr",
                          "has_images": true
                        }
                      ]
                    }
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        HttpDocumentOcrPort port = new HttpDocumentOcrPort(new ObjectMapper(), properties());
        DocumentOcrPort.OcrPdfResult result = port.ocrPdf(new byte[]{1, 2, 3}, true);

        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        assertTrue(requestBody.get().contains("name=\"file\"; filename=\"document.pdf\""));
        assertTrue(requestBody.get().contains("Content-Type: application/pdf"));
        assertTrue(requestBody.get().contains("name=\"force_ocr\""));
        assertTrue(requestBody.get().contains("true"));
        assertEquals(2, result.getPageCount());
        assertEquals(1234, result.getDurationMs());
        assertEquals("rapidocr", result.getEngine());
        assertEquals(1, result.getPages().size());
        assertEquals(1, result.getPages().get(0).getPageNo());
        assertEquals("第一页文字", result.getPages().get(0).getText());
        assertEquals(0.95d, result.getPages().get(0).getConfidence());
        assertEquals("ocr", result.getPages().get(0).getSource());
        assertTrue(result.getPages().get(0).isHasImages());
    }

    @Test
    void ocrPdfRejectsHttpErrorWithCleanMessage() {
        server.createContext("/ocr/pdf", exchange -> {
            byte[] resp = "{\"detail\":\"request timed out\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        HttpDocumentOcrPort port = new HttpDocumentOcrPort(new ObjectMapper(), properties());
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> port.ocrPdf(new byte[]{1}, false));

        assertEquals("文档 OCR 识别失败，请稍后重试或检查 OCR 服务", ex.getMessage());
    }

    @Test
    void ocrPdfRejectsInvalidPageNo() {
        server.createContext("/ocr/pdf", exchange -> {
            byte[] resp = ("""
                    {
                      "page_count": 1,
                      "pages": [
                        {"page_no": 0, "text": "异常页码", "source": "ocr"}
                      ]
                    }
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();

        HttpDocumentOcrPort port = new HttpDocumentOcrPort(new ObjectMapper(), properties());
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> port.ocrPdf(new byte[]{1}, false));

        assertEquals("文档 OCR 返回页码异常", ex.getMessage());
    }

    private OcrProperties properties() {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeoutMs(3000L);
        properties.setReadTimeoutMs(10000L);
        return properties;
    }
}
