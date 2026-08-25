package com.bidding.ai.infrastructure.ocr;

import com.bidding.ai.infrastructure.ocr.dto.OcrPageResponse;
import com.bidding.ai.infrastructure.ocr.dto.OcrPdfResponse;
import com.bidding.common.port.DocumentOcrPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OCR HTTP 客户端，调用外置 RapidOCR 服务。
 */
@Component
@EnableConfigurationProperties(OcrProperties.class)
@ConditionalOnProperty(prefix = "bidding.ocr", name = "enabled", havingValue = "true")
public class HttpDocumentOcrPort implements DocumentOcrPort {

    private static final Logger log = LoggerFactory.getLogger(HttpDocumentOcrPort.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String ocrPdfUrl;
    private final Duration requestTimeout;

    public HttpDocumentOcrPort(ObjectMapper objectMapper, OcrProperties properties) {
        this.objectMapper = objectMapper;
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("OCR 服务地址未配置");
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        long readTimeoutMs = properties.getReadTimeoutMs() < 1 ? 120000L : properties.getReadTimeoutMs();
        long connectTimeoutMs = properties.getConnectTimeoutMs() < 1 ? 5000L : properties.getConnectTimeoutMs();
        this.ocrPdfUrl = baseUrl + "/ocr/pdf";
        this.requestTimeout = Duration.ofMillis(readTimeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        log.info("HttpDocumentOcrPort 已启用，url={}", this.ocrPdfUrl);
    }

    @Override
    public OcrPdfResult ocrPdf(byte[] pdfBytes, boolean forceOcr) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 内容为空，无法 OCR");
        }
        String boundary = "----bidding-ocr-" + UUID.randomUUID();
        byte[] body = buildPdfMultipartBody(boundary, pdfBytes, forceOcr);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ocrPdfUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("OCR HTTP 调用失败 status={} body={}", response.statusCode(), truncateBody(response.body()));
                throw new IllegalStateException("文档 OCR 识别失败，请稍后重试或检查 OCR 服务");
            }
            return toPortResult(objectMapper.readValue(response.body(), OcrPdfResponse.class));
        } catch (HttpTimeoutException ex) {
            log.warn("OCR HTTP 调用超时 url={}", ocrPdfUrl, ex);
            throw new IllegalStateException("文档 OCR 识别超时，请稍后重试", ex);
        } catch (IOException ex) {
            log.warn("OCR HTTP 调用异常 url={} cause={}", ocrPdfUrl, describeThrowable(ex), ex);
            throw new IllegalStateException("文档 OCR 识别失败，请稍后重试或检查 OCR 服务", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("OCR HTTP 调用被中断 url={}", ocrPdfUrl, ex);
            throw new IllegalStateException("文档 OCR 识别被中断，请重新发起索引", ex);
        }
    }

    private OcrPdfResult toPortResult(OcrPdfResponse response) {
        if (response == null) {
            throw new IllegalStateException("OCR 服务返回为空");
        }
        if (response.getPages() == null || response.getPages().isEmpty()) {
            throw new IllegalStateException("文档 OCR 未识别到有效文本");
        }
        OcrPdfResult result = new OcrPdfResult();
        result.setPageCount(response.getPageCount());
        result.setDurationMs(response.getDurationMs());
        result.setEngine(response.getEngine());
        List<OcrPageResult> pages = new ArrayList<>();
        for (OcrPageResponse page : response.getPages()) {
            if (page == null || page.getPageNo() < 1) {
                throw new IllegalStateException("文档 OCR 返回页码异常");
            }
            OcrPageResult item = new OcrPageResult();
            item.setPageNo(page.getPageNo());
            item.setText(page.getText());
            item.setConfidence(page.getConfidence());
            item.setSource(page.getSource());
            item.setHasImages(page.isHasImages());
            pages.add(item);
        }
        result.setPages(pages);
        return result;
    }

    private static byte[] buildPdfMultipartBody(String boundary, byte[] pdfBytes, boolean forceOcr) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeText(out, "--" + boundary + "\r\n");
            writeText(out, "Content-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\n");
            writeText(out, "Content-Type: application/pdf\r\n\r\n");
            out.write(pdfBytes);
            writeText(out, "\r\n");
            writeText(out, "--" + boundary + "\r\n");
            writeText(out, "Content-Disposition: form-data; name=\"force_ocr\"\r\n\r\n");
            writeText(out, Boolean.toString(forceOcr));
            writeText(out, "\r\n");
            writeText(out, "--" + boundary + "--\r\n");
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("构造 OCR 请求失败", ex);
        }
    }

    private static void writeText(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String describeThrowable(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message;
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
