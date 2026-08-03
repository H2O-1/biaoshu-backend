package com.bidding.common.port;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 PDF / Word 等文件抽取纯文本（实现在 ai 模块）。
 */
public interface DocumentTextExtractPort {

    /**
     * 按扩展名抽取文本。
     *
     * @param fileExt 小写扩展名，如 pdf / docx
     * @param content 文件流（本方法不关闭流，由调用方关闭）
     */
    ExtractResult extract(String fileExt, InputStream content);

    /** 抽取结果（含可选目录 Outline，供章节切块）。 */
    class ExtractResult {
        private String fullText;
        private List<PageText> pages = new ArrayList<>();
        private List<OutlineItem> outline = new ArrayList<>();
        private String warning;

        public boolean isBlank() {
            return fullText == null || fullText.trim().isEmpty();
        }

        public String getFullText() {
            return fullText;
        }

        public void setFullText(String fullText) {
            this.fullText = fullText;
        }

        public List<PageText> getPages() {
            return pages;
        }

        public void setPages(List<PageText> pages) {
            this.pages = pages;
        }

        public List<OutlineItem> getOutline() {
            return outline;
        }

        public void setOutline(List<OutlineItem> outline) {
            this.outline = outline;
        }

        public String getWarning() {
            return warning;
        }

        public void setWarning(String warning) {
            this.warning = warning;
        }
    }

    /** 单页文本（PDF 有页；Word 可能只有逻辑页）。 */
    class PageText {
        private int pageNo;
        private String text;

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
    }

    /** 目录/书签项（PDF Outline 或 Word 标题样式）。 */
    class OutlineItem {
        private String title;
        private int pageNo;
        private int level;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public int getPageNo() {
            return pageNo;
        }

        public void setPageNo(int pageNo) {
            this.pageNo = pageNo;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }
    }
}
