package com.bidding.ai.infrastructure.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR 服务配置。
 */
@ConfigurationProperties(prefix = "bidding.ocr")
public class OcrProperties {

    private boolean enabled;
    private String baseUrl;
    private int minTotalChars = 50;
    private int minPageChars = 30;
    private long connectTimeoutMs = 5000L;
    private long readTimeoutMs = 120000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getMinTotalChars() {
        return minTotalChars;
    }

    public void setMinTotalChars(int minTotalChars) {
        this.minTotalChars = minTotalChars;
    }

    public int getMinPageChars() {
        return minPageChars;
    }

    public void setMinPageChars(int minPageChars) {
        this.minPageChars = minPageChars;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
