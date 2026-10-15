package com.bidding.common.port;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档 OCR 识别（实现在 ai 模块，供索引阶段增强 PDF 文本抽取）。
 */
public interface DocumentOcrPort {

    /**
     * 对 PDF 执行 OCR，返回页级文本。
     *
     * @param pdfBytes PDF 文件字节
     * @param forceOcr 是否强制整份 PDF 使用 OCR
     */
    OcrPdfResult ocrPdf(byte[] pdfBytes, boolean forceOcr);

    /** PDF OCR 结果。 */
    class OcrPdfResult {
        private int pageCount;
        private Integer durationMs;
        private String engine;
        private List<OcrPageResult> pages = new ArrayList<>();

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

        public List<OcrPageResult> getPages() {
            return pages;
        }

        public void setPages(List<OcrPageResult> pages) {
            this.pages = pages;
        }
    }

    /** 单页 OCR 结果。 */
    class OcrPageResult {
        private int pageNo;
        private String text;
        private Double confidence;
        private String source;
        private boolean hasImages;

        public int getPageNo() {
            return pageNo;
        }

        public void setPageNo(int pageNo) {
            this.pageNo = pageNo;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public Double getConfidence() {
            return confidence;
        }

        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public boolean isHasImages() {
            return hasImages;
        }

        public void setHasImages(boolean hasImages) {
            this.hasImages = hasImages;
        }
    }
}
