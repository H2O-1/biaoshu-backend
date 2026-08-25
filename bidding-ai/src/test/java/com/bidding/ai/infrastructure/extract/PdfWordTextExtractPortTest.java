package com.bidding.ai.infrastructure.extract;

import com.bidding.ai.infrastructure.ocr.OcrProperties;
import com.bidding.common.port.DocumentOcrPort;
import com.bidding.common.port.DocumentTextExtractPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfWordTextExtractPortTest {

    private final PdfWordTextExtractPort port = new PdfWordTextExtractPort(Optional.empty(), disabledOcrProperties());

    @Test
    void extractPdfText() throws Exception {
        byte[] pdfBytes = buildSamplePdf("Bidding Qualification");
        try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
            DocumentTextExtractPort.ExtractResult result = port.extract("pdf", in);
            assertFalse(result.isBlank());
            assertTrue(result.getFullText().contains("Bidding"));
            assertFalse(result.getPages().isEmpty());
        }
    }

    @Test
    void extractDocxText() throws Exception {
        byte[] docxBytes = buildSampleDocx("工期与交付要求");
        try (InputStream in = new ByteArrayInputStream(docxBytes)) {
            DocumentTextExtractPort.ExtractResult result = port.extract("docx", in);
            assertFalse(result.isBlank());
            assertTrue(result.getFullText().contains("工期"));
        }
    }

    @Test
    void extractPdfUsesOcrWhenNativeTextIsSparse() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        PdfWordTextExtractPort ocrPort = new PdfWordTextExtractPort(
                Optional.of((pdfBytes, forceOcr) -> {
                    called.set(true);
                    assertFalse(forceOcr);
                    return ocrResult(2, "OCR 第一页", "OCR 第二页");
                }),
                enabledOcrProperties());

        byte[] pdfBytes = buildSamplePdf("");
        try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
            DocumentTextExtractPort.ExtractResult result = ocrPort.extract("pdf", in);

            assertTrue(called.get());
            assertEquals("OCR 第一页\nOCR 第二页", result.getFullText());
            assertEquals(1, result.getPages().get(0).getPageNo());
            assertEquals(2, result.getPages().get(1).getPageNo());
        }
    }

    @Test
    void extractPdfUsesOcrWhenPageContainsLargeImage() throws Exception {
        AtomicBoolean called = new AtomicBoolean(false);
        PdfWordTextExtractPort ocrPort = new PdfWordTextExtractPort(
                Optional.of((pdfBytes, forceOcr) -> {
                    called.set(true);
                    assertFalse(forceOcr);
                    return ocrResult(1, "图片 OCR 文本");
                }),
                enabledOcrProperties());

        byte[] pdfBytes = buildSamplePdfWithLargeImage();
        try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
            DocumentTextExtractPort.ExtractResult result = ocrPort.extract("pdf", in);

            assertTrue(called.get());
            assertEquals("图片 OCR 文本", result.getFullText());
            assertEquals(1, result.getPages().get(0).getPageNo());
        }
    }

    @Test
    void extractPdfReportsOcrDisabledForBlankScan() throws Exception {
        byte[] pdfBytes = buildSamplePdf("");
        try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
            DocumentTextExtractPort.ExtractResult result = port.extract("pdf", in);

            assertTrue(result.isBlank());
            assertEquals("未能从文件抽取到文本，扫描版 PDF 需启用 OCR 服务", result.getWarning());
        }
    }

    @Test
    void extractDocxKeepsTableBesidePrecedingText() throws Exception {
        byte[] docxBytes = buildDocxWithScoringTable();
        try (InputStream in = new ByteArrayInputStream(docxBytes)) {
            DocumentTextExtractPort.ExtractResult result = port.extract("docx", in);

            String text = result.getFullText();
            assertTrue(text.indexOf("（一）商务部分（30分）") < text.indexOf("企业资质 | 具备电子与智能化工程专业承包壹级资质得5分"));
            assertTrue(text.indexOf("企业资质 | 具备电子与智能化工程专业承包壹级资质得5分") < text.indexOf("（二）技术部分（40分）"));
            assertTrue(result.getOutline().isEmpty());
            assertFalse(result.getPages().isEmpty());
            assertTrue(result.getPages().get(0).getText().contains("商务部分"));
            assertTrue(result.getPages().get(0).getText().contains("企业资质 | 具备电子与智能化工程专业承包壹级资质得5分"));
        }
    }

    @Test
    void rejectExcel() {
        assertThrows(IllegalArgumentException.class,
                () -> port.extract("xlsx", new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    private static byte[] buildSamplePdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildSamplePdfWithLargeImage() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("Bidding qualification and scoring text with enough native characters on this page.");
                stream.endText();

                PDImageXObject image = LosslessFactory.createFromImage(document, largeImage());
                stream.drawImage(image, 50, 420, 300, 160);
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage largeImage() {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLUE);
            graphics.fillRect(20, 20, 160, 60);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] buildSampleDocx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(text);
            document.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildDocxWithScoringTable() throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph business = document.createParagraph();
            business.setStyle("Heading1");
            business.createRun().setText("（一）商务部分（30分）");

            XWPFTable table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("评审项目");
            table.getRow(0).getCell(1).setText("评分标准");
            table.getRow(0).getCell(2).setText("分值");
            table.getRow(1).getCell(0).setText("企业资质");
            table.getRow(1).getCell(1).setText("具备电子与智能化工程专业承包壹级资质得5分");
            table.getRow(1).getCell(2).setText("5分");

            XWPFParagraph technical = document.createParagraph();
            technical.setStyle("Heading1");
            technical.createRun().setText("（二）技术部分（40分）");

            document.write(out);
            return out.toByteArray();
        }
    }

    private static OcrProperties disabledOcrProperties() {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(false);
        return properties;
    }

    private static OcrProperties enabledOcrProperties() {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(true);
        properties.setMinTotalChars(50);
        properties.setMinPageChars(30);
        return properties;
    }

    private static DocumentOcrPort.OcrPdfResult ocrResult(int pageCount, String... pageTexts) {
        DocumentOcrPort.OcrPdfResult result = new DocumentOcrPort.OcrPdfResult();
        result.setPageCount(pageCount);
        result.setEngine("rapidocr");
        for (int i = 0; i < pageTexts.length; i++) {
            DocumentOcrPort.OcrPageResult page = new DocumentOcrPort.OcrPageResult();
            page.setPageNo(i + 1);
            page.setText(pageTexts[i]);
            page.setSource("ocr");
            result.getPages().add(page);
        }
        return result;
    }
}
