package com.bidding.ai.infrastructure.extract;

import com.bidding.common.port.DocumentTextExtractPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PDF（PDFBox）+ Word docx（POI）文本抽取。
 * PDF 尽量读取 Outline；Word 用标题样式生成伪目录。
 */
@Component
public class PdfWordTextExtractPort implements DocumentTextExtractPort {

    static {
        // 招标 docx 常含高度压缩的 EMF/WMF 图，默认 MIN_INFLATE_RATIO=0.01 会误判 zip bomb
        ZipSecureFile.setMinInflateRatio(0.001d);
    }

    @Override
    public ExtractResult extract(String fileExt, InputStream content) {
        if (content == null) {
            throw new IllegalArgumentException("文件流为空");
        }
        String ext = normalizeExt(fileExt);
        if ("pdf".equals(ext)) {
            return extractPdf(content);
        }
        if ("docx".equals(ext)) {
            return extractDocx(content);
        }
        if ("doc".equals(ext)) {
            throw new IllegalArgumentException("暂不支持老格式 .doc，请转换为 docx 或 PDF");
        }
        if ("xls".equals(ext) || "xlsx".equals(ext)) {
            throw new IllegalArgumentException("第一版索引不支持 Excel，请使用 PDF 或 Word(docx)");
        }
        throw new IllegalArgumentException("不支持的文件类型: " + ext);
    }

    private ExtractResult extractPdf(InputStream content) {
        try (PDDocument document = PDDocument.load(content)) {
            if (document.isEncrypted()) {
                throw new IllegalStateException("PDF 已加密，无法抽取文本");
            }
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            List<PageText> pages = new ArrayList<>();
            StringBuilder full = new StringBuilder();

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText == null) {
                    pageText = "";
                }
                pageText = pageText.trim();
                PageText pt = new PageText();
                pt.setPageNo(page);
                pt.setText(pageText);
                pages.add(pt);
                if (!pageText.isEmpty()) {
                    if (full.length() > 0) {
                        full.append('\n');
                    }
                    full.append(pageText);
                }
            }

