package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageCommandPort;
import com.bidding.common.port.TenderAnalysisDocumentCommandPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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

class TenderAnalysisAppServiceTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void rerunObsoletesOldAnalysisAndCreatesNewJob() {
        UserContext.set(new CurrentUser(10L, 2L, "u", Set.of("TENDERER"), 1, false));
        TenderAnalysisPort tenderAnalysisPort = mock(TenderAnalysisPort.class);
        PackageCommandPort packageCommandPort = mock(PackageCommandPort.class);
        TenderAnalysisDocumentCommandPort documentCommandPort = mock(TenderAnalysisDocumentCommandPort.class);
        JobPort jobPort = mock(JobPort.class);
        TenderAnalysisAppService service = new TenderAnalysisAppService(
                tenderAnalysisPort,
                packageCommandPort,
                documentCommandPort,
                jobPort,
                new ObjectMapper()
        );

        TenderAnalysisPort.AnalysisSummary existing = new TenderAnalysisPort.AnalysisSummary();
        existing.setId(55L);
        existing.setOrgId(2L);
        existing.setPackageId(66L);
        existing.setName("招标文件.pdf");
        existing.setStatus(TenderAnalysisPort.STATUS_FAILED);
        existing.setDocumentIdsJson("[7,8]");
        existing.setCreatedAt(Instant.parse("2026-08-11T08:30:00Z"));
        existing.setOriginalCreatedAt(Instant.parse("2026-08-10T08:30:00Z"));
        when(tenderAnalysisPort.findAnyById(2L, 55L)).thenReturn(existing);
        when(tenderAnalysisPort.createRerunBuilding(
                2L,
                66L,
                "招标文件.pdf",
                "[7,8]",
                10L,
                55L,
                Instant.parse("2026-08-10T08:30:00Z")))
                .thenReturn(77L);
        when(jobPort.insert(eq(2L), eq(null), eq(JobPort.TYPE_TENDER_ANALYSIS),
                eq(JobPort.STATUS_PENDING), anyString(), eq(10L))).thenReturn(88L);

        TenderAnalysisAppService.CreateResult result = service.rerun(55L);

        assertEquals(77L, result.getAnalysisId());
        assertEquals(88L, result.getJobId());
        assertEquals(66L, result.getPackageId());
        assertEquals(List.of(7L, 8L), result.getDocumentIds());
        verify(tenderAnalysisPort).markObsolete(2L, 55L);
        verify(tenderAnalysisPort).bindJob(77L, 88L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobPort).insert(eq(2L), eq(null), eq(JobPort.TYPE_TENDER_ANALYSIS),
                eq(JobPort.STATUS_PENDING), payloadCaptor.capture(), eq(10L));
        assertTrue(payloadCaptor.getValue().contains("\"analysisId\":77"));
        assertTrue(payloadCaptor.getValue().contains("\"sourceAnalysisId\":55"));
        assertTrue(payloadCaptor.getValue().contains("\"packageId\":66"));
        assertTrue(payloadCaptor.getValue().contains("\"documentIds\":[7,8]"));
    }

    @Test
    void rerunRejectsRunningAnalysis() {
        UserContext.set(new CurrentUser(10L, 2L, "u", Set.of("TENDERER"), 1, false));
        TenderAnalysisPort tenderAnalysisPort = mock(TenderAnalysisPort.class);
        TenderAnalysisAppService service = new TenderAnalysisAppService(
                tenderAnalysisPort,
                mock(PackageCommandPort.class),
                mock(TenderAnalysisDocumentCommandPort.class),
                mock(JobPort.class),
                new ObjectMapper()
        );

        TenderAnalysisPort.AnalysisSummary existing = new TenderAnalysisPort.AnalysisSummary();
        existing.setId(55L);
        existing.setOrgId(2L);
        existing.setStatus(TenderAnalysisPort.STATUS_BUILDING);
        when(tenderAnalysisPort.findAnyById(2L, 55L)).thenReturn(existing);

        BizException ex = assertThrows(BizException.class, () -> service.rerun(55L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
    }
}
