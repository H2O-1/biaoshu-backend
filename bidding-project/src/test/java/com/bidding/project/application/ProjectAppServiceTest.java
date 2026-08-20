package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BidPackageReviewSummaryPort;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.port.DocumentProjectBindPort;
import com.bidding.common.port.OrgLookupPort;
import com.bidding.common.port.ProjectArtifactInvalidationPort;
import com.bidding.common.port.ProjectExpertReviewProgressPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.model.Project;
import com.bidding.project.domain.port.PackageRepository;
import com.bidding.project.domain.port.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAppServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private PackageRepository packageRepository;

    @Mock
    private DocumentCountPort documentCountPort;

    @Mock
    private DocumentProjectBindPort documentProjectBindPort;

    @Mock
    private OrgLookupPort orgLookupPort;

    @Mock
    private ProjectReviewProgressPort projectReviewProgressPort;

    @Mock
    private ProjectExpertReviewProgressPort projectExpertReviewProgressPort;

    @Mock
    private BidPackageReviewSummaryPort bidPackageReviewSummaryPort;

    @Mock
    private ProjectArtifactInvalidationPort projectArtifactInvalidationPort;

    @InjectMocks
    private ProjectAppService projectAppService;

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
    void platformAdminForbidden() {
        Set<String> roles = new HashSet<>();
        roles.add("PLATFORM_ADMIN");
        UserContext.set(new CurrentUser(1L, 1L, "admin", roles, 0, false));

        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.list("", "", "", 1, 20));
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode());
    }

    @Test
    void createSuccess_bindsTenderAndBidPackages() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                unboundTender(50L, 2L, "市政招标文件包")));
        when(packageRepository.findById(60L)).thenReturn(Optional.of(
                unboundBid(60L, 2L, "华建集团")));
        when(projectRepository.existsByOrgIdAndName(eq(2L), eq("市政道路改造"), isNull()))
                .thenReturn(false);
        when(projectRepository.insert(eq(2L), eq("市政道路改造"), isNull(),
                isNull(), isNull(), eq("ACTIVE"), eq(10L))).thenReturn(100L);
        when(packageRepository.bindToProjectIfUnbound(50L, 100L, "TENDER")).thenReturn(1);
        when(packageRepository.bindToProjectIfUnbound(60L, 100L, "BID")).thenReturn(1);

        Project created = new Project(100L, 2L, "市政道路改造", null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
        when(projectRepository.findById(100L)).thenReturn(Optional.of(created));
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "市政招标文件包")));
        stubNeedTenderFiles(100L);

        ProjectAppService.ProjectSummary summary = projectAppService.create(
                "市政道路改造", null, null, null, 50L, List.of(60L));

        assertEquals(100L, summary.getId());
        assertEquals(2L, summary.getOrgId());
        assertEquals(50L, summary.getTenderPackageId());
        assertEquals(1L, summary.getTenderPackageCount());
        assertNull(summary.getAgency());
        assertNull(summary.getProjectType());
        assertNull(summary.getLeaderName());
        assertEquals(ProjectAppService.WORKFLOW_NEED_TENDER_FILES, summary.getWorkflowStatus());
        verify(packageRepository).bindToProjectIfUnbound(50L, 100L, "TENDER");
        verify(packageRepository).bindToProjectIfUnbound(60L, 100L, "BID");
        verify(documentProjectBindPort).bindPackageDocumentsToProject(50L, 100L);
        verify(documentProjectBindPort).bindPackageDocumentsToProject(60L, 100L);
        verify(projectRepository).insert(eq(2L), eq("市政道路改造"), isNull(), isNull(), isNull(),
                eq("ACTIVE"), eq(10L));
    }

    @Test
    void createRejectWhenPackageAlreadyBound() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                boundTender(50L, 2L, 99L, "已占用包")));

        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.create(
                        "市政道路改造", "代理", "公开招标", "张三", 50L, List.of(60L)));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(projectRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void createRejectDuplicateName() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                unboundTender(50L, 2L, "包")));
        when(packageRepository.findById(60L)).thenReturn(Optional.of(
                unboundBid(60L, 2L, "投标公司")));
        when(projectRepository.existsByOrgIdAndName(eq(2L), eq("市政道路改造"), isNull()))
                .thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.create(
                        "市政道路改造", "代理", "公开招标", "张三", 50L, List.of(60L)));
        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        verify(projectRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void createRejectWhenBidPackageIdsEmpty() {
        BizException ex = assertThrows(BizException.class,
                () -> projectAppService.create(
                        "市政道路改造", "代理", "公开招标", "张三", 50L, List.of()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        verify(projectRepository, never()).insert(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void createAutoNameWhenBlank() {
        when(packageRepository.findById(50L)).thenReturn(Optional.of(
                unboundTender(50L, 2L, "市政招标文件包")));
        when(packageRepository.findById(60L)).thenReturn(Optional.of(
                unboundBid(60L, 2L, "华建集团")));
        when(projectRepository.existsByOrgIdAndName(eq(2L), anyString(), isNull()))
                .thenReturn(false);
        when(projectRepository.insert(eq(2L), anyString(), eq("某某代理公司"),
                eq("公开招标"), eq("张三"), eq("ACTIVE"), eq(10L))).thenReturn(101L);
        when(packageRepository.bindToProjectIfUnbound(50L, 101L, "TENDER")).thenReturn(1);
        when(packageRepository.bindToProjectIfUnbound(60L, 101L, "BID")).thenReturn(1);

        when(projectRepository.findById(101L)).thenAnswer(invocation ->
                Optional.of(project(101L, 2L, "未命名-placeholder", "ACTIVE")));
        when(packageRepository.findByProjectIdAndType(101L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 101L, "市政招标文件包")));
        stubNeedTenderFiles(101L);

        ProjectAppService.ProjectSummary summary = projectAppService.create(
                "  ", "某某代理公司", "公开招标", "张三", 50L, List.of(60L));

        assertEquals(101L, summary.getId());
        verify(projectRepository).insert(eq(2L), org.mockito.ArgumentMatchers.argThat(name ->
                        name != null && name.startsWith("未命名-")
                                && name.length() >= "未命名-20260729-1030".length()),
                eq("某某代理公司"), eq("公开招标"), eq("张三"), eq("ACTIVE"), eq(10L));
    }

    @Test
    void getRejectCrossOrg() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(
                project(100L, 9L, "别的组织项目", "ACTIVE")));

        BizException ex = assertThrows(BizException.class, () -> projectAppService.get(100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void updateAllowedWhenDisabled() {
        Project disabled = project(100L, 2L, "旧名称", "DISABLED");
        Project after = new Project(100L, 2L, "新名称", null, null, null,
                "DISABLED", 10L, Instant.now(), Instant.now());
        when(projectRepository.findById(100L))
                .thenReturn(Optional.of(disabled))
                .thenReturn(Optional.of(after));
        when(projectRepository.existsByOrgIdAndName(eq(2L), eq("新名称"), eq(100L)))
                .thenReturn(false);
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "包")));
        stubNeedTenderFiles(100L);

        ProjectAppService.ProjectSummary summary = projectAppService.update(
                100L, "新名称", "  ", null, "");

        assertEquals("新名称", summary.getName());
        assertEquals("DISABLED", summary.getStatus());
        assertNull(summary.getAgency());
        assertNull(summary.getProjectType());
        assertNull(summary.getLeaderName());
        verify(projectRepository).updateBasicInfo(100L, "新名称", null, null, null);
    }

    @Test
    void deleteCleansExpertReviewsBeforeProjectDeleted() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(
                project(100L, 2L, "市政道路改造", "ACTIVE")));
        when(projectArtifactInvalidationPort.deleteExpertReviewsByProjectId(2L, 100L))
                .thenReturn(1);

        projectAppService.delete(100L);

        InOrder inOrder = inOrder(projectRepository, projectArtifactInvalidationPort);
        inOrder.verify(projectRepository).findById(100L);
        inOrder.verify(projectArtifactInvalidationPort).deleteExpertReviewsByProjectId(2L, 100L);
        inOrder.verify(projectRepository).updateStatus(100L, "DELETED");
    }

    @Test
    void listOk() {
        when(projectRepository.searchByOrgId(eq(2L), eq(""), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(project(100L, 2L, "市政道路改造", "ACTIVE")));
        when(projectRepository.countSearchByOrgId(eq(2L), eq(""), eq(""))).thenReturn(1L);
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.empty());
        stubNeedTenderFiles(100L);

        PageResult<ProjectAppService.ProjectSummary> page =
                projectAppService.list("", "", "", 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals(1L, page.getTotal());
        assertEquals(ProjectAppService.WORKFLOW_NEED_TENDER_FILES,
                page.getItems().get(0).getWorkflowStatus());
    }

    @Test
    void resolveWorkflow_needBidWhenZeroBidFiles() {
        stubTenderReady(100L);
        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "BID")).thenReturn(0L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(100L, "BID"))
                .thenReturn(0L);

        assertEquals(ProjectAppService.WORKFLOW_NEED_BID_FILES,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void resolveWorkflow_needTenderWhenIndexNotSuccess() {
        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "TENDER")).thenReturn(2L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(100L, "TENDER"))
                .thenReturn(1L);

        assertEquals(ProjectAppService.WORKFLOW_NEED_TENDER_FILES,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void getUsesTendererOrgNameForTenderParseFailedMessage() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(
                project(100L, 2L, "智慧城市", "ACTIVE")));
        when(orgLookupPort.findNameById(2L)).thenReturn("杭州富阳区发展和改革局");
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "未命名-20260806-1647")));
        when(documentCountPort.resolveIndexStatusByPackageId(50L)).thenReturn("FAILED");
        when(documentCountPort.countActiveByPackageId(50L)).thenReturn(1L);
        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "TENDER")).thenReturn(1L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(100L, "TENDER"))
                .thenReturn(1L);
        when(documentCountPort.findActiveFileNameByProjectIdAndPackageTypeAndFileType(
                100L, "TENDER", ProjectAppService.FILE_TYPE_MAIN_TENDER))
                .thenReturn("招标文件.pdf");
        when(packageRepository.searchByProjectIdAndType(100L, "BID", 0, 500)).thenReturn(List.of());
        when(packageRepository.countByProjectIdAndType(100L, "BID")).thenReturn(0L);
        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "BID")).thenReturn(0L);
        when(documentCountPort.countPackagesWithActiveFilesByProjectIdAndPackageType(100L, "BID"))
                .thenReturn(0L);

        ProjectAppService.ProjectSummary summary = projectAppService.get(100L);

        assertEquals("杭州富阳区发展和改革局", summary.getTendererOrgName());
        assertEquals("杭州富阳区发展和改革局招标文件解析失败", summary.getWorkflowMessage());
        assertEquals("未命名-20260806-1647", summary.getTenderPackage().getName());
    }

    @Test
    void resolveWorkflow_needBidWhenBidIndexFailedOrProcessing() {
        stubTenderReady(100L);
        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "BID")).thenReturn(1L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(100L, "BID"))
                .thenReturn(1L);

        assertEquals(ProjectAppService.WORKFLOW_NEED_BID_FILES,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void resolveWorkflow_readyWhenDocsOkAndNoReview() {
        stubDocsReady(100L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(100L)).thenReturn(null);

        assertEquals(ProjectAppService.WORKFLOW_READY_FOR_AI_REVIEW,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void resolveWorkflow_reviewingWhenInProgress() {
        stubDocsReady(100L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(true);

        assertEquals(ProjectAppService.WORKFLOW_AI_REVIEWING,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void resolveWorkflow_successAndFailed() {
        stubDocsReady(100L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(100L))
                .thenReturn(ReviewPort.STATUS_SUCCESS);
        assertEquals(ProjectAppService.WORKFLOW_AI_REVIEW_SUCCESS,
                projectAppService.resolveWorkflowStatus(2L, 100L));

        when(projectReviewProgressPort.findLatestReviewStatus(100L))
                .thenReturn(ReviewPort.STATUS_FAILED);
        assertEquals(ProjectAppService.WORKFLOW_AI_REVIEW_FAILED,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void resolveWorkflow_expertReviewingWhenAiSuccessAndExpertRunning() {
        stubDocsReady(100L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(100L))
                .thenReturn(ReviewPort.STATUS_SUCCESS);
        when(projectExpertReviewProgressPort.findLatestActiveByProject(2L, 100L))
                .thenReturn(expertReviewProgress(
                        900L, ProjectExpertReviewProgressPort.STATUS_RUNNING, 3, 1));

        assertEquals(ProjectAppService.WORKFLOW_EXPERT_REVIEWING,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void resolveWorkflow_expertReviewSuccessWhenAiSuccessAndExpertSuccess() {
        stubDocsReady(100L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(100L))
                .thenReturn(ReviewPort.STATUS_SUCCESS);
        when(projectExpertReviewProgressPort.findLatestActiveByProject(2L, 100L))
                .thenReturn(expertReviewProgress(
                        900L, ProjectExpertReviewProgressPort.STATUS_SUCCESS, 3, 3));

        assertEquals(ProjectAppService.WORKFLOW_EXPERT_REVIEW_SUCCESS,
                projectAppService.resolveWorkflowStatus(2L, 100L));
    }

    @Test
    void listFilterByWorkflowStatus() {
        when(projectRepository.countSearchByOrgId(eq(2L), eq(""), eq(""))).thenReturn(2L);
        when(projectRepository.searchByOrgId(eq(2L), eq(""), eq(""), eq(0), eq(2)))
                .thenReturn(List.of(
                        project(100L, 2L, "A", "ACTIVE"),
                        project(101L, 2L, "B", "ACTIVE")));
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.empty());
        when(packageRepository.findByProjectIdAndType(101L, "TENDER"))
                .thenReturn(Optional.empty());
        stubNeedTenderFiles(100L);
        stubDocsReadyForList(101L);
        when(projectReviewProgressPort.hasInProgressReview(101L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(101L)).thenReturn(null);

        PageResult<ProjectAppService.ProjectSummary> page = projectAppService.list(
                "", "", ProjectAppService.WORKFLOW_READY_FOR_AI_REVIEW, 1, 20);

        assertEquals(1, page.getItems().size());
        assertEquals(101L, page.getItems().get(0).getId());
        assertEquals(1L, page.getTotal());
    }

    @Test
    void listOk_fillsTenderAndBidListColumns() {
        when(projectRepository.searchByOrgId(eq(2L), eq(""), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(project(100L, 2L, "市政道路改造", "ACTIVE")));
        when(projectRepository.countSearchByOrgId(eq(2L), eq(""), eq(""))).thenReturn(1L);
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "包")));
        when(packageRepository.countByProjectIdAndType(100L, "BID")).thenReturn(2L);

        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "TENDER")).thenReturn(2L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(100L, "TENDER"))
                .thenReturn(0L);
        when(documentCountPort.findActiveFileNameByProjectIdAndPackageTypeAndFileType(
                100L, "TENDER", ProjectAppService.FILE_TYPE_MAIN_TENDER))
                .thenReturn("招标文件_智慧教育云平台.docx");
        when(documentCountPort.countActiveByProjectIdAndPackageType(100L, "BID")).thenReturn(3L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(100L, "BID"))
                .thenReturn(0L);
        when(documentCountPort.countPackagesWithActiveFilesByProjectIdAndPackageType(100L, "BID"))
                .thenReturn(1L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(100L)).thenReturn(null);

        PageResult<ProjectAppService.ProjectSummary> page =
                projectAppService.list("", "", "", 1, 20);

        ProjectAppService.ProjectSummary item = page.getItems().get(0);
        assertEquals("招标文件_智慧教育云平台.docx", item.getMainTenderFileName());
        assertEquals(2L, item.getTenderFileCount());
        assertEquals(2L, item.getBidPackageCount());
        assertEquals(1L, item.getBidPackageUploadedCount());
        assertEquals(3L, item.getBidFileCount());
        assertEquals(ProjectAppService.WORKFLOW_READY_FOR_AI_REVIEW, item.getWorkflowStatus());
    }

    @Test
    void listOk_fillsExpertReviewProgressColumns() {
        when(projectRepository.searchByOrgId(eq(2L), eq(""), eq(""), eq(0), eq(20)))
                .thenReturn(List.of(project(100L, 2L, "市政道路改造", "ACTIVE")));
        when(projectRepository.countSearchByOrgId(eq(2L), eq(""), eq(""))).thenReturn(1L);
        when(packageRepository.findByProjectIdAndType(100L, "TENDER"))
                .thenReturn(Optional.of(boundTender(50L, 2L, 100L, "包")));
        stubDocsReadyForList(100L);
        when(projectReviewProgressPort.hasInProgressReview(100L)).thenReturn(false);
        when(projectReviewProgressPort.findLatestReviewStatus(100L))
                .thenReturn(ReviewPort.STATUS_SUCCESS);
        when(projectExpertReviewProgressPort.findLatestActiveByProject(2L, 100L))
                .thenReturn(expertReviewProgress(
                        900L, ProjectExpertReviewProgressPort.STATUS_RUNNING, 3, 1));

        PageResult<ProjectAppService.ProjectSummary> page =
                projectAppService.list("", "", "", 1, 20);

        ProjectAppService.ProjectSummary item = page.getItems().get(0);
        assertEquals(ProjectAppService.WORKFLOW_EXPERT_REVIEWING, item.getWorkflowStatus());
        assertEquals("专家评审中", item.getWorkflowMessage());
        assertEquals(900L, item.getExpertReviewId());
        assertEquals(ProjectExpertReviewProgressPort.STATUS_RUNNING, item.getExpertReviewStatus());
        assertEquals(3, item.getExpertCount());
        assertEquals(1, item.getSubmittedExpertCount());
    }

    @Test
    void listBidPackagesIncludesLatestReviewSummary() {
        when(projectRepository.findById(100L)).thenReturn(Optional.of(project(100L, 2L, "项目A", "ACTIVE")));
        BizPackage bid = boundBid(60L, 2L, 100L, "华建集团");
        when(packageRepository.searchByProjectIdAndType(100L, "BID", 0, 20))
                .thenReturn(List.of(bid));
        when(packageRepository.countByProjectIdAndType(100L, "BID")).thenReturn(1L);
        when(documentCountPort.countActiveByPackageId(60L)).thenReturn(4L);
        when(documentCountPort.resolveIndexStatusByPackageId(60L)).thenReturn("SUCCESS");
        BidPackageReviewSummaryPort.Summary review = new BidPackageReviewSummaryPort.Summary();
        review.setPackageId(60L);
        review.setSuggestedScoreTotal(new BigDecimal("88.50"));
        review.setFullScoreTotal(new BigDecimal("100.00"));
        review.setQualificationMismatchCount(1);
        review.setQualificationMatchCount(4);
        review.setQualificationUnknownCount(2);
        when(bidPackageReviewSummaryPort.findLatestSuccessByProjectId(100L))
                .thenReturn(Map.of(60L, review));

        PageResult<PackageAppService.PackageSummary> page =
                projectAppService.listBidPackages(100L, 1, 20);

        PackageAppService.PackageSummary item = page.getItems().get(0);
        assertEquals(new BigDecimal("88.50"), item.getSuggestedScoreTotal());
        assertEquals(new BigDecimal("100.00"), item.getFullScoreTotal());
        assertEquals(1, item.getQualificationMismatchCount());
        assertEquals(4, item.getQualificationMatchCount());
        assertEquals(2, item.getQualificationUnknownCount());
    }

    private void stubNeedTenderFiles(long projectId) {
        when(documentCountPort.countActiveByProjectIdAndPackageType(projectId, "TENDER"))
                .thenReturn(0L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(projectId, "TENDER"))
                .thenReturn(0L);
        stubListExtrasEmpty(projectId);
    }

    private void stubTenderReady(long projectId) {
        when(documentCountPort.countActiveByProjectIdAndPackageType(projectId, "TENDER"))
                .thenReturn(1L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(projectId, "TENDER"))
                .thenReturn(0L);
    }

    /** 仅供 resolveWorkflowStatus 单测：两侧文件就绪 */
    private void stubDocsReady(long projectId) {
        stubTenderReady(projectId);
        when(documentCountPort.countActiveByProjectIdAndPackageType(projectId, "BID"))
                .thenReturn(1L);
        when(documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(projectId, "BID"))
                .thenReturn(0L);
    }

    /** toSummary 列表展示字段（无主文件、无投标包） */
    private void stubListExtrasEmpty(long projectId) {
        when(documentCountPort.countActiveByProjectIdAndPackageType(projectId, "BID"))
                .thenReturn(0L);
        when(documentCountPort.countPackagesWithActiveFilesByProjectIdAndPackageType(projectId, "BID"))
                .thenReturn(0L);
        when(documentCountPort.findActiveFileNameByProjectIdAndPackageTypeAndFileType(
                projectId, "TENDER", ProjectAppService.FILE_TYPE_MAIN_TENDER))
                .thenReturn(null);
        when(packageRepository.countByProjectIdAndType(projectId, "BID")).thenReturn(0L);
    }

    /** toSummary + workflow 就绪：两侧有文件 */
    private void stubDocsReadyForList(long projectId) {
        stubDocsReady(projectId);
        when(documentCountPort.countPackagesWithActiveFilesByProjectIdAndPackageType(projectId, "BID"))
                .thenReturn(1L);
        when(documentCountPort.findActiveFileNameByProjectIdAndPackageTypeAndFileType(
                projectId, "TENDER", ProjectAppService.FILE_TYPE_MAIN_TENDER))
                .thenReturn("主招标文件.pdf");
        when(packageRepository.countByProjectIdAndType(projectId, "BID")).thenReturn(1L);
    }

    private static Project project(long id, long orgId, String name, String status) {
        return new Project(id, orgId, name, "代理机构", "公开招标", "张三",
                status, 10L, Instant.now(), Instant.now());
    }

    private static BizPackage unboundTender(long id, long orgId, String name) {
        return new BizPackage(id, orgId, null, "TENDER", name,
                null, null, null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
    }

    private static BizPackage unboundBid(long id, long orgId, String name) {
        return new BizPackage(id, orgId, null, "BID", name,
                "联系人", "13800000000", null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
    }

    private static BizPackage boundTender(long id, long orgId, long projectId, String name) {
        return new BizPackage(id, orgId, projectId, "TENDER", name,
                null, null, null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
    }

    private static BizPackage boundBid(long id, long orgId, long projectId, String name) {
        return new BizPackage(id, orgId, projectId, "BID", name,
                "联系人", "13800000000", null, null, null, null,
                "ACTIVE", 10L, Instant.now(), Instant.now());
    }

    private static ProjectExpertReviewProgressPort.Summary expertReviewProgress(
            long reviewId, String status, int expertCount, int submittedExpertCount) {
        ProjectExpertReviewProgressPort.Summary summary =
                new ProjectExpertReviewProgressPort.Summary();
        summary.setReviewId(reviewId);
        summary.setStatus(status);
        summary.setExpertCount(expertCount);
        summary.setSubmittedExpertCount(submittedExpertCount);
        return summary;
    }
}
