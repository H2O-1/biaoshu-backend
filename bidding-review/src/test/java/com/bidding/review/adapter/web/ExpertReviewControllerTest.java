package com.bidding.review.adapter.web;

import com.bidding.review.application.ExpertReviewAppService;
import com.bidding.review.application.ExpertReviewExportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpertReviewControllerTest {

    @Test
    void exportReturnsXlsxAttachment() {
        ExpertReviewAppService appService = mock(ExpertReviewAppService.class);
        ExpertReviewExportService exportService = mock(ExpertReviewExportService.class);
        byte[] content = new byte[] {1, 2, 3};
        when(exportService.exportProjectReview(100L)).thenReturn(
                new ExpertReviewExportService.ExportFile("智慧教育-专家评审报告.xlsx", content));
        ExpertReviewController controller = new ExpertReviewController(appService, exportService);

        ResponseEntity<byte[]> response = controller.export(100L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .startsWith("attachment; filename*=UTF-8''"));
        assertArrayEquals(content, response.getBody());
        verify(exportService).exportProjectReview(100L);
    }

    @Test
    void removeMemberDelegatesToAppService() {
        ExpertReviewAppService appService = mock(ExpertReviewAppService.class);
        ExpertReviewExportService exportService = mock(ExpertReviewExportService.class);
        ExpertReviewController controller = new ExpertReviewController(appService, exportService);

        controller.removeMember(100L, 88L);

        verify(appService).removeMember(100L, 88L);
    }

    @Test
    void pushAppendDelegatesToAppService() {
        ExpertReviewAppService appService = mock(ExpertReviewAppService.class);
        ExpertReviewExportService exportService = mock(ExpertReviewExportService.class);
        ExpertReviewController controller = new ExpertReviewController(appService, exportService);

        controller.pushAppend(100L);

        verify(appService).pushAppend(100L);
    }
}
