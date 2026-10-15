package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobAppServiceTest {

    @Mock
    private JobPort jobPort;

    @Mock
    private DocumentAccessPort documentAccessPort;

    @Mock
    private PackageLookupPort packageLookupPort;

    @InjectMocks
    private JobAppService jobAppService;

    @BeforeEach
    void setUp() {
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(10L, 2L, "tenderer01", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createIndexJobWritesPendingAndMarksProcessing() {
        when(jobPort.insert(eq(2L), eq(100L), eq(JobPort.TYPE_INDEX), eq(JobPort.STATUS_PENDING),
                eq("{\"documentId\":9}"), eq(10L))).thenReturn(55L);

        long jobId = jobAppService.createIndexJob(2L, 100L, 9L, 10L);

        assertEquals(55L, jobId);
        verify(documentAccessPort).updateIndexStatus(9L, DocumentAccessPort.INDEX_PROCESSING);
    }

    @Test
    void startDocumentIndexCreatesJob() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(9L);
        doc.setOrgId(2L);
        doc.setProjectId(100L);
        doc.setPackageId(50L);
        doc.setIndexStatus(DocumentAccessPort.INDEX_PENDING);
        when(documentAccessPort.findActiveById(9L)).thenReturn(doc);

        when(jobPort.insert(eq(2L), eq(100L), eq(JobPort.TYPE_INDEX), eq(JobPort.STATUS_PENDING),
                eq("{\"documentId\":9}"), eq(10L))).thenReturn(55L);

        JobPort.JobRef created = new JobPort.JobRef();
        created.setId(55L);
        created.setOrgId(2L);
        created.setProjectId(100L);
        created.setType(JobPort.TYPE_INDEX);
        created.setStatus(JobPort.STATUS_PENDING);
        created.setPayloadJson("{\"documentId\":9}");
        created.setCreatedBy(10L);
        when(jobPort.findById(55L)).thenReturn(Optional.of(created));

        JobAppService.JobSummary summary = jobAppService.startDocumentIndex(50L, 9L);

        assertEquals(55L, summary.getId());
        assertEquals(JobPort.STATUS_PENDING, summary.getStatus());
        verify(documentAccessPort).updateIndexStatus(9L, DocumentAccessPort.INDEX_PROCESSING);
    }

    @Test
    void startDocumentIndexRejectsWhenProcessing() {
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        when(packageLookupPort.findActiveById(50L)).thenReturn(pkg);

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(9L);
        doc.setOrgId(2L);
        doc.setPackageId(50L);
        doc.setIndexStatus(DocumentAccessPort.INDEX_PROCESSING);
        when(documentAccessPort.findActiveById(9L)).thenReturn(doc);

        BizException ex = assertThrows(BizException.class,
                () -> jobAppService.startDocumentIndex(50L, 9L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(jobPort, never()).insert(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void getJobHidesOtherOrg() {
        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(55L);
        job.setOrgId(99L);
        job.setType(JobPort.TYPE_INDEX);
        job.setStatus(JobPort.STATUS_PENDING);
        when(jobPort.findById(55L)).thenReturn(Optional.of(job));

        BizException ex = assertThrows(BizException.class, () -> jobAppService.getJob(55L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void getJobExposesBidSimulationAuditId() {
        UserContext.set(new CurrentUser(10L, 2L, "bidder01", Set.of("BIDDER"), 0, false));
        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(56L);
        job.setOrgId(2L);
        job.setType(JobPort.TYPE_BID_SIMULATION_AUDIT);
        job.setStatus(JobPort.STATUS_SUCCESS);
        job.setPayloadJson("{\"auditId\":55,\"bidPackageId\":88}");
        when(jobPort.findById(56L)).thenReturn(Optional.of(job));

        JobAppService.JobSummary summary = jobAppService.getJob(56L);

        assertEquals(Long.valueOf(55L), summary.getAuditId());
    }

    @Test
    void claimAndMarkSuccess() {
        JobPort.JobRef running = new JobPort.JobRef();
        running.setId(55L);
        running.setStatus(JobPort.STATUS_RUNNING);
        when(jobPort.claimNextRunnable(JobPort.TYPE_INDEX, "w1")).thenReturn(Optional.of(running));

        Optional<JobPort.JobRef> claimed = jobAppService.claimNextIndex("w1");
        assertTrue(claimed.isPresent());

        jobAppService.markIndexSuccess(55L, 9L, "向量化完成");
        verify(jobPort).markSuccess(55L, "向量化完成");
        verify(documentAccessPort).updateIndexStatus(9L, DocumentAccessPort.INDEX_SUCCESS);
    }

    @Test
    void markIndexFailedUpdatesDocument() {
        jobAppService.markIndexFailed(55L, 9L, "boom");
        verify(jobPort).markFailed(55L, "boom");
        verify(documentAccessPort).updateIndexStatus(9L, DocumentAccessPort.INDEX_FAILED);
    }
}
