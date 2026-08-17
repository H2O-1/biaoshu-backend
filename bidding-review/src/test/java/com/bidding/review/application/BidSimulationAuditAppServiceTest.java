package com.bidding.review.application;

import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageCommandPort;
import com.bidding.common.port.TenderAnalysisDocumentCommandPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BidSimulationAuditAppServiceTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createUploadsTenderAndBidFilesAndCreatesAuditJob() {
        UserContext.set(new CurrentUser(10L, 2L, "u", Set.of("BIDDER"), 1, false));
        BidSimulationAuditPort auditPort = mock(BidSimulationAuditPort.class);
        PackageCommandPort packageCommandPort = mock(PackageCommandPort.class);
        TenderAnalysisDocumentCommandPort documentCommandPort = mock(TenderAnalysisDocumentCommandPort.class);
        JobPort jobPort = mock(JobPort.class);
        BidSimulationAuditAppService service = new BidSimulationAuditAppService(
                auditPort,
                packageCommandPort,
                documentCommandPort,
                jobPort,
                new ObjectMapper()
        );
        MockMultipartFile tenderFile = file("招标文件.pdf");
        MockMultipartFile bidFile = file("投标文件.pdf");
        when(packageCommandPort.createUnboundTenderPackage(2L, "投标文件.pdf", 10L)).thenReturn(77L);
        when(packageCommandPort.createUnboundBidPackage(2L, "投标文件.pdf", 10L)).thenReturn(88L);
        when(documentCommandPort.uploadAndIndex(eq(2L), eq(77L), eq("招标正文"), eq(tenderFile), eq(10L)))
                .thenReturn(uploaded(7L, 101L, "招标文件.pdf", "招标正文"));
        when(documentCommandPort.uploadAndIndex(eq(2L), eq(88L), eq("投标正文"), eq(bidFile), eq(10L)))
                .thenReturn(uploaded(8L, 102L, "投标文件.pdf", "投标正文"));
        when(auditPort.createBuilding(2L, "投标文件.pdf", 77L, 88L, "[7]", "[8]", 10L))
                .thenReturn(55L);
        when(jobPort.insert(eq(2L), eq(null), eq(JobPort.TYPE_BID_SIMULATION_AUDIT),
                eq(JobPort.STATUS_PENDING), anyString(), eq(10L))).thenReturn(66L);

        BidSimulationAuditAppService.CreateResult result = service.create(
                "",
                List.of(tenderFile),
                List.of(" 招标正文 "),
                List.of(bidFile),
                List.of(" 投标正文 ")
        );

        assertEquals(55L, result.getAuditId());
        assertEquals(66L, result.getJobId());
        assertEquals(77L, result.getTenderPackageId());
        assertEquals(88L, result.getBidPackageId());
        assertEquals(List.of(7L), result.getTenderDocumentIds());
        assertEquals(List.of(8L), result.getBidDocumentIds());
        assertEquals(List.of(101L, 102L), result.getIndexJobIds());
        assertEquals("招标正文", result.getTenderFiles().get(0).getFileType());
        assertEquals("投标正文", result.getBidFiles().get(0).getFileType());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobPort).insert(eq(2L), eq(null), eq(JobPort.TYPE_BID_SIMULATION_AUDIT),
                eq(JobPort.STATUS_PENDING), payloadCaptor.capture(), eq(10L));
        assertTrue(payloadCaptor.getValue().contains("\"auditId\":55"));
        assertTrue(payloadCaptor.getValue().contains("\"tenderPackageId\":77"));
        assertTrue(payloadCaptor.getValue().contains("\"bidPackageId\":88"));
        assertTrue(payloadCaptor.getValue().contains("\"tenderDocumentIds\":[7]"));
        assertTrue(payloadCaptor.getValue().contains("\"bidDocumentIds\":[8]"));
        assertTrue(payloadCaptor.getValue().contains("\"indexJobIds\":[101,102]"));
        verify(auditPort).bindJob(55L, 66L);
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "demo".getBytes());
    }

    private static TenderAnalysisDocumentCommandPort.UploadResult uploaded(long documentId,
                                                                           long jobId,
                                                                           String fileName,
                                                                           String fileType) {
        TenderAnalysisDocumentCommandPort.UploadResult result = new TenderAnalysisDocumentCommandPort.UploadResult();
        result.setDocumentId(documentId);
        result.setJobId(jobId);
        result.setFileName(fileName);
        result.setFileType(fileType);
        return result;
    }
}
