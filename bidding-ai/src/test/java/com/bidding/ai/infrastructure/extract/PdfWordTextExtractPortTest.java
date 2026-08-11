package com.bidding.ai.infrastructure.extract;

import com.bidding.common.port.DocumentTextExtractPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfWordTextExtractPortTest {

    private final PdfWordTextExtractPort port = new PdfWordTextExtractPort();

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
}
