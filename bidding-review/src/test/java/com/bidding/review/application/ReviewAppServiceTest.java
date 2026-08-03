package com.bidding.review.application;

import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAppServiceTest {

    @Mock
    private ReviewPort reviewPort;
    @Mock
    private RequirementChecklistPort checklistPort;
    @Mock
    private ProjectLookupPort projectLookupPort;
    @Mock
    private PackageLookupPort packageLookupPort;
    @Mock
    private DocumentAccessPort documentAccessPort;
    @Mock
    private JobPort jobPort;

    private ReviewAppService service;

    @BeforeEach
    void setUp() {
        service = new ReviewAppService(
                reviewPort, checklistPort, projectLookupPort, packageLookupPort,
                documentAccessPort, jobPort, new ObjectMapper(), 30, 6, "review-v2");
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(9L, 2L, "u", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createUsesLatestSuccessChecklistAndAllBidPackages() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(2L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);

        RequirementChecklistPort.ChecklistDetail checklist =
                new RequirementChecklistPort.ChecklistDetail();
        checklist.setId(88L);
        checklist.setOrgId(2L);
        checklist.setProjectId(100L);
        checklist.setStatus(RequirementChecklistPort.STATUS_SUCCESS);
        RequirementChecklistPort.ItemView item = new RequirementChecklistPort.ItemView();
        item.setId(1L);
        item.setText("要求");
        item.setDimensionCode("QUALIFICATION");
        List<RequirementChecklistPort.ItemView> items = new ArrayList<>();
        items.add(item);
        checklist.setItems(items);
        when(checklistPort.findLatestSuccess(2L, 100L)).thenReturn(checklist);

        PackageLookupPort.PackageRef bid = new PackageLookupPort.PackageRef();
        bid.setId(80L);
        bid.setOrgId(2L);
        bid.setProjectId(100L);
        bid.setType("BID");
        List<PackageLookupPort.PackageRef> bids = new ArrayList<>();
        bids.add(bid);
        when(packageLookupPort.listActiveByProjectIdAndType(100L, "BID")).thenReturn(bids);

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(7L);
        doc.setOrgId(2L);
        doc.setProjectId(100L);
        doc.setPackageId(80L);
        doc.setIndexStatus(DocumentAccessPort.INDEX_SUCCESS);
        List<DocumentAccessPort.DocumentRef> docs = new ArrayList<>();
        docs.add(doc);
        when(documentAccessPort.listActiveByPackageId(80L)).thenReturn(docs);

        when(reviewPort.nextVersion(2L, 100L)).thenReturn(1);
        when(reviewPort.createBuilding(
                eq(2L), eq(100L), eq(88L), eq(1), anyString(), eq("review-v2"),
                eq(30), eq(6), eq(9L))).thenReturn(201L);
        when(jobPort.insert(eq(2L), eq(100L), eq(JobPort.TYPE_REVIEW),
                eq(JobPort.STATUS_PENDING), anyString(), eq(9L))).thenReturn(301L);

        ReviewAppService.CreateResult result = service.create(100L, null, null);
        assertEquals(201L, result.getReviewId());
        assertEquals(301L, result.getJobId());
        assertEquals(1, result.getVersion());

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(jobPort).insert(eq(2L), eq(100L), eq(JobPort.TYPE_REVIEW),
                eq(JobPort.STATUS_PENDING), payloadCap.capture(), eq(9L));
        assertEquals(true, payloadCap.getValue().contains("\"reviewId\":201"));
    }
}
