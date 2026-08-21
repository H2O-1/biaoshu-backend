package com.bidding.review.adapter.web;

import com.bidding.review.application.ExpertReviewAppService;
import com.bidding.review.application.ExpertReviewExportService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;

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
    void previewAttachmentReturnsInlineContent() throws Exception {
        ExpertReviewAppService appService = mock(ExpertReviewAppService.class);
        ExpertReviewExportService exportService = mock(ExpertReviewExportService.class);
        ExpertReviewAppService.AttachmentContent content = new ExpertReviewAppService.AttachmentContent();
        content.setFileName("现场照片.png");
        content.setContentType("image/png");
        content.setInputStream(new ByteArrayInputStream(new byte[] {4, 5}));
        when(appService.openProjectAttachmentForPreview(100L, 801L, 901L)).thenReturn(content);
        ExpertReviewController controller = new ExpertReviewController(appService, exportService);

        ResponseEntity<InputStreamResource> response = controller.previewAttachment(100L, 801L, 901L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline;"));
        assertArrayEquals(new byte[] {4, 5}, response.getBody().getInputStream().readAllBytes());
        verify(appService).openProjectAttachmentForPreview(100L, 801L, 901L);
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
