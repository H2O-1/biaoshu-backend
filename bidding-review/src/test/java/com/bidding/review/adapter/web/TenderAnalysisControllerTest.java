package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.review.application.TenderAnalysisAppService;
import com.bidding.review.application.TenderAnalysisExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenderAnalysisControllerTest {

    @Test
    void listPassesStatusFilter() {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        TenderAnalysisExportService exportService = mock(TenderAnalysisExportService.class);
        TenderAnalysisController controller =
                new TenderAnalysisController(appService, exportService, new ObjectMapper());
        TenderAnalysisPort.AnalysisSummary summary = new TenderAnalysisPort.AnalysisSummary();
        summary.setId(55L);
        summary.setName("招标文件.pdf");
        summary.setStatus(TenderAnalysisPort.STATUS_FAILED);
        summary.setDocumentIdsJson("[7,8]");
        summary.setRequirementCount(12);
        summary.setIssueCount(3);
        summary.setSourceAnalysisId(44L);
        summary.setOriginalCreatedAt(Instant.parse("2026-08-10T08:30:00Z"));
        when(appService.list("园区", TenderAnalysisPort.STATUS_FAILED, 2, 10))
                .thenReturn(new PageResult<>(List.of(summary), 1, 2, 10));

        ApiResponse<PageResult<TenderAnalysisController.AnalysisSummaryDto>> response =
                controller.list("园区", TenderAnalysisPort.STATUS_FAILED, 2, 10);

        assertEquals(1, response.getData().getTotal());
        TenderAnalysisController.AnalysisSummaryDto dto = response.getData().getItems().get(0);
        assertEquals(2, dto.getFileCount());
        assertEquals(12, dto.getRequirementCount());
        assertEquals(3, dto.getIssueCount());
        assertEquals(44L, dto.getSourceAnalysisId());
        assertEquals(Instant.parse("2026-08-10T08:30:00Z"), dto.getOriginalCreatedAt());
        verify(appService).list("园区", TenderAnalysisPort.STATUS_FAILED, 2, 10);
    }

    @Test
    void getClassifiesIssueAndTypoItems() {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        TenderAnalysisExportService exportService = mock(TenderAnalysisExportService.class);
        TenderAnalysisController controller =
                new TenderAnalysisController(appService, exportService, new ObjectMapper());
        when(appService.get(55L)).thenReturn(detail());

        ApiResponse<TenderAnalysisController.AnalysisDetailDto> response = controller.get(55L);

        TenderAnalysisController.AnalysisDetailDto data = response.getData();
        assertEquals(44L, data.getSourceAnalysisId());
        assertEquals(Instant.parse("2026-08-10T08:30:00Z"), data.getOriginalCreatedAt());
        assertEquals(1, data.getQualificationItems().size());
        assertEquals("Q1", data.getQualificationItems().get(0).getRequirementCode());
        assertEquals(1, data.getScoringItems().size());
        assertEquals("S1", data.getScoringItems().get(0).getRequirementCode());
        assertEquals(1, data.getLogicIssueItems().size());
        assertEquals("逻辑问题-1", data.getLogicIssueItems().get(0).getRequirementCode());
        assertEquals(TenderAnalysisPort.ITEM_LOGIC_ISSUE, data.getLogicIssueItems().get(0).getItemType());
        assertEquals("MEDIUM", data.getLogicIssueItems().get(0).getSeverity());
        assertEquals("中风险", data.getLogicIssueItems().get(0).getSeverityLabel());
        assertEquals("QUALIFICATION_REJECT_LOGIC", data.getLogicIssueItems().get(0).getIssueType());
        assertEquals("资格与废标逻辑", data.getLogicIssueItems().get(0).getIssueTypeLabel());
        assertEquals("补充可校验的判定条件。", data.getLogicIssueItems().get(0).getSuggestion());
        assertEquals(1, data.getTypoItems().size());
        assertEquals("错别字-1", data.getTypoItems().get(0).getRequirementCode());
        assertEquals(TenderAnalysisPort.ITEM_TYPO, data.getTypoItems().get(0).getItemType());
        assertEquals("LOW", data.getTypoItems().get(0).getSeverity());
        assertEquals("低风险", data.getTypoItems().get(0).getSeverityLabel());
        assertEquals("错别字", data.getTypoItems().get(0).getIssueTypeLabel());
        assertEquals("按装调试", data.getTypoItems().get(0).getWrongText());
        assertEquals("安装调试", data.getTypoItems().get(0).getSuggestedText());
        assertEquals("HIGH", data.getTypoItems().get(0).getConfidence());
        assertEquals("高置信", data.getTypoItems().get(0).getConfidenceLabel());
        assertEquals("更正为“安装调试”。", data.getTypoItems().get(0).getSuggestion());
    }

    @Test
    void rerunReturnsNewAnalysisResponse() {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        TenderAnalysisExportService exportService = mock(TenderAnalysisExportService.class);
        TenderAnalysisController controller =
                new TenderAnalysisController(appService, exportService, new ObjectMapper());
        TenderAnalysisAppService.CreateResult created = new TenderAnalysisAppService.CreateResult();
        created.setAnalysisId(77L);
        created.setJobId(88L);
        created.setPackageId(66L);
        created.setDocumentIds(List.of(7L, 8L));
        when(appService.rerun(55L)).thenReturn(created);

        ApiResponse<TenderAnalysisController.CreateResponse> response = controller.rerun(55L);

        assertEquals(77L, response.getData().getAnalysisId());
        assertEquals(88L, response.getData().getJobId());
        assertEquals(66L, response.getData().getPackageId());
        assertEquals(List.of(7L, 8L), response.getData().getDocumentIds());
        assertEquals(0, response.getData().getFiles().size());
        verify(appService).rerun(55L);
    }

    @Test
    void exportReturnsXlsxAttachment() {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        TenderAnalysisExportService exportService = mock(TenderAnalysisExportService.class);
        TenderAnalysisController controller =
                new TenderAnalysisController(appService, exportService, new ObjectMapper());
        byte[] content = new byte[] {1, 2, 3};
        when(exportService.exportAnalysis(55L)).thenReturn(
                new TenderAnalysisExportService.ExportFile("招标文件-招标解析结果.xlsx", content));

        ResponseEntity<byte[]> response = controller.export(55L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .startsWith("attachment; filename*=UTF-8''"));
        assertArrayEquals(content, response.getBody());
        verify(exportService).exportAnalysis(55L);
    }

    private static TenderAnalysisPort.AnalysisDetail detail() {
        TenderAnalysisPort.AnalysisDetail detail = new TenderAnalysisPort.AnalysisDetail();
        detail.setId(55L);
        detail.setOrgId(2L);
        detail.setPackageId(66L);
        detail.setName("招标文件.pdf");
        detail.setStatus(TenderAnalysisPort.STATUS_SUCCESS);
        detail.setDocumentIdsJson("[7]");
        detail.setEvidenceJson("[]");
        detail.setSourceAnalysisId(44L);
        detail.setOriginalCreatedAt(Instant.parse("2026-08-10T08:30:00Z"));
        detail.setItems(items());
        return detail;
    }

    private static List<TenderAnalysisPort.ItemView> items() {
        List<TenderAnalysisPort.ItemView> items = new ArrayList<>();
        items.add(item(TenderAnalysisPort.ITEM_QUALIFICATION, "Q1"));
        items.add(item(TenderAnalysisPort.ITEM_SCORING, "S1"));
        items.add(item(TenderAnalysisPort.ITEM_LOGIC_ISSUE, "逻辑问题-1"));
        items.add(item(TenderAnalysisPort.ITEM_TYPO, "错别字-1"));
        return items;
    }

    private static TenderAnalysisPort.ItemView item(String type, String code) {
        TenderAnalysisPort.ItemView item = new TenderAnalysisPort.ItemView();
        item.setId(code.hashCode());
        item.setAnalysisId(55L);
        item.setItemType(type);
        item.setRequirementCode(code);
        item.setTitle(code);
        item.setContent(code + " 内容");
        item.setChunkIdsJson("[9001]");
        item.setEvidenceJson(evidenceJson(type));
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
                    + "\"confidence\":\"HIGH\",\"suggestion\":\"更正为“安装调试”。\"}]";
        }
        return "[]";
    }
}
