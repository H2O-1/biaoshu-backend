package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.VectorRecallPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecallAppServiceTest {

    @Mock
    private EmbeddingPort embeddingPort;
    @Mock
    private VectorRecallPort vectorRecallPort;
    @Mock
    private ProjectLookupPort projectLookupPort;

    private RecallAppService recallAppService;

    @BeforeEach
    void setUp() {
        recallAppService = new RecallAppService(
                embeddingPort, vectorRecallPort, projectLookupPort, 40);
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(10L, 2L, "u", roles, 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void recallReturnsHitsAndPassesFilters() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(2L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);

        List<float[]> vectors = new ArrayList<>();
        vectors.add(new float[]{1f, 0f});
        when(embeddingPort.embed(anyList())).thenReturn(vectors);

        VectorRecallPort.RecallHit hit = new VectorRecallPort.RecallHit();
        hit.setChunkId(9L);
        hit.setChunkIndex(0);
        hit.setContent("资格要求");
        hit.setDocumentId(5L);
        hit.setFileName("招标.pdf");
        hit.setPackageId(3L);
        hit.setPackageType("TENDER");
        hit.setPackageName("招标包");
        hit.setScore(0.95f);
        List<VectorRecallPort.RecallHit> hits = new ArrayList<>();
        hits.add(hit);
        when(vectorRecallPort.recall(any(VectorRecallPort.RecallQuery.class))).thenReturn(hits);

        List<Long> packageIds = new ArrayList<>();
        packageIds.add(3L);
        List<RecallAppService.HitSummary> result =
                recallAppService.recall(100L, "资格条件", 5, packageIds, null);

        assertEquals(1, result.size());
        assertEquals(9L, result.get(0).getChunkId());
        assertEquals("TENDER", result.get(0).getPackageType());
        assertEquals(5L, result.get(0).getDocumentId());

        ArgumentCaptor<VectorRecallPort.RecallQuery> captor =
                ArgumentCaptor.forClass(VectorRecallPort.RecallQuery.class);
        verify(vectorRecallPort).recall(captor.capture());
        assertEquals(2L, captor.getValue().getOrgId());
        assertEquals(100L, captor.getValue().getProjectId());
        assertEquals(5, captor.getValue().getTopK());
        assertEquals(1, captor.getValue().getPackageIds().size());
        assertEquals(3L, captor.getValue().getPackageIds().get(0));
    }

    @Test
    void recallRejectsEmptyQuery() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(2L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);

        assertThrows(BizException.class,
                () -> recallAppService.recall(100L, "  ", 5, null, null));
    }

    @Test
    void recallRejectsOtherOrgProject() {
        ProjectLookupPort.ProjectRef project = new ProjectLookupPort.ProjectRef();
        project.setId(100L);
        project.setOrgId(99L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);

        BizException ex = assertThrows(BizException.class,
                () -> recallAppService.recall(100L, "资格", 5, null, null));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        assertTrue(ex.getMessage().contains("项目不存在"));
    }
}
