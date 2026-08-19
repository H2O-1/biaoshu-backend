package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void rerunMarksOldAuditObsoleteAndCreatesNewAuditJob() {
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
        BidSimulationAuditPort.AuditSummary existing = audit(
                BidSimulationAuditPort.STATUS_SUCCESS,
                77L,
                88L,
                "[7]",
                "[8]");
        when(auditPort.findById(2L, 55L)).thenReturn(existing);
        when(auditPort.markObsolete(2L, 55L)).thenReturn(1);
        when(auditPort.createBuilding(2L, "投标文件.pdf", 77L, 88L, "[7]", "[8]", 10L))
                .thenReturn(56L);
        when(jobPort.insert(eq(2L), eq(null), eq(JobPort.TYPE_BID_SIMULATION_AUDIT),
                eq(JobPort.STATUS_PENDING), anyString(), eq(10L))).thenReturn(67L);

        BidSimulationAuditAppService.CreateResult result = service.rerun(55L);

        assertEquals(56L, result.getAuditId());
        assertEquals(67L, result.getJobId());
        assertEquals(77L, result.getTenderPackageId());
        assertEquals(88L, result.getBidPackageId());
        assertEquals(List.of(7L), result.getTenderDocumentIds());
        assertEquals(List.of(8L), result.getBidDocumentIds());
        assertTrue(result.getIndexJobIds().isEmpty());
        assertTrue(result.getTenderFiles().isEmpty());
        assertTrue(result.getBidFiles().isEmpty());

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobPort).insert(eq(2L), eq(null), eq(JobPort.TYPE_BID_SIMULATION_AUDIT),
                eq(JobPort.STATUS_PENDING), payloadCaptor.capture(), eq(10L));
        assertTrue(payloadCaptor.getValue().contains("\"auditId\":56"));
        assertTrue(payloadCaptor.getValue().contains("\"sourceAuditId\":55"));
        assertTrue(payloadCaptor.getValue().contains("\"tenderPackageId\":77"));
        assertTrue(payloadCaptor.getValue().contains("\"bidPackageId\":88"));
        assertTrue(payloadCaptor.getValue().contains("\"tenderDocumentIds\":[7]"));
        assertTrue(payloadCaptor.getValue().contains("\"bidDocumentIds\":[8]"));
        assertTrue(payloadCaptor.getValue().contains("\"indexJobIds\":[]"));
        verify(auditPort).markObsolete(2L, 55L);
        verify(auditPort).bindJob(56L, 67L);
    }

    @Test
    void rerunRejectsMissingAudit() {
        UserContext.set(new CurrentUser(10L, 2L, "u", Set.of("BIDDER"), 1, false));
        BidSimulationAuditPort auditPort = mock(BidSimulationAuditPort.class);
        BidSimulationAuditAppService service = new BidSimulationAuditAppService(
                auditPort,
                mock(PackageCommandPort.class),
                mock(TenderAnalysisDocumentCommandPort.class),
                mock(JobPort.class),
                new ObjectMapper()
        );

        BizException ex = assertThrows(BizException.class, () -> service.rerun(55L));

        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void rerunRejectsBuildingAudit() {
        UserContext.set(new CurrentUser(10L, 2L, "u", Set.of("BIDDER"), 1, false));
        BidSimulationAuditPort auditPort = mock(BidSimulationAuditPort.class);
        BidSimulationAuditAppService service = new BidSimulationAuditAppService(
                auditPort,
                mock(PackageCommandPort.class),
                mock(TenderAnalysisDocumentCommandPort.class),
                mock(JobPort.class),
                new ObjectMapper()
        );
        when(auditPort.findById(2L, 55L)).thenReturn(audit(
                BidSimulationAuditPort.STATUS_BUILDING,
                77L,
                88L,
                "[7]",
                "[8]"));

        BizException ex = assertThrows(BizException.class, () -> service.rerun(55L));

        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
    }

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/pdf", "demo".getBytes());
    }

    private static BidSimulationAuditPort.AuditSummary audit(String status,
                                                             long tenderPackageId,
                                                             long bidPackageId,
                                                             String tenderDocumentIdsJson,
                                                             String bidDocumentIdsJson) {
        BidSimulationAuditPort.AuditSummary audit = new BidSimulationAuditPort.AuditSummary();
        audit.setId(55L);
        audit.setOrgId(2L);
        audit.setName("投标文件.pdf");
        audit.setStatus(status);
        audit.setTenderPackageId(tenderPackageId);
        audit.setBidPackageId(bidPackageId);
        audit.setTenderDocumentIdsJson(tenderDocumentIdsJson);
        audit.setBidDocumentIdsJson(bidDocumentIdsJson);
        return audit;
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
