package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.review.application.TenderAnalysisAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenderAnalysisControllerTest {

    @Test
    void getClassifiesIssueAndTypoItems() {
        TenderAnalysisAppService appService = mock(TenderAnalysisAppService.class);
        TenderAnalysisController controller =
                new TenderAnalysisController(appService, new ObjectMapper());
        when(appService.get(55L)).thenReturn(detail());

        ApiResponse<TenderAnalysisController.AnalysisDetailDto> response = controller.get(55L);

        TenderAnalysisController.AnalysisDetailDto data = response.getData();
        assertEquals(1, data.getQualificationItems().size());
        assertEquals("Q1", data.getQualificationItems().get(0).getRequirementCode());
        assertEquals(1, data.getScoringItems().size());
        assertEquals("S1", data.getScoringItems().get(0).getRequirementCode());
        assertEquals(1, data.getLogicIssueItems().size());
        assertEquals("逻辑问题-1", data.getLogicIssueItems().get(0).getRequirementCode());
        assertEquals(TenderAnalysisPort.ITEM_LOGIC_ISSUE, data.getLogicIssueItems().get(0).getItemType());
        assertEquals(1, data.getTypoItems().size());
        assertEquals("错别字-1", data.getTypoItems().get(0).getRequirementCode());
        assertEquals(TenderAnalysisPort.ITEM_TYPO, data.getTypoItems().get(0).getItemType());
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
        item.setEvidenceJson("[]");
        return item;
    }
}
