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
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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

    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

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

            Node body = document.getDocument().getBody().getDomNode();
            NodeList bodyChildren = body.getChildNodes();
            for (int i = 0; i < bodyChildren.getLength(); i++) {
                Node element = bodyChildren.item(i);
                String text = "";
                if ("p".equals(element.getLocalName()) && WORD_NS.equals(element.getNamespaceURI())) {
                    text = normalizeText(extractNodeText(element));
                } else if ("tbl".equals(element.getLocalName()) && WORD_NS.equals(element.getNamespaceURI())) {
                    text = extractTableText(element);
                }
                if (text.isEmpty()) {
                    continue;
                }

                appendBlock(full, text);
            }

            ExtractResult result = new ExtractResult();
            result.setFullText(full.toString());
            result.setOutline(new ArrayList<>());
            List<PageText> pages = new ArrayList<>();
            if (full.length() > 0) {
                pages.add(page(1, full.toString()));
            }
            result.setPages(pages);

            if (result.isBlank()) {
                result.setWarning("Word 文档未抽取到文本");
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("解析 Word 失败: " + ex.getMessage(), ex);
        }
    }

    private String extractTableText(Node tableNode) {
        if (tableNode == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<Node> rows = directWordChildren(tableNode, "tr");
        for (int r = 0; r < rows.size(); r++) {
            List<Node> cells = directWordChildren(rows.get(r), "tc");
            if (cells.isEmpty()) {
                continue;
            }
            StringBuilder rowText = new StringBuilder();
            for (int c = 0; c < cells.size(); c++) {
                String cellText = normalizeCellText(extractNodeText(cells.get(c)));
                if (cellText.isEmpty()) {
                    continue;
                }
                if (rowText.length() > 0) {
                    rowText.append(" | ");
                }
                rowText.append(cellText);
            }
            if (rowText.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(rowText);
        }
        return sb.toString();
    }

    private static String extractNodeText(Node node) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        collectNodeText(node, sb);
        return sb.toString();
    }

    private static void collectNodeText(Node node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        String localName = node.getLocalName();
        if ("t".equals(localName) && WORD_NS.equals(node.getNamespaceURI())) {
            appendTextNodeValue(node, sb);
            return;
        }
        if ("tab".equals(localName) && WORD_NS.equals(node.getNamespaceURI())) {
            sb.append(' ');
            return;
        }
        if ("br".equals(localName) && WORD_NS.equals(node.getNamespaceURI())) {
            sb.append('\n');
            return;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            collectNodeText(children.item(i), sb);
        }
    }

    private static void appendTextNodeValue(Node node, StringBuilder sb) {
        Node child = node.getFirstChild();
        while (child != null) {
            String value = child.getNodeValue();
            if (value != null) {
                sb.append(value);
            }
            child = child.getNextSibling();
        }
    }

    private static List<Node> directWordChildren(Node node, String localName) {
        List<Node> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName()) && WORD_NS.equals(child.getNamespaceURI())) {
                result.add(child);
            }
        }
        return result;
    }

    private static void appendBlock(StringBuilder sb, String text) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(text);
    }

    private static PageText page(int pageNo, String text) {
        PageText page = new PageText();
        page.setPageNo(pageNo);
        page.setText(text);
        return page;
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }

    private static String normalizeCellText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
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
