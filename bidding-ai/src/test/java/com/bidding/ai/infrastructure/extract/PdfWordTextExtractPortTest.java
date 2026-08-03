package com.bidding.ai.infrastructure.extract;

import com.bidding.common.port.DocumentTextExtractPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
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
}