            ExtractResult result = new ExtractResult();
            result.setFullText(full.toString());
            result.setPages(pages);
            result.setOutline(readPdfOutline(document));
            if (result.isBlank()) {
                result.setWarning("未能抽取到文本，可能是扫描件（本期不支持 OCR）");
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 PDF 失败: " + ex.getMessage(), ex);
        }
    }

    private List<OutlineItem> readPdfOutline(PDDocument document) {
        List<OutlineItem> items = new ArrayList<>();
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            return items;
        }
        PDOutlineItem root = outline.getFirstChild();
        collectPdfOutline(document, root, 1, items);
        return items;
    }

    private void collectPdfOutline(PDDocument document,
                                   PDOutlineItem item,
                                   int level,
                                   List<OutlineItem> out) {
        PDOutlineItem current = item;
        while (current != null) {
            String title = current.getTitle();
            int pageNo = resolveOutlinePageNo(document, current);
            if (title != null) {
                title = title.trim();
            }
            if (title != null && !title.isEmpty() && pageNo > 0) {
                OutlineItem oi = new OutlineItem();
                oi.setTitle(title);
                oi.setPageNo(pageNo);
                oi.setLevel(level);
                out.add(oi);
            }
            PDOutlineItem child = current.getFirstChild();
            if (child != null) {
                collectPdfOutline(document, child, level + 1, out);
            }
            current = current.getNextSibling();
        }
    }

    /**
     * PDFBox 2.x：书签可能挂 Destination 或 GoTo Action，没有 3.x 的 findDestination。
     */
    private int resolveOutlinePageNo(PDDocument document, PDOutlineItem item) {
        try {
            PDPageDestination pageDestination = toPageDestination(document, item);
            if (pageDestination == null) {
                return -1;
            }
            PDPage page = pageDestination.getPage();
            if (page != null) {
                int index = document.getPages().indexOf(page);
                if (index >= 0) {
                    return index + 1;
                }
            }
            int zeroBased = pageDestination.retrievePageNumber();
            if (zeroBased >= 0) {
                return zeroBased + 1;
            }
        } catch (IOException ignored) {
            // 个别书签无法解析页码时跳过
        }
        return -1;
    }

    private PDPageDestination toPageDestination(PDDocument document, PDOutlineItem item) throws IOException {
        PDDestination destination = item.getDestination();
        PDPageDestination fromDest = asPageDestination(document, destination);
        if (fromDest != null) {
            return fromDest;
        }
        PDAction action = item.getAction();
        if (action instanceof PDActionGoTo) {
            PDDestination goToDest = ((PDActionGoTo) action).getDestination();
            return asPageDestination(document, goToDest);
        }
        return null;
    }

    private PDPageDestination asPageDestination(PDDocument document, PDDestination destination) throws IOException {
        if (destination instanceof PDPageDestination) {
            return (PDPageDestination) destination;
        }
        if (destination instanceof PDNamedDestination) {
            return document.getDocumentCatalog()
                    .findNamedDestinationPage((PDNamedDestination) destination);
        }
        return null;
    }

    private ExtractResult extractDocx(InputStream content) {
        try (XWPFDocument document = new XWPFDocument(content)) {
            StringBuilder full = new StringBuilder();
            List<OutlineItem> outline = new ArrayList<>();
            List<XWPFParagraph> paragraphs = document.getParagraphs();

            for (int i = 0; i < paragraphs.size(); i++) {
                XWPFParagraph paragraph = paragraphs.get(i);
                String text = paragraph.getText();
                if (text == null) {
                    continue;
                }
                text = text.trim();
                if (text.isEmpty()) {
                    continue;
                }

                int headingLevel = detectHeadingLevel(paragraph);
                if (headingLevel > 0) {
                    OutlineItem oi = new OutlineItem();
                    oi.setTitle(text);
                    // Word 无物理页时，用出现顺序作为逻辑页号，便于章节切分
                    oi.setPageNo(outline.size() + 1);
                    oi.setLevel(headingLevel);
                    outline.add(oi);
                }

                if (full.length() > 0) {
                    full.append('\n');
                }
                full.append(text);
            }

            // 表格内文字一并抽取（不参与标题目录）
            List<org.apache.poi.xwpf.usermodel.XWPFTable> tables = document.getTables();
            for (int t = 0; t < tables.size(); t++) {
                org.apache.poi.xwpf.usermodel.XWPFTable table = tables.get(t);
                List<org.apache.poi.xwpf.usermodel.XWPFTableRow> rows = table.getRows();
                for (int r = 0; r < rows.size(); r++) {
                    org.apache.poi.xwpf.usermodel.XWPFTableRow row = rows.get(r);
                    List<org.apache.poi.xwpf.usermodel.XWPFTableCell> cells = row.getTableCells();
                    for (int c = 0; c < cells.size(); c++) {
                        String cellText = cells.get(c).getText();
                        if (cellText == null) {
                            continue;
                        }
                        cellText = cellText.trim();
                        if (cellText.isEmpty()) {
                            continue;
                        }
                        if (full.length() > 0) {
                            full.append('\n');
                        }
                        full.append(cellText);
                    }
                }
            }

            ExtractResult result = new ExtractResult();
            result.setFullText(full.toString());
            result.setOutline(outline);

            // Word：按标题拆成「逻辑页」，无标题则整篇一页
            List<PageText> pages = buildWordLogicalPages(paragraphs, outline);
            result.setPages(pages);

            if (result.isBlank()) {
                result.setWarning("Word 文档未抽取到文本");
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 Word 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将 Word 段落按标题切成逻辑页，便于与 PDF Outline 共用章节切块逻辑。
     */
    private List<PageText> buildWordLogicalPages(List<XWPFParagraph> paragraphs,
                                                 List<OutlineItem> outline) {
        List<PageText> pages = new ArrayList<>();
        if (outline == null || outline.isEmpty()) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < paragraphs.size(); i++) {
                String text = paragraphs.get(i).getText();
                if (text == null) {
                    continue;
                }
                text = text.trim();
                if (text.isEmpty()) {
                    continue;
                }
                if (all.length() > 0) {
                    all.append('\n');
                }
                all.append(text);
            }
            PageText page = new PageText();
            page.setPageNo(1);
            page.setText(all.toString());
            pages.add(page);
            return pages;
        }

        StringBuilder current = new StringBuilder();
        int currentPage = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph paragraph = paragraphs.get(i);
            String text = paragraph.getText();
            if (text == null) {
                continue;
            }
            text = text.trim();
            if (text.isEmpty()) {
                continue;
            }
            int headingLevel = detectHeadingLevel(paragraph);
            if (headingLevel > 0) {
                if (currentPage > 0 && current.length() > 0) {
                    PageText page = new PageText();
                    page.setPageNo(currentPage);
                    page.setText(current.toString());
                    pages.add(page);
                    current = new StringBuilder();
                }
                currentPage++;
            }
            if (currentPage == 0) {
                currentPage = 1;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(text);
        }
        if (currentPage > 0 && current.length() > 0) {
            PageText page = new PageText();
            page.setPageNo(currentPage);
            page.setText(current.toString());
            pages.add(page);
        }
        // 校正 outline 的 pageNo 与逻辑页一致（按标题出现顺序）
        int outlineIdx = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (detectHeadingLevel(paragraphs.get(i)) > 0 && outlineIdx < outline.size()) {
                outline.get(outlineIdx).setPageNo(outlineIdx + 1);
                outlineIdx++;
            }
        }
        return pages;
    }

    private int detectHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return 0;
        }
        String lower = style.toLowerCase(Locale.ROOT);
        if (lower.contains("heading3") || lower.equals("3")) {
            return 3;
        }
        if (lower.contains("heading2") || lower.equals("2")) {
            return 2;
        }
        if (lower.contains("heading1") || lower.contains("heading") || lower.equals("1")) {
            return 1;
        }
        // 中文样式名常见「标题 1」
        if (style.contains("标题3")) {
            return 3;
        }
        if (style.contains("标题2")) {
            return 2;
        }
        if (style.contains("标题1") || style.contains("标题")) {
            return 1;
        }
        return 0;
    }

    private static String normalizeExt(String fileExt) {
        if (fileExt == null) {
            return "";
        }
        String ext = fileExt.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        return ext;
    }
}
