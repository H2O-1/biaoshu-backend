package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiReviewPipelineAppServiceTest {

    @Mock
    private JobPort jobPort;
    @Mock
    private ProjectLookupPort projectLookupPort;
    @Mock
    private PackageLookupPort packageLookupPort;
    @Mock
    private DocumentAccessPort documentAccessPort;

    @InjectMocks
    private AiReviewPipelineAppService appService;

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
    void startSuccess() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(2L);
        project.setStatus("ACTIVE");
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(jobPort.existsActiveByProjectIdAndType(100L, JobPort.TYPE_AI_REVIEW_PIPELINE))
                .thenReturn(false);

        PackageLookupPort.PackageRef tender = pkg(50L, 100L, "TENDER");
        when(packageLookupPort.findActiveByProjectIdAndType(100L, "TENDER")).thenReturn(tender);
        List<PackageLookupPort.PackageRef> bids = new ArrayList<>();
        bids.add(pkg(60L, 100L, "BID"));
        when(packageLookupPort.listActiveByProjectIdAndType(100L, "BID")).thenReturn(bids);

        List<DocumentAccessPort.DocumentRef> tenderDocs = new ArrayList<>();
        tenderDocs.add(doc(1L, 50L, "招标.pdf"));
        when(documentAccessPort.listActiveByPackageId(50L)).thenReturn(tenderDocs);
        List<DocumentAccessPort.DocumentRef> bidDocs = new ArrayList<>();
        bidDocs.add(doc(2L, 60L, "投标.pdf"));
        when(documentAccessPort.listActiveByPackageId(60L)).thenReturn(bidDocs);

        when(jobPort.insert(eq(2L), eq(100L), eq(JobPort.TYPE_AI_REVIEW_PIPELINE),
                eq(JobPort.STATUS_PENDING), anyString(), eq(10L))).thenReturn(501L);

        AiReviewPipelineAppService.StartResult result = appService.start(100L);

        assertEquals(501L, result.getJobId());
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(jobPort).insert(eq(2L), eq(100L), eq(JobPort.TYPE_AI_REVIEW_PIPELINE),
                eq(JobPort.STATUS_PENDING), payloadCap.capture(), eq(10L));
        org.junit.jupiter.api.Assertions.assertTrue(
                payloadCap.getValue().contains("\"projectId\":100"));
    }

    @Test
    void startRejectWhenAlreadyRunning() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(2L);
        project.setStatus("ACTIVE");
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(jobPort.existsActiveByProjectIdAndType(100L, JobPort.TYPE_AI_REVIEW_PIPELINE))
                .thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> appService.start(100L));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(jobPort, never()).insert(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void extractJsonLongFromPayload() {
        String json = "{\"projectId\":10,\"phase\":\"DONE\",\"checklistId\":88,\"reviewId\":201}";
        assertEquals(88L, JobAppService.extractJsonLong(json, "checklistId"));
        assertEquals(201L, JobAppService.extractJsonLong(json, "reviewId"));
        assertEquals(null, JobAppService.extractJsonLong(json, "missing"));
    }

    private static PackageLookupPort.PackageRef pkg(long id, long projectId, String type) {
        PackageLookupPort.PackageRef ref = new PackageLookupPort.PackageRef();
        ref.setId(id);
        ref.setOrgId(2L);
        ref.setProjectId(projectId);
        ref.setType(type);
        ref.setStatus("ACTIVE");
        return ref;
    }

    private static DocumentAccessPort.DocumentRef doc(long id, long packageId, String name) {
        DocumentAccessPort.DocumentRef ref = new DocumentAccessPort.DocumentRef();
        ref.setId(id);
        ref.setOrgId(2L);
        ref.setProjectId(100L);
        ref.setPackageId(packageId);
        ref.setFileName(name);
        ref.setIndexStatus(DocumentAccessPort.INDEX_PENDING);
        return ref;
    }
}
