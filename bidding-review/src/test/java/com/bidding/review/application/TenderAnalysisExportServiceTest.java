package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenderAnalysisExportServiceTest {

    @Test
    void exportAnalysisBuildsXlsxWorkbook() throws IOException {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        when(appService.get(55L)).thenReturn(detail(TenderAnalysisPort.STATUS_SUCCESS));
        when(documentAccessPort.listActiveByPackageId(66L)).thenReturn(List.of(doc(7L, 66L, "招标文件.pdf")));
        TenderAnalysisExportService exportService =
                new TenderAnalysisExportService(appService, documentAccessPort, new ObjectMapper());

        TenderAnalysisExportService.ExportFile file = exportService.exportAnalysis(55L);

        assertEquals("招标文件-招标解析结果.xlsx", file.getFileName());
        assertTrue(file.getContent().length > 0);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            assertNotNull(workbook.getSheet("概览"));
            assertNotNull(workbook.getSheet("源文件"));
            assertNotNull(workbook.getSheet("基础信息"));
            assertNotNull(workbook.getSheet("资格要求"));
            assertNotNull(workbook.getSheet("评分标准"));
            assertNotNull(workbook.getSheet("逻辑问题"));
            assertNotNull(workbook.getSheet("错别字"));
            assertEquals("任务名称", workbook.getSheet("概览").getRow(1).getCell(0).getStringCellValue());
            assertEquals("招标文件", workbook.getSheet("概览").getRow(1).getCell(1).getStringCellValue());
            assertEquals("招标文件.pdf", workbook.getSheet("源文件").getRow(1).getCell(2).getStringCellValue());
            assertEquals("ZHCS-2024-001", workbook.getSheet("基础信息").getRow(1).getCell(1).getStringCellValue());
            assertEquals("采购人A", workbook.getSheet("基础信息").getRow(2).getCell(1).getStringCellValue());
            assertEquals("Q1", workbook.getSheet("资格要求").getRow(1).getCell(0).getStringCellValue());
            assertEquals("S1", workbook.getSheet("评分标准").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void exportAnalysisRejectsUnsuccessfulStatus() {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        when(appService.get(55L)).thenReturn(detail(TenderAnalysisPort.STATUS_FAILED));
        TenderAnalysisExportService exportService =
                new TenderAnalysisExportService(appService, documentAccessPort, new ObjectMapper());

        BizException ex = assertThrows(BizException.class, () -> exportService.exportAnalysis(55L));

        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
    }

    private static TenderAnalysisPort.AnalysisDetail detail(String status) {
        TenderAnalysisPort.AnalysisDetail detail = new TenderAnalysisPort.AnalysisDetail();
        detail.setId(55L);
        detail.setOrgId(2L);
        detail.setPackageId(66L);
        detail.setName("招标文件");
        detail.setStatus(status);
        detail.setDocumentIdsJson("[7]");
        detail.setTenderNo("ZHCS-2024-001");
        detail.setTenderer("采购人A");
        detail.setAgency("代理B");
        detail.setBudgetAmountText("预算金额100万元");
        detail.setBidDeadlineText("2026年8月15日17:00");
        detail.setBidDeadlineAt(Instant.parse("2026-08-15T09:00:00Z"));
        detail.setProjectIntro("项目简介");
        detail.setProjectBackground("项目背景");
        detail.setProjectOverview("项目概况");
        detail.setEvidenceJson("[{\"field\":\"tenderer\",\"chunkId\":9001,\"pageNo\":1,\"quote\":\"采购人A\"}]");
        detail.setCreatedAt(Instant.parse("2026-08-14T08:00:00Z"));
        detail.setUpdatedAt(Instant.parse("2026-08-14T09:00:00Z"));
        detail.setItems(List.of(
                item(TenderAnalysisPort.ITEM_QUALIFICATION, "Q1"),
                item(TenderAnalysisPort.ITEM_SCORING, "S1"),
                item(TenderAnalysisPort.ITEM_LOGIC_ISSUE, "逻辑问题-1"),
                item(TenderAnalysisPort.ITEM_TYPO, "错别字-1")
        ));
        return detail;
    }

    private static TenderAnalysisPort.ItemView item(String type, String code) {
        TenderAnalysisPort.ItemView item = new TenderAnalysisPort.ItemView();
        item.setId(code.hashCode());
        item.setAnalysisId(55L);
        item.setItemType(type);
        item.setRequirementCode(code);
        item.setTitle(code + " 标题");
        item.setContent(code + " 内容");
        item.setSubType("OTHER");
        item.setChunkIdsJson("[9001]");
        item.setEvidenceJson(evidenceJson(type));
        if (TenderAnalysisPort.ITEM_SCORING.equals(type)) {
            item.setScoreTotal(new BigDecimal("10.00"));
            item.setScoreSumMatches(true);
        }
        return item;
    }

    private static String evidenceJson(String type) {
        if (TenderAnalysisPort.ITEM_LOGIC_ISSUE.equals(type)) {
            return "[{\"chunkId\":9001,\"pageNo\":19,\"quote\":\"废标项缺少判定依据\","
                    + "\"severity\":\"MEDIUM\",\"issueType\":\"QUALIFICATION_REJECT_LOGIC\","
                    + "\"suggestion\":\"补充可校验的判定条件。\"}]";
        }
        if (TenderAnalysisPort.ITEM_TYPO.equals(type)) {
            return "[{\"chunkId\":9002,\"pageNo\":12,\"quote\":\"按装调试\","
                    + "\"wrongText\":\"按装调试\",\"suggestedText\":\"安装调试\","
                    + "\"confidence\":\"HIGH\",\"suggestion\":\"更正为安装调试。\"}]";
        }
        return "[{\"chunkId\":9001,\"pageNo\":1,\"quote\":\"原文片段\"}]";
    }

    private static DocumentAccessPort.DocumentRef doc(long id, long packageId, String fileName) {
        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(id);
        doc.setOrgId(2L);
        doc.setPackageId(packageId);
        doc.setFileName(fileName);
        doc.setFileExt("pdf");
        doc.setIndexStatus(DocumentAccessPort.INDEX_SUCCESS);
        return doc;
    }
}
