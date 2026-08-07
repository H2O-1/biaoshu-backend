package com.bidding.review.application;

import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewDimensionPort;
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
class ChecklistAppServiceTest {

    @Mock
    private RequirementChecklistPort checklistPort;
    @Mock
    private ReviewDimensionPort reviewDimensionPort;
    @Mock
    private ProjectLookupPort projectLookupPort;
    @Mock
    private PackageLookupPort packageLookupPort;
    @Mock
    private DocumentAccessPort documentAccessPort;
    @Mock
    private JobPort jobPort;

    private ChecklistAppService service;

    @BeforeEach
    void setUp() {
        service = new ChecklistAppService(
                checklistPort, reviewDimensionPort, projectLookupPort, packageLookupPort,
                documentAccessPort, jobPort, new ObjectMapper(), 40, 8, "extract-artifact-v1");
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(9L, 2L, "u", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createUsesDefaultDimensionsAndSuccessDocs() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(2L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);

        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(50L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        pkg.setType("TENDER");
        when(packageLookupPort.findActiveByProjectIdAndType(100L, "TENDER")).thenReturn(pkg);

        DocumentAccessPort.DocumentRef doc = new DocumentAccessPort.DocumentRef();
        doc.setId(7L);
        doc.setOrgId(2L);
        doc.setProjectId(100L);
        doc.setPackageId(50L);
        doc.setIndexStatus(DocumentAccessPort.INDEX_SUCCESS);
        List<DocumentAccessPort.DocumentRef> docs = new ArrayList<>();
        docs.add(doc);
        when(documentAccessPort.listActiveByPackageId(50L)).thenReturn(docs);

        ReviewDimensionPort.Dimension dim = new ReviewDimensionPort.Dimension();
        dim.setCode("QUALIFICATION");
        dim.setDefaultSelected(true);
        dim.setEnabled(true);
        ReviewDimensionPort.Dimension dim2 = new ReviewDimensionPort.Dimension();
        dim2.setCode("SCORING");
        dim2.setDefaultSelected(true);
        dim2.setEnabled(true);
        List<ReviewDimensionPort.Dimension> dims = new ArrayList<>();
        dims.add(dim);
        dims.add(dim2);
        when(reviewDimensionPort.listAll(true)).thenReturn(dims);

        when(checklistPort.nextVersion(2L, 100L)).thenReturn(1);
        when(checklistPort.createBuilding(
                eq(2L), eq(100L), eq(1), anyString(), anyString(), eq("extract-artifact-v1"),
                eq(40), eq(8), eq(9L))).thenReturn(88L);
        when(jobPort.insert(eq(2L), eq(100L), eq(JobPort.TYPE_BUILD_CHECKLIST),
                eq(JobPort.STATUS_PENDING), anyString(), eq(9L))).thenReturn(99L);

        ChecklistAppService.CreateResult result = service.create(100L, List.of("QUALIFICATION"), null);
        assertEquals(88L, result.getChecklistId());
        assertEquals(99L, result.getJobId());
        assertEquals(1, result.getVersion());

        ArgumentCaptor<String> codesCaptor = ArgumentCaptor.forClass(String.class);
        verify(checklistPort).createBuilding(
                eq(2L), eq(100L), eq(1), codesCaptor.capture(), anyString(), eq("extract-artifact-v1"),
                eq(40), eq(8), eq(9L));
        assertEquals(true, codesCaptor.getValue().contains("QUALIFICATION"));
        assertEquals(true, codesCaptor.getValue().contains("SCORING"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(jobPort).insert(eq(2L), eq(100L), eq(JobPort.TYPE_BUILD_CHECKLIST),
                eq(JobPort.STATUS_PENDING), payloadCaptor.capture(), eq(9L));
        assertEquals(true, payloadCaptor.getValue().contains("\"checklistId\":88"));
    }
}
