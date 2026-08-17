package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.review.application.BidSimulationAuditAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BidSimulationAuditControllerTest {

    @Test
    void createMapsAppServiceResult() {
        BidSimulationAuditAppService appService = mock(BidSimulationAuditAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        BidSimulationAuditController controller =
                new BidSimulationAuditController(appService, documentAccessPort, new ObjectMapper());
        List<MultipartFile> tenderFiles = List.of(file("招标文件.pdf"));
        List<String> tenderFileTypes = List.of("招标正文");
        List<MultipartFile> bidFiles = List.of(file("投标文件.pdf"));
        List<String> bidFileTypes = List.of("投标正文");
        BidSimulationAuditAppService.CreateResult created = new BidSimulationAuditAppService.CreateResult();
        created.setAuditId(55L);
        created.setJobId(66L);
        created.setTenderPackageId(77L);
        created.setBidPackageId(88L);
        created.setTenderDocumentIds(List.of(7L));
        created.setBidDocumentIds(List.of(8L));
        created.setIndexJobIds(List.of(101L, 102L));
        when(appService.create("", tenderFiles, tenderFileTypes, bidFiles, bidFileTypes))
                .thenReturn(created);

        ApiResponse<BidSimulationAuditController.CreateResponse> response =
                controller.create("", tenderFileTypes, tenderFiles, bidFileTypes, bidFiles);

        assertEquals(55L, response.getData().getAuditId());
        assertEquals(66L, response.getData().getJobId());
        assertEquals(77L, response.getData().getTenderPackageId());
        assertEquals(88L, response.getData().getBidPackageId());
        assertEquals(List.of(7L), response.getData().getTenderDocumentIds());
        assertEquals(List.of(8L), response.getData().getBidDocumentIds());
        assertEquals(List.of(101L, 102L), response.getData().getIndexJobIds());
        verify(appService).create("", tenderFiles, tenderFileTypes, bidFiles, bidFileTypes);
    }

    @Test
    void listPassesStatusFilterAndMapsSummary() {
        BidSimulationAuditAppService appService = mock(BidSimulationAuditAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        BidSimulationAuditController controller =
                new BidSimulationAuditController(appService, documentAccessPort, new ObjectMapper());
        BidSimulationAuditPort.AuditSummary summary = new BidSimulationAuditPort.AuditSummary();
        summary.setId(55L);
        summary.setName("投标文件.pdf");
        summary.setStatus(BidSimulationAuditPort.STATUS_SUCCESS);
        summary.setTenderDocumentIdsJson("[7,8]");
        summary.setBidDocumentIdsJson("[9]");
        summary.setQualificationTotal(10);
        summary.setQualificationPassCount(7);
        summary.setQualificationPassRate(new BigDecimal("70"));
        summary.setScoreSuggested(new BigDecimal("16.00"));
        summary.setScoreTotal(new BigDecimal("23.00"));
        summary.setLogicIssueCount(2);
        summary.setTypoCount(3);
        when(appService.list("投标", BidSimulationAuditPort.STATUS_SUCCESS, 2, 10))
                .thenReturn(new PageResult<>(List.of(summary), 1, 2, 10));

        ApiResponse<PageResult<BidSimulationAuditController.AuditSummaryDto>> response =
                controller.list("投标", BidSimulationAuditPort.STATUS_SUCCESS, 2, 10);

        BidSimulationAuditController.AuditSummaryDto dto = response.getData().getItems().get(0);
        assertEquals(55L, dto.getId());
        assertEquals("投标文件.pdf", dto.getName());
        assertEquals(2, dto.getTenderFileCount());
        assertEquals(1, dto.getBidFileCount());
        assertEquals("70.00%", dto.getQualificationPassRate());
        assertEquals(new BigDecimal("16.00"), dto.getScoreSuggested());
        assertEquals(new BigDecimal("23.00"), dto.getScoreTotal());
        assertEquals(2, dto.getLogicIssueCount());
        assertEquals(3, dto.getTypoCount());
        verify(appService).list("投标", BidSimulationAuditPort.STATUS_SUCCESS, 2, 10);
    }

    @Test
    void getClassifiesAllItemTypesAndMapsDisplayFields() {
        BidSimulationAuditAppService appService = mock(BidSimulationAuditAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        BidSimulationAuditController controller =
                new BidSimulationAuditController(appService, documentAccessPort, new ObjectMapper());
        when(appService.get(55L)).thenReturn(detail());
        when(documentAccessPort.listActiveByPackageId(66L)).thenReturn(List.of(doc(7L, 66L, "招标文件.pdf", "pdf")));
        when(documentAccessPort.listActiveByPackageId(67L)).thenReturn(List.of(doc(8L, 67L, "投标文件.pdf", "pdf")));

        ApiResponse<BidSimulationAuditController.AuditDetailDto> response = controller.get(55L);

        BidSimulationAuditController.AuditDetailDto data = response.getData();
        assertEquals("70.00%", data.getSummary().getQualificationPassRate());
        assertEquals("采购人A", data.getBasicInfo().getTenderer());
        assertEquals("代理B", data.getBasicInfo().getAgency());
        assertEquals("预算金额100万元", data.getBasicInfo().getBudgetAmountText());
        assertEquals("2026年8月15日17:00", data.getBasicInfo().getBidDeadlineText());
        assertEquals(Instant.parse("2026-08-15T09:00:00Z"), data.getBasicInfo().getBidDeadlineAt());
        assertEquals("tenderer", data.getBasicInfo().getEvidence().get(0).get("field").asText());
        assertEquals(1, data.getQualificationItems().size());
        assertEquals("Q1", data.getQualificationItems().get(0).getRequirementCode());
        assertEquals(BidSimulationAuditPort.RESULT_PASS, data.getQualificationItems().get(0).getResult());
        assertEquals("符合", data.getQualificationItems().get(0).getResultLabel());
        assertEquals("CERTIFICATE", data.getQualificationItems().get(0).getSubType());
        assertEquals("资质证书", data.getQualificationItems().get(0).getSubTypeLabel());

        assertEquals(1, data.getScoringItems().size());
        assertEquals("S1", data.getScoringItems().get(0).getRequirementCode());
        assertEquals(new BigDecimal("8.50"), data.getScoringItems().get(0).getSuggestedScore());
        assertEquals(new BigDecimal("10.00"), data.getScoringItems().get(0).getFullScore());
        assertEquals("TECH_SCORE", data.getScoringItems().get(0).getSubType());
        assertEquals("技术评分", data.getScoringItems().get(0).getSubTypeLabel());
        assertEquals("方案较完整。", data.getScoringItems().get(0).getReason());

        assertEquals(1, data.getLogicIssueItems().size());
        assertEquals("逻辑问题-1", data.getLogicIssueItems().get(0).getRequirementCode());
        assertEquals(BidSimulationAuditPort.ITEM_LOGIC_ISSUE, data.getLogicIssueItems().get(0).getItemType());
        assertEquals("MEDIUM", data.getLogicIssueItems().get(0).getSeverity());
        assertEquals("中风险", data.getLogicIssueItems().get(0).getSeverityLabel());
        assertEquals("TERM_INCONSISTENCY", data.getLogicIssueItems().get(0).getIssueType());
        assertEquals("期限逻辑", data.getLogicIssueItems().get(0).getIssueTypeLabel());
        assertEquals("统一服务周期。", data.getLogicIssueItems().get(0).getSuggestion());

        assertEquals(1, data.getTypoItems().size());
        assertEquals("错别字-1", data.getTypoItems().get(0).getRequirementCode());
        assertEquals(BidSimulationAuditPort.ITEM_TYPO, data.getTypoItems().get(0).getItemType());
        assertEquals("LOW", data.getTypoItems().get(0).getSeverity());
        assertEquals("低风险", data.getTypoItems().get(0).getSeverityLabel());
        assertEquals("错别字", data.getTypoItems().get(0).getIssueTypeLabel());
        assertEquals("按装调试", data.getTypoItems().get(0).getWrongText());
        assertEquals("安装调试", data.getTypoItems().get(0).getSuggestedText());
        assertEquals("HIGH", data.getTypoItems().get(0).getConfidence());
        assertEquals("高置信", data.getTypoItems().get(0).getConfidenceLabel());
        assertEquals(1, data.getTenderDocuments().size());
        assertEquals(7L, data.getTenderDocuments().get(0).getDocumentId());
        assertEquals("招标文件.pdf", data.getTenderDocuments().get(0).getFileName());
        assertEquals(DocumentAccessPort.INDEX_SUCCESS, data.getTenderDocuments().get(0).getIndexStatus());
        assertEquals(1, data.getBidDocuments().size());
        assertEquals(8L, data.getBidDocuments().get(0).getDocumentId());
        assertEquals("投标文件.pdf", data.getBidDocuments().get(0).getFileName());
    }

    @Test
    void deleteDelegatesToAppService() {
        BidSimulationAuditAppService appService = mock(BidSimulationAuditAppService.class);
        DocumentAccessPort documentAccessPort = mock(DocumentAccessPort.class);
        BidSimulationAuditController controller =
                new BidSimulationAuditController(appService, documentAccessPort, new ObjectMapper());

        controller.delete(55L);

        verify(appService).delete(55L);
    }

    private static BidSimulationAuditPort.AuditDetail detail() {
        BidSimulationAuditPort.AuditDetail detail = new BidSimulationAuditPort.AuditDetail();
        detail.setId(55L);
        detail.setOrgId(2L);
        detail.setName("投标文件.pdf");
        detail.setStatus(BidSimulationAuditPort.STATUS_SUCCESS);
        detail.setTenderPackageId(66L);
        detail.setBidPackageId(67L);
        detail.setTenderDocumentIdsJson("[7]");
        detail.setBidDocumentIdsJson("[8,9]");
        detail.setTenderer("采购人A");
        detail.setAgency("代理B");
        detail.setBudgetAmountText("预算金额100万元");
        detail.setBidDeadlineText("2026年8月15日17:00");
        detail.setBidDeadlineAt(Instant.parse("2026-08-15T09:00:00Z"));
        detail.setBasicInfoEvidenceJson("[{\"field\":\"tenderer\",\"value\":\"采购人A\"}]");
        detail.setQualificationTotal(10);
        detail.setQualificationPassCount(7);
        detail.setQualificationFailCount(2);
        detail.setQualificationManualCount(1);
        detail.setQualificationPassRate(new BigDecimal("70"));
        detail.setScoreSuggested(new BigDecimal("16.00"));
        detail.setScoreTotal(new BigDecimal("23.00"));
        detail.setLogicIssueCount(1);
        detail.setTypoCount(1);
        detail.setItems(items());
        return detail;
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "demo".getBytes());
    }

    private static DocumentAccessPort.DocumentRef doc(long id, long packageId, String fileName, String fileExt) {
        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(id);
        doc.setOrgId(2L);
        doc.setPackageId(packageId);
        doc.setFileName(fileName);
        doc.setFileExt(fileExt);
        doc.setIndexStatus(DocumentAccessPort.INDEX_SUCCESS);
        return doc;
    }

    private static List<BidSimulationAuditPort.ItemView> items() {
        List<BidSimulationAuditPort.ItemView> items = new ArrayList<>();
        items.add(item(BidSimulationAuditPort.ITEM_QUALIFICATION, "Q1"));
        items.add(item(BidSimulationAuditPort.ITEM_SCORING, "S1"));
        items.add(item(BidSimulationAuditPort.ITEM_LOGIC_ISSUE, "逻辑问题-1"));
        items.add(item(BidSimulationAuditPort.ITEM_TYPO, "错别字-1"));
        return items;
    }

    private static BidSimulationAuditPort.ItemView item(String type, String code) {
        BidSimulationAuditPort.ItemView item = new BidSimulationAuditPort.ItemView();
        item.setId(code.hashCode());
        item.setAuditId(55L);
        item.setItemType(type);
        item.setRequirementCode(code);
        item.setTitle(code);
        item.setContent(code + " 内容");
        item.setChunkIdsJson("[9001]");
        item.setEvidenceJson(evidenceJson(type));
        if (BidSimulationAuditPort.ITEM_QUALIFICATION.equals(type)) {
            item.setSubType("CERTIFICATE");
            item.setResult(BidSimulationAuditPort.RESULT_PASS);
            item.setReason("投标文件提供了有效资质。");
        }
        if (BidSimulationAuditPort.ITEM_SCORING.equals(type)) {
            item.setSubType("TECH_SCORE");
            item.setSuggestedScore(new BigDecimal("8.50"));
            item.setFullScore(new BigDecimal("10.00"));
            item.setReason("方案较完整。");
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
                    + "\"confidence\":\"HIGH\",\"suggestion\":\"更正为“安装调试”。\"}]";
        }
        return "[]";
    }
}
