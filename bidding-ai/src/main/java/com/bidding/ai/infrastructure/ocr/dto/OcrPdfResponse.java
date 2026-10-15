package com.bidding.ai.infrastructure.ocr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OcrPdfResponse {

    @JsonProperty("page_count")
    private int pageCount;
    @JsonProperty("duration_ms")
    private Integer durationMs;
    private String engine;
    private List<OcrPageResponse> pages;

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public List<OcrPageResponse> getPages() {
        return pages;
    }

    public void setPages(List<OcrPageResponse> pages) {
        this.pages = pages;
    }
}
