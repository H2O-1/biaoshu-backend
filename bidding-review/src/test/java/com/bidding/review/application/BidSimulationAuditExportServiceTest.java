package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.DocumentAccessPort;
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

class BidSimulationAuditExportServiceTest {

    @Test
    void exportAuditBuildsXlsxWorkbook() throws IOException {
        BidSimulationAuditAppService appService = mock(BidSimulationAuditAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        when(appService.get(55L)).thenReturn(detail(BidSimulationAuditPort.STATUS_SUCCESS));
        when(documentAccessPort.listActiveByPackageId(77L)).thenReturn(List.of(doc(7L, 77L, "招标文件.pdf")));
        when(documentAccessPort.listActiveByPackageId(88L)).thenReturn(List.of(doc(8L, 88L, "投标文件.pdf")));
        BidSimulationAuditExportService exportService =
                new BidSimulationAuditExportService(appService, documentAccessPort, new ObjectMapper());

        BidSimulationAuditExportService.ExportFile file = exportService.exportAudit(55L);

        assertEquals("投标文件-投标模拟审核结果.xlsx", file.getFileName());
        assertTrue(file.getContent().length > 0);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.getContent()))) {
            assertNotNull(workbook.getSheet("概览"));
            assertNotNull(workbook.getSheet("招标文件"));
            assertNotNull(workbook.getSheet("投标文件"));
            assertNotNull(workbook.getSheet("招标基础信息"));
            assertNotNull(workbook.getSheet("资格响应"));
            assertNotNull(workbook.getSheet("评分响应"));
            assertNotNull(workbook.getSheet("投标逻辑问题"));
            assertNotNull(workbook.getSheet("投标错别字"));
            assertEquals("任务名称", workbook.getSheet("概览").getRow(1).getCell(0).getStringCellValue());
            assertEquals("投标文件", workbook.getSheet("概览").getRow(1).getCell(1).getStringCellValue());
            assertEquals("招标文件.pdf", workbook.getSheet("招标文件").getRow(1).getCell(2).getStringCellValue());
            assertEquals("投标文件.pdf", workbook.getSheet("投标文件").getRow(1).getCell(2).getStringCellValue());
            assertEquals("Q1", workbook.getSheet("资格响应").getRow(1).getCell(0).getStringCellValue());
            assertEquals("S1", workbook.getSheet("评分响应").getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void exportAuditRejectsUnsuccessfulStatus() {
        BidSimulationAuditAppService appService = mock(BidSimulationAuditAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        when(appService.get(55L)).thenReturn(detail(BidSimulationAuditPort.STATUS_BUILDING));
        BidSimulationAuditExportService exportService =
                new BidSimulationAuditExportService(appService, documentAccessPort, new ObjectMapper());

        BizException ex = assertThrows(BizException.class, () -> exportService.exportAudit(55L));

        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
    }

    private static BidSimulationAuditPort.AuditDetail detail(String status) {
        BidSimulationAuditPort.AuditDetail detail = new BidSimulationAuditPort.AuditDetail();
        detail.setId(55L);
        detail.setOrgId(2L);
        detail.setName("投标文件");
        detail.setStatus(status);
        detail.setTenderPackageId(77L);
        detail.setBidPackageId(88L);
        detail.setTenderDocumentIdsJson("[7]");
        detail.setBidDocumentIdsJson("[8]");
        detail.setTenderer("采购人A");
        detail.setAgency("代理B");
        detail.setBudgetAmountText("预算金额100万元");
        detail.setBidDeadlineText("2026年8月15日17:00");
        detail.setBidDeadlineAt(Instant.parse("2026-08-15T09:00:00Z"));
        detail.setBasicInfoEvidenceJson("[{\"field\":\"tenderer\",\"chunkId\":9001,\"pageNo\":1,\"quote\":\"采购人A\"}]");
        detail.setQualificationTotal(1);
        detail.setQualificationPassCount(1);
        detail.setQualificationPassRate(new BigDecimal("100.00"));
        detail.setScoreSuggested(new BigDecimal("8.50"));
        detail.setScoreTotal(new BigDecimal("10.00"));
        detail.setLogicIssueCount(1);
        detail.setTypoCount(1);
        detail.setCreatedAt(Instant.parse("2026-08-14T08:00:00Z"));
        detail.setUpdatedAt(Instant.parse("2026-08-14T09:00:00Z"));
        detail.setItems(List.of(
                item(BidSimulationAuditPort.ITEM_QUALIFICATION, "Q1"),
                item(BidSimulationAuditPort.ITEM_SCORING, "S1"),
                item(BidSimulationAuditPort.ITEM_LOGIC_ISSUE, "逻辑问题-1"),
                item(BidSimulationAuditPort.ITEM_TYPO, "错别字-1")
        ));
        return detail;
    }

    private static BidSimulationAuditPort.ItemView item(String type, String code) {
        BidSimulationAuditPort.ItemView item = new BidSimulationAuditPort.ItemView();
        item.setId(code.hashCode());
        item.setAuditId(55L);
        item.setItemType(type);
        item.setRequirementCode(code);
        item.setTitle(code + " 标题");
        item.setContent(code + " 内容");
        item.setResult(BidSimulationAuditPort.RESULT_PASS);
        item.setReason(code + " 原因");
        item.setSubType("OTHER");
        item.setChunkIdsJson("[9001]");
        item.setEvidenceJson(evidenceJson(type));
        if (BidSimulationAuditPort.ITEM_SCORING.equals(type)) {
            item.setFullScore(new BigDecimal("10.00"));
            item.setSuggestedScore(new BigDecimal("8.50"));
        }
        return item;
    }

    private static String evidenceJson(String type) {
        if (BidSimulationAuditPort.ITEM_LOGIC_ISSUE.equals(type)) {
            return "[{\"chunkId\":9001,\"pageNo\":19,\"quote\":\"服务周期前后不一致\","
                    + "\"severity\":\"MEDIUM\",\"issueType\":\"TERM_INCONSISTENCY\","
                    + "\"suggestion\":\"统一服务周期。\"}]";
        }
        if (BidSimulationAuditPort.ITEM_TYPO.equals(type)) {
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
