package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BinaryObjectStoragePort;
import com.bidding.common.port.ExpertAccountPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.AttachmentRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.CompanyRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.EvidenceChunkRef;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ItemRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.MemberRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewItemSeed;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewRow;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertReviewAppServiceTest {

    @Mock
    private ProjectLookupPort projectLookupPort;

    @Mock
    private ProjectReviewProgressPort projectReviewProgressPort;

    @Mock
    private PackageLookupPort packageLookupPort;

    @Mock
    private ExpertAccountPort expertAccountPort;

    @Mock
    private JdbcExpertReviewStore expertReviewStore;

    @Mock
    private BinaryObjectStoragePort objectStoragePort;

    private ExpertReviewAppService service;

    @BeforeEach
    void setUp() {
        service = new ExpertReviewAppService(
                projectLookupPort,
                projectReviewProgressPort,
                packageLookupPort,
                expertAccountPort,
                expertReviewStore,
                objectStoragePort,
                10 * 1024 * 1024L,
                5
        );
        UserContext.set(new CurrentUser(9L, 2L, "tenderer", Set.of("TENDERER"), 0, false));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void addMemberCreatesBuildingReviewAndTemporaryAccount() {
        ProjectLookupPort.ProjectRef project = project();
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(projectReviewProgressPort.findLatestReviewStatus(100L)).thenReturn("SUCCESS");
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(null, buildingReview());
        when(expertReviewStore.createBuilding(2L, 100L, 9L)).thenReturn(501L);
        when(expertReviewStore.existsMemberDisplayName(501L, "张三")).thenReturn(false);
        when(expertReviewStore.insertMember(501L, 100L, 77L, "expert_x", "张三", "hash-1", "ACTIVE"))
                .thenReturn(88L);
        when(expertReviewStore.countConfirmedMembers(501L)).thenReturn(1);
        when(expertReviewStore.updateExpertCount(501L, 1)).thenReturn(1);
        ExpertAccountPort.CreateResult account = new ExpertAccountPort.CreateResult();
        account.setUserId(77L);
        account.setUsername("expert_x");
        account.setDisplayName("张三");
        account.setTemporaryPassword("Temp1234!");
        account.setPasswordHash("hash-1");
        when(expertAccountPort.createTemporaryExpert(2L, "张三", 9L)).thenReturn(account);

        ExpertReviewAppService.MemberResult result = service.addMember(100L, " 张三 ");

        assertEquals(501L, result.getReviewId());
        assertEquals(88L, result.getMemberId());
        assertEquals(77L, result.getUserId());
        assertEquals("expert_x", result.getUsername());
        assertEquals("张三", result.getDisplayName());
        assertEquals("Temp1234!", result.getTemporaryPassword());
        assertTrue(!result.isAppended());
    }

    @Test
    void removeMemberDeletesTemporaryAccountAndRefreshesCount() {
        ProjectLookupPort.ProjectRef project = project();
        ReviewRow review = buildingReview();
        MemberRow member = member(88L, 77L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findMemberById(501L, 88L)).thenReturn(member);
        when(expertReviewStore.markMemberDeleted(501L, 88L)).thenReturn(1);
        when(expertReviewStore.countConfirmedMembers(501L)).thenReturn(1);
        when(expertReviewStore.updateExpertCount(501L, 1)).thenReturn(1);

        service.removeMember(100L, 88L);

        verify(expertReviewStore).markMemberDeleted(501L, 88L);
        verify(expertAccountPort).deleteTemporaryExpert(77L);
        verify(expertReviewStore).updateExpertCount(501L, 1);
    }

    @Test
    void removeMemberRejectsPushedReview() {
        ProjectLookupPort.ProjectRef project = project();
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(expertReviewStore.findLatestByProject(2L, 100L))
                .thenReturn(runningReview(Instant.parse("2026-08-20T09:00:00Z")));
        when(expertReviewStore.findMemberById(501L, 88L)).thenReturn(member(88L, 77L));

        BizException exception = assertThrows(BizException.class,
                () -> service.removeMember(100L, 88L));

        assertEquals(ErrorCode.CONFLICT, exception.errorCode());
        verify(expertReviewStore, never()).markMemberDeleted(anyLong(), anyLong());
        verify(expertAccountPort, never()).deleteTemporaryExpert(anyLong());
    }

    @Test
    void appendCreatesPendingMemberWithoutReviewItems() {
        ProjectLookupPort.ProjectRef project = project();
        ReviewRow review = runningReview(Instant.parse("2026-08-20T09:00:00Z"));
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(projectReviewProgressPort.findLatestReviewStatus(100L)).thenReturn("SUCCESS");
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.existsMemberDisplayName(501L, "李四")).thenReturn(false);
        ExpertAccountPort.CreateResult account = new ExpertAccountPort.CreateResult();
        account.setUserId(78L);
        account.setUsername("expert_y");
        account.setDisplayName("李四");
        account.setTemporaryPassword("Temp5678!");
        account.setPasswordHash("hash-2");
        when(expertAccountPort.createTemporaryExpert(2L, "李四", 9L)).thenReturn(account);
        when(expertReviewStore.insertMember(501L, 100L, 78L, "expert_y", "李四", "hash-2", "PENDING_APPEND"))
                .thenReturn(89L);
        when(expertReviewStore.countConfirmedMembers(501L)).thenReturn(2);
        when(expertReviewStore.updateExpertCount(501L, 2)).thenReturn(1);

        ExpertReviewAppService.MemberResult result = service.append(100L, " 李四 ");

        assertEquals(89L, result.getMemberId());
        assertEquals("expert_y", result.getUsername());
        assertTrue(result.isAppended());
        verify(expertReviewStore, never()).listSeedTemplates(anyLong());
        verify(expertReviewStore, never()).insertReviewItemsForMembers(anyLong(), anyLong(), any(), any());
    }

    @Test
    void pushAppendActivatesPendingMembersAndSeedsItems() {
        ProjectLookupPort.ProjectRef project = project();
        ReviewRow review = runningReview(Instant.parse("2026-08-20T09:00:00Z"));
        MemberRow pendingA = pendingAppendMember(89L, 78L);
        MemberRow pendingB = pendingAppendMember(90L, 79L);
        List<MemberRow> pendingMembers = List.of(pendingA, pendingB);
        List<ReviewItemSeed> seeds = List.of(
                seed(701L, 9001L, 81L, "QUALIFICATION", "Q1"),
                seed(702L, 9002L, 81L, "SCORING", "S1")
        );
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(projectReviewProgressPort.findLatestReviewStatus(100L)).thenReturn("SUCCESS");
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.listPendingAppendMembers(501L)).thenReturn(pendingMembers);
        when(expertReviewStore.listSeedTemplates(501L)).thenReturn(seeds);
        when(expertReviewStore.insertReviewItemsForMembers(501L, 100L, pendingMembers, seeds)).thenReturn(4);
        when(expertReviewStore.activatePendingAppendMembers(501L, List.of(89L, 90L))).thenReturn(2);
        when(expertReviewStore.countConfirmedMembers(501L)).thenReturn(4);
        when(expertReviewStore.updateExpertCount(501L, 4)).thenReturn(1);

        ExpertReviewAppService.AppendPushResult result = service.pushAppend(100L);

        assertEquals(501L, result.getReviewId());
        assertEquals(100L, result.getProjectId());
        assertEquals("RUNNING", result.getStatus());
        assertEquals(2, result.getAppendedExpertCount());
        assertEquals(4, result.getExpertCount());
        assertEquals(4, result.getReviewItemCount());
        verify(expertReviewStore).activatePendingAppendMembers(501L, List.of(89L, 90L));
    }

    @Test
    void removeMemberDeletesPendingAppendAfterReviewRunning() {
        ProjectLookupPort.ProjectRef project = project();
        ReviewRow review = runningReview(Instant.parse("2026-08-20T09:00:00Z"));
        MemberRow member = pendingAppendMember(89L, 78L);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findMemberById(501L, 89L)).thenReturn(member);
        when(expertReviewStore.markMemberDeleted(501L, 89L)).thenReturn(1);
        when(expertReviewStore.countConfirmedMembers(501L)).thenReturn(2);
        when(expertReviewStore.updateExpertCount(501L, 2)).thenReturn(1);

        service.removeMember(100L, 89L);

        verify(expertReviewStore).markMemberDeleted(501L, 89L);
        verify(expertAccountPort).deleteTemporaryExpert(78L);
        verify(expertReviewStore).updateExpertCount(501L, 2);
    }

    @Test
    void pushLocksBuildingReviewAndSeedsCompanies() {
        ProjectLookupPort.ProjectRef project = project();
        when(projectLookupPort.findActiveById(100L)).thenReturn(project);
        when(projectReviewProgressPort.findLatestReviewStatus(100L)).thenReturn("SUCCESS");
        ReviewRow review = buildingReview();
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.countMembers(501L)).thenReturn(2);
        List<MemberRow> members = List.of(member(88L, 77L), member(89L, 78L));
        when(expertReviewStore.listMembers(501L)).thenReturn(members);
        PackageLookupPort.PackageRef pkg = new PackageLookupPort.PackageRef();
        pkg.setId(81L);
        pkg.setOrgId(2L);
        pkg.setProjectId(100L);
        pkg.setType("BID");
        pkg.setName("投标包A");
        when(packageLookupPort.listActiveByProjectIdAndType(100L, "BID")).thenReturn(List.of(pkg));
        List<ReviewItemSeed> seeds = List.of(
                seed(701L, 9001L, 81L, "QUALIFICATION", "Q1"),
                seed(702L, 9002L, 81L, "SCORING", "S1")
        );
        when(expertReviewStore.findLatestAiReviewSeeds(2L, 100L, List.of(81L))).thenReturn(seeds);
        when(expertReviewStore.updatePushInfo(
                501L, Instant.parse("2026-08-20T09:00:00Z"), "请评审", 2, 9L))
                .thenReturn(1);

        ExpertReviewAppService.PushResult result =
                service.push(100L, Instant.parse("2026-08-20T09:00:00Z"), "请评审");

        assertEquals(501L, result.getReviewId());
        assertEquals(100L, result.getProjectId());
        assertEquals("RUNNING", result.getStatus());
        assertEquals(2, result.getExpertCount());
        assertEquals(2, result.getReviewItemCount());
        assertEquals("投标项目A", result.getProjectName());
        assertNotNull(result.getDeadlineAt());
        verify(expertReviewStore).insertReviewItemsForMembers(501L, 100L, members, seeds);
    }

    @Test
    void getCurrentTaskReturnsCompaniesAndItemsForExpert() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        Instant deadlineAt = Instant.now().plusSeconds(2 * 86_400L);
        ReviewRow review = runningReview(deadlineAt);
        review.setPusherName("陈明");
        MemberRow member = member(88L, 77L);
        CompanyRow company = company();
        ItemRow item = item();
        item.setTenderEvidenceJson("[{\"fragmentId\":27833,\"page\":44,\"quote\":\"招标评分标准\"}]");
        item.setEvidenceJson("[{\"fragmentId\":30012,\"page\":18,\"quote\":\"投标技术方案\"}]");
        Map<Long, EvidenceChunkRef> refs = new LinkedHashMap<>();
        refs.put(27833L, evidenceRef(27833L, 551L, 424L, "招标文件.docx"));
        refs.put(30012L, evidenceRef(30012L, 660L, 81L, "投标文件.docx"));
        AttachmentRow attachment = attachment(901L, 801L, "说明.png", "image/png", "object-key");

        when(expertReviewStore.findCurrentMemberByUserId(77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(item));
        when(expertReviewStore.findEvidenceChunkRefs(any())).thenReturn(refs);
        when(expertReviewStore.listAttachmentsByItemIds(List.of(801L)))
                .thenReturn(Map.of(801L, List.of(attachment)));
        when(expertReviewStore.listCompanies(501L)).thenReturn(List.of(company));

        ExpertReviewAppService.CurrentTaskDetail detail = service.getCurrentTask();

        assertEquals(88L, detail.getTaskId());
        assertEquals(501L, detail.getReviewId());
        assertEquals("投标项目A", detail.getProjectName());
        assertEquals("RUNNING", detail.getReviewStatus());
        assertEquals("陈明", detail.getPusherName());
        assertFalse(detail.isReadOnly());
        assertTrue(detail.getRemainingDays() >= 1);
        assertEquals(1, detail.getCompanies().size());
        ExpertReviewAppService.TaskCompany taskCompany = detail.getCompanies().get(0);
        assertEquals(81L, taskCompany.getBidPackageId());
        assertEquals("投标包A", taskCompany.getSupplierName());
        assertEquals(1, taskCompany.getItems().size());
        ExpertReviewAppService.TaskItem taskItem = taskCompany.getItems().get(0);
        assertEquals(801L, taskItem.getItemId());
        assertEquals(701L, taskItem.getSourceReviewItemId());
        assertEquals("SCORING", taskItem.getItemType());
        assertEquals(new BigDecimal("9.50"), taskItem.getAiSuggestedScore());
        assertEquals(new BigDecimal("10.00"), taskItem.getFullScore());
        assertEquals(1, taskItem.getTenderEvidence().size());
        assertEquals(Long.valueOf(27833L), taskItem.getTenderEvidence().get(0).getFragmentId());
        assertEquals(Integer.valueOf(44), taskItem.getTenderEvidence().get(0).getPage());
        assertEquals("招标评分标准", taskItem.getTenderEvidence().get(0).getQuote());
        assertEquals(Long.valueOf(551L), taskItem.getTenderEvidence().get(0).getDocumentId());
        assertEquals(Long.valueOf(424L), taskItem.getTenderEvidence().get(0).getPackageId());
        assertEquals("招标文件.docx", taskItem.getTenderEvidence().get(0).getFileName());
        assertEquals(1, taskItem.getBidEvidence().size());
        assertEquals(Long.valueOf(30012L), taskItem.getBidEvidence().get(0).getFragmentId());
        assertEquals(Integer.valueOf(18), taskItem.getBidEvidence().get(0).getPage());
        assertEquals("投标技术方案", taskItem.getBidEvidence().get(0).getQuote());
        assertEquals(Long.valueOf(660L), taskItem.getBidEvidence().get(0).getDocumentId());
        assertEquals(Long.valueOf(81L), taskItem.getBidEvidence().get(0).getPackageId());
        assertEquals("投标文件.docx", taskItem.getBidEvidence().get(0).getFileName());
        assertEquals(1, taskItem.getAttachments().size());
        assertEquals(901L, taskItem.getAttachments().get(0).getAttachmentId());
        assertEquals("说明.png", taskItem.getAttachments().get(0).getFileName());
        assertNull(taskItem.getScoreValue());
    }

    @Test
    void getSnapshotReturnsProjectExpertReviewAggregates() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        review.setHasAnyCompleteSubmission(true);
        review.setSubmittedExpertCount(1);
        review.setSubmittedCompanyCount(3);
        MemberRow submitted = submittedMember(88L, 77L);
        MemberRow notSubmitted = member(89L, 78L);
        CompanyRow companyA = company(301L, 81L, "投标单位A");
        CompanyRow companyB = company(302L, 82L, "投标单位B");
        companyB.setCompanyStatus("PENDING_VETO");
        companyB.setDisplayMode("PENDING_CONFIRMATION");
        companyB.setVetoItemCount(1);
        companyB.setVetoReasonSummary("资格不符合");
        companyB.setVetoExpertNames("专家77");
        CompanyRow companyC = company(303L, 83L, "投标单位C");
        companyC.setCompanyStatus("VETOED");

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.listMembers(501L)).thenReturn(List.of(submitted, notSubmitted));
        when(expertReviewStore.listCompanies(501L)).thenReturn(List.of(companyA, companyB, companyC));
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(
                scoringItem(801L, 81L, "9.00"),
                scoringItem(802L, 82L, "8.00"),
                scoringItem(803L, 83L, "10.00")
        ));
        when(expertReviewStore.listItemsForMember(501L, 89L)).thenReturn(List.of(
                pendingScoringItem(811L, 89L, 81L),
                pendingScoringItem(812L, 89L, 82L),
                pendingScoringItem(813L, 89L, 83L)
        ));
        when(expertReviewStore.listAttachmentsByItemIds(List.of(801L, 802L, 803L, 811L, 812L, 813L)))
                .thenReturn(Map.of());

        ExpertReviewAppService.ReviewSnapshot snapshot = service.getSnapshot(100L);

        assertEquals(501L, snapshot.getReviewId());
        assertEquals("投标项目A", snapshot.getProjectName());
        assertTrue(snapshot.isHasAnyCompleteSubmission());
        assertEquals(2, snapshot.getExpertMembers().size());
        assertTrue(snapshot.getExpertMembers().get(0).isSubmitted());
        assertFalse(snapshot.getExpertMembers().get(1).isSubmitted());
        assertEquals(3, snapshot.getCompanyTabs().size());
        assertEquals(new BigDecimal("9.00"), snapshot.getCompanyTabs().get(0).getAverageScore());
        assertEquals(1, snapshot.getCompanyTabs().get(0).getRank());
        assertEquals(new BigDecimal("8.00"), snapshot.getCompanyTabs().get(1).getAverageScore());
        assertEquals(2, snapshot.getCompanyTabs().get(1).getRank());
        assertNull(snapshot.getCompanyTabs().get(2).getAverageScore());
        assertNull(snapshot.getCompanyTabs().get(2).getRank());
        assertEquals(2, snapshot.getAverageRanking().size());
        assertEquals("投标单位A", snapshot.getAverageRanking().get(0).getSupplierName());
        assertEquals(1, snapshot.getVetoPendingItems().size());
        assertEquals(302L, snapshot.getVetoPendingItems().get(0).getCompanyId());
        assertEquals(1, snapshot.getScoreCompareItems().get(0).getExpertScores().size());
        assertTrue(snapshot.getScoreCompareItems().get(2).getExpertScores().isEmpty());
        assertEquals(3, snapshot.getItemReviewGroups().size());
        assertEquals(2, snapshot.getItemReviewGroups().get(0).getExpertReviews().size());
        assertEquals("专家77", snapshot.getItemReviewGroups().get(0).getExpertReviews().get(0).getDisplayName());
        assertEquals(new BigDecimal("9.00"), snapshot.getItemReviewGroups().get(0).getExpertReviews().get(0).getScoreValue());
        assertEquals("NOT_SUBMITTED", snapshot.getItemReviewGroups().get(0).getExpertReviews().get(1).getSubmitStatus());
        assertEquals(new BigDecimal("9.00"), snapshot.getItemReviewGroups().get(0).getAverageScore());
    }

    @Test
    void getSnapshotReturnsItemReviewGroupsWithQualificationAndAttachments() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        review.setHasAnyCompleteSubmission(true);
        MemberRow expertA = submittedMember(88L, 77L);
        expertA.setDisplayName("专家甲");
        MemberRow expertB = submittedMember(89L, 78L);
        expertB.setDisplayName("专家乙");
        CompanyRow company = company(301L, 81L, "投标单位A");
        ItemRow qualificationA = qualificationItem(800L, 88L, 81L, 9001L, 700L);
        qualificationA.setResult("PASS");
        qualificationA.setComment("资质齐全");
        qualificationA.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        ItemRow qualificationB = qualificationItem(810L, 89L, 81L, 9001L, 700L);
        qualificationB.setResult("FAIL");
        qualificationB.setComment("缺少证明");
        qualificationB.setVeto(true);
        qualificationB.setVetoReason("核心资格证明缺失");
        qualificationB.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        ItemRow scoringA = scoringItem(801L, 88L, 81L, 9002L, 701L, "4.00");
        scoringA.setComment("方案完整");
        ItemRow scoringB = scoringItem(811L, 89L, 81L, 9002L, 701L, "2.00");
        scoringB.setComment("实施细节不足");
        AttachmentRow attachment = attachment(901L, 810L, "现场照片.png", "image/png", "object-key");

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.listMembers(501L)).thenReturn(List.of(expertA, expertB));
        when(expertReviewStore.listCompanies(501L)).thenReturn(List.of(company));
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(qualificationA, scoringA));
        when(expertReviewStore.listItemsForMember(501L, 89L)).thenReturn(List.of(qualificationB, scoringB));
        when(expertReviewStore.listAttachmentsByItemIds(List.of(800L, 801L, 810L, 811L)))
                .thenReturn(Map.of(810L, List.of(attachment)));

        ExpertReviewAppService.ReviewSnapshot snapshot = service.getSnapshot(100L);

        assertEquals(2, snapshot.getItemReviewGroups().size());
        ExpertReviewAppService.ItemReviewGroup qualificationGroup = snapshot.getItemReviewGroups().get(0);
        assertEquals("QUALIFICATION", qualificationGroup.getItemType());
        assertNull(qualificationGroup.getAverageScore());
        assertEquals(2, qualificationGroup.getExpertReviews().size());
        assertEquals("PASS", qualificationGroup.getExpertReviews().get(0).getResult());
        assertEquals("资质齐全", qualificationGroup.getExpertReviews().get(0).getComment());
        assertEquals("FAIL", qualificationGroup.getExpertReviews().get(1).getResult());
        assertTrue(qualificationGroup.getExpertReviews().get(1).isVeto());
        assertEquals("核心资格证明缺失", qualificationGroup.getExpertReviews().get(1).getVetoReason());
        assertEquals(1, qualificationGroup.getExpertReviews().get(1).getAttachments().size());
        assertEquals("现场照片.png", qualificationGroup.getExpertReviews().get(1).getAttachments().get(0).getFileName());

        ExpertReviewAppService.ItemReviewGroup scoringGroup = snapshot.getItemReviewGroups().get(1);
        assertEquals("SCORING", scoringGroup.getItemType());
        assertEquals(new BigDecimal("3.00"), scoringGroup.getAverageScore());
        assertEquals(new BigDecimal("4.00"), scoringGroup.getExpertReviews().get(0).getScoreValue());
        assertEquals("方案完整", scoringGroup.getExpertReviews().get(0).getComment());
        assertEquals(new BigDecimal("2.00"), scoringGroup.getExpertReviews().get(1).getScoreValue());
    }

    @Test
    void getSnapshotTreatsOverdueSubmittedAsSubmittedButSkipsBlankScores() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        review.setHasAnyCompleteSubmission(true);
        review.setSubmittedExpertCount(2);
        MemberRow submitted = submittedMember(88L, 77L);
        MemberRow overdue = overdueSubmittedMember(89L, 78L);
        CompanyRow company = company(301L, 81L, "投标单位A");

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.listMembers(501L)).thenReturn(List.of(submitted, overdue));
        when(expertReviewStore.listCompanies(501L)).thenReturn(List.of(company));
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(
                scoringItem(801L, 81L, "9.00")
        ));
        when(expertReviewStore.listItemsForMember(501L, 89L)).thenReturn(List.of(
                overdueScoredItem(802L, 89L, 81L, "7.00"),
                overdueBlankScoringItem(803L, 89L, 81L)
        ));
        when(expertReviewStore.listAttachmentsByItemIds(List.of(801L, 802L, 803L))).thenReturn(Map.of());

        ExpertReviewAppService.ReviewSnapshot snapshot = service.getSnapshot(100L);

        assertTrue(snapshot.getExpertMembers().get(1).isSubmitted());
        assertEquals("OVERDUE_SUBMITTED", snapshot.getExpertMembers().get(1).getSubmitStatus());
        assertEquals(new BigDecimal("9.00"), snapshot.getCompanyTabs().get(0).getAverageScore());
        assertEquals(1, snapshot.getScoreCompareItems().get(0).getExpertScores().size());
        assertEquals(88L, snapshot.getScoreCompareItems().get(0).getExpertScores().get(0).getMemberId());
    }

    @Test
    void getSnapshotKeepsNullScoresForSubmittedVetoShortcutItems() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        review.setHasAnyCompleteSubmission(true);
        review.setSubmittedExpertCount(2);
        MemberRow expertA = submittedMember(88L, 77L);
        MemberRow expertB = submittedMember(89L, 78L);
        CompanyRow company = company(301L, 81L, "投标单位A");

        ItemRow qualificationA = qualificationItem(800L, 88L, 81L, 9001L, 700L);
        qualificationA.setResult("PASS");
        qualificationA.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        ItemRow scoringA = scoringItem(801L, 88L, 81L, 9002L, 701L, "9.00");

        ItemRow qualificationB = qualificationItem(810L, 89L, 81L, 9001L, 700L);
        qualificationB.setResult("FAIL");
        qualificationB.setVeto(true);
        qualificationB.setVetoReason("核心资格证明缺失");
        qualificationB.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        ItemRow blankScoringB = overdueBlankScoringItem(811L, 89L, 81L);

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.listMembers(501L)).thenReturn(List.of(expertA, expertB));
        when(expertReviewStore.listCompanies(501L)).thenReturn(List.of(company));
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(qualificationA, scoringA));
        when(expertReviewStore.listItemsForMember(501L, 89L)).thenReturn(List.of(qualificationB, blankScoringB));
        when(expertReviewStore.listAttachmentsByItemIds(List.of(800L, 801L, 810L, 811L))).thenReturn(Map.of());

        ExpertReviewAppService.ReviewSnapshot snapshot = service.getSnapshot(100L);

        assertEquals(new BigDecimal("9.00"), snapshot.getCompanyTabs().get(0).getAverageScore());
        assertEquals(1, snapshot.getScoreCompareItems().get(0).getExpertScores().size());
        assertEquals(88L, snapshot.getScoreCompareItems().get(0).getExpertScores().get(0).getMemberId());

        ExpertReviewAppService.ItemReviewGroup scoringGroup = snapshot.getItemReviewGroups().get(1);
        assertEquals("SCORING", scoringGroup.getItemType());
        assertEquals(new BigDecimal("9.00"), scoringGroup.getAverageScore());
        assertEquals(new BigDecimal("9.00"), scoringGroup.getExpertReviews().get(0).getScoreValue());
        assertNull(scoringGroup.getExpertReviews().get(1).getResult());
        assertNull(scoringGroup.getExpertReviews().get(1).getScoreValue());
        assertEquals("SUBMITTED", scoringGroup.getExpertReviews().get(1).getSubmitStatus());
    }

    @Test
    void saveItemPersistsScoringResult() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);
        ItemRow item = item();
        ItemRow saved = item();
        saved.setResult("SCORE");
        saved.setScoreValue(new BigDecimal("9.00"));
        saved.setComment("建议说明");

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.findItemByIdForMember(501L, 88L, 801L)).thenReturn(item, saved);
        when(expertReviewStore.listAttachments(801L)).thenReturn(List.of());
        when(expertReviewStore.updateItemResult(
                501L,
                88L,
                801L,
                "SCORE",
                new BigDecimal("9.00"),
                "建议说明",
                false,
                null
        )).thenReturn(1);
        ExpertReviewAppService.SaveItemCommand command = new ExpertReviewAppService.SaveItemCommand();
        command.setScoreValue(new BigDecimal("9.00"));
        command.setComment(" 建议说明 ");

        ExpertReviewAppService.TaskItem result = service.saveItem(88L, 801L, command);

        assertEquals("SCORE", result.getResult());
        assertEquals(new BigDecimal("9.00"), result.getScoreValue());
        assertEquals("建议说明", result.getComment());
        verify(expertReviewStore).updateItemResult(
                501L,
                88L,
                801L,
                "SCORE",
                new BigDecimal("9.00"),
                "建议说明",
                false,
                null
        );
    }

    @Test
    void uploadAttachmentStoresImageForEditableItem() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);
        ItemRow item = item();
        AttachmentRow saved = attachment(901L, 801L, "现场照片.png", "image/png", "object-key");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "现场照片.png",
                "image/png",
                new byte[] {1, 2, 3}
        );

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.findItemByIdForMember(501L, 88L, 801L)).thenReturn(item);
        when(expertReviewStore.countAttachments(801L)).thenReturn(0);
        when(expertReviewStore.insertAttachment(eq(801L), anyString(), eq("现场照片.png"), eq("image/png"), eq(1)))
                .thenReturn(901L);
        when(expertReviewStore.findAttachment(801L, 901L)).thenReturn(saved);

        ExpertReviewAppService.AttachmentSummary result = service.uploadAttachment(88L, 801L, file);

        assertEquals(901L, result.getAttachmentId());
        assertEquals("现场照片.png", result.getFileName());
        assertEquals("image/png", result.getContentType());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(objectStoragePort).put(keyCaptor.capture(), any(InputStream.class), eq(3L), eq("image/png"));
        assertTrue(keyCaptor.getValue().startsWith("org/2/expert-review/501/item/801/"));
    }

    @Test
    void uploadAttachmentRejectsNonImage() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "说明.pdf",
                "application/pdf",
                new byte[] {1, 2, 3}
        );

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.findItemByIdForMember(501L, 88L, 801L)).thenReturn(item());

        BizException exception = assertThrows(BizException.class, () -> service.uploadAttachment(88L, 801L, file));

        assertEquals(ErrorCode.BAD_REQUEST, exception.errorCode());
        verify(objectStoragePort, never()).put(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    void listAttachmentsAllowsSubmittedTask() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        review.setStatus("SUCCESS");
        MemberRow member = submittedMember(88L, 77L);

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.findItemByIdForMember(501L, 88L, 801L)).thenReturn(item());
        when(expertReviewStore.listAttachments(801L))
                .thenReturn(List.of(attachment(901L, 801L, "说明.png", "image/png", "object-key")));

        List<ExpertReviewAppService.AttachmentSummary> result = service.listAttachments(88L, 801L);

        assertEquals(1, result.size());
        assertEquals("说明.png", result.get(0).getFileName());
    }

    @Test
    void openAttachmentForPreviewReturnsInlineContent() throws Exception {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.findItemByIdForMember(501L, 88L, 801L)).thenReturn(item());
        when(expertReviewStore.findAttachment(801L, 901L))
                .thenReturn(attachment(901L, 801L, "说明.png", "image/png", "object-key"));
        when(objectStoragePort.open("object-key")).thenReturn(new ByteArrayInputStream(new byte[] {7, 8}));

        ExpertReviewAppService.AttachmentContent result = service.openAttachmentForPreview(88L, 801L, 901L);

        assertEquals("说明.png", result.getFileName());
        assertEquals("image/png", result.getContentType());
        Assertions.assertArrayEquals(new byte[] {7, 8}, result.getInputStream().readAllBytes());
    }

    @Test
    void openProjectAttachmentForPreviewAllowsTenderer() throws Exception {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findItemById(501L, 801L)).thenReturn(item());
        when(expertReviewStore.findAttachment(801L, 901L))
                .thenReturn(attachment(901L, 801L, "现场照片.png", "image/png", "object-key"));
        when(objectStoragePort.open("object-key")).thenReturn(new ByteArrayInputStream(new byte[] {5, 6}));

        ExpertReviewAppService.AttachmentContent result =
                service.openProjectAttachmentForPreview(100L, 801L, 901L);

        assertEquals("现场照片.png", result.getFileName());
        assertEquals("image/png", result.getContentType());
        Assertions.assertArrayEquals(new byte[] {5, 6}, result.getInputStream().readAllBytes());
    }

    @Test
    void deleteAttachmentRejectsSubmittedTask() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = submittedMember(88L, 77L);

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);

        assertThrows(BizException.class, () -> service.deleteAttachment(88L, 801L, 901L));

        verify(expertReviewStore, never()).deleteAttachment(anyLong(), anyLong());
        verify(objectStoragePort, never()).delete(anyString());
    }

    @Test
    void saveItemRejectsScoreGreaterThanFullScore() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.findItemByIdForMember(501L, 88L, 801L)).thenReturn(item());
        ExpertReviewAppService.SaveItemCommand command = new ExpertReviewAppService.SaveItemCommand();
        command.setScoreValue(new BigDecimal("10.01"));

        assertThrows(BizException.class, () -> service.saveItem(88L, 801L, command));
    }

    @Test
    void submitCompletesReviewAndDisablesTemporaryExperts() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        ReviewRow completed = runningReview(review.getDeadlineAt());
        completed.setStatus("SUCCESS");
        completed.setSubmittedExpertCount(2);
        MemberRow member = member(88L, 77L);
        ItemRow qualification = qualificationItem();
        qualification.setResult("PASS");
        ItemRow scoring = item();
        scoring.setResult("SCORE");
        scoring.setScoreValue(new BigDecimal("9.50"));

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review, completed);
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(qualification, scoring));
        when(expertReviewStore.markItemsSubmitted(eq(501L), eq(88L), any(Instant.class))).thenReturn(2);
        when(expertReviewStore.markMemberSubmitted(eq(88L), eq(77L), any(Instant.class))).thenReturn(1);
        when(expertReviewStore.refreshCompanyVetoStats(501L)).thenReturn(1);
        when(expertReviewStore.refreshSubmissionStats(501L)).thenReturn(1);
        when(expertReviewStore.listMembers(501L)).thenReturn(List.of(
                submittedMember(88L, 77L),
                submittedMember(89L, 78L)
        ));
        when(expertReviewStore.markSubmittedMembersDisabled(501L)).thenReturn(2);

        ExpertReviewAppService.SubmitResult result = service.submit(88L);

        assertEquals(88L, result.getTaskId());
        assertEquals("DISABLED", result.getMemberStatus());
        assertEquals("SUBMITTED", result.getSubmitStatus());
        assertEquals("SUCCESS", result.getReviewStatus());
        assertEquals(2, result.getSubmittedExpertCount());
        assertTrue(result.isReviewCompleted());
        assertNotNull(result.getSubmittedAt());
        verify(expertReviewStore).refreshCompanyVetoStats(501L);
        verify(expertAccountPort).disableTemporaryExpert(77L);
        verify(expertAccountPort).disableTemporaryExpert(78L);
        verify(expertReviewStore).markSubmittedMembersDisabled(501L);
    }

    @Test
    void submitKeepsCurrentMemberReadOnlyWhenReviewStillRunning() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        ReviewRow stillRunning = runningReview(review.getDeadlineAt());
        stillRunning.setSubmittedExpertCount(1);
        MemberRow member = member(88L, 77L);
        ItemRow qualification = qualificationItem();
        qualification.setResult("PASS");
        ItemRow scoring = item();
        scoring.setResult("SCORE");
        scoring.setScoreValue(new BigDecimal("9.50"));

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review, stillRunning);
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(qualification, scoring));
        when(expertReviewStore.markItemsSubmitted(eq(501L), eq(88L), any(Instant.class))).thenReturn(2);
        when(expertReviewStore.markMemberSubmitted(eq(88L), eq(77L), any(Instant.class))).thenReturn(1);
        when(expertReviewStore.refreshCompanyVetoStats(501L)).thenReturn(1);
        when(expertReviewStore.refreshSubmissionStats(501L)).thenReturn(1);

        ExpertReviewAppService.SubmitResult result = service.submit(88L);

        assertEquals("READ_ONLY", result.getMemberStatus());
        assertEquals("RUNNING", result.getReviewStatus());
        assertFalse(result.isReviewCompleted());
        verify(expertAccountPort, never()).disableTemporaryExpert(anyLong());
        verify(expertReviewStore, never()).markSubmittedMembersDisabled(501L);
    }

    @Test
    void submitAllowsSamePackageRemainingItemsBlankWhenQualificationVetoed() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        ReviewRow stillRunning = runningReview(review.getDeadlineAt());
        stillRunning.setSubmittedExpertCount(1);
        MemberRow member = member(88L, 77L);
        ItemRow vetoedQualification = qualificationItem();
        vetoedQualification.setResult("FAIL");
        vetoedQualification.setVeto(true);
        vetoedQualification.setVetoReason("核心资格证明缺失");
        ItemRow blankQualification = qualificationItem(802L, 88L, 81L, 9003L, 702L);
        ItemRow blankScoring = pendingScoringItem(803L, 88L, 81L);

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review, stillRunning);
        when(expertReviewStore.listItemsForMember(501L, 88L))
                .thenReturn(List.of(vetoedQualification, blankQualification, blankScoring));
        when(expertReviewStore.markItemsSubmitted(eq(501L), eq(88L), any(Instant.class))).thenReturn(3);
        when(expertReviewStore.markMemberSubmitted(eq(88L), eq(77L), any(Instant.class))).thenReturn(1);
        when(expertReviewStore.refreshCompanyVetoStats(501L)).thenReturn(1);
        when(expertReviewStore.refreshSubmissionStats(501L)).thenReturn(1);

        ExpertReviewAppService.SubmitResult result = service.submit(88L);

        assertEquals("READ_ONLY", result.getMemberStatus());
        assertEquals("RUNNING", result.getReviewStatus());
        assertFalse(result.isReviewCompleted());
        verify(expertReviewStore).markItemsSubmitted(eq(501L), eq(88L), any(Instant.class));
        verify(expertReviewStore).refreshCompanyVetoStats(501L);
    }

    @Test
    void submitRejectsOtherPackageBlankWhenDifferentPackageVetoed() {
        UserContext.set(new CurrentUser(77L, 2L, "expert_x", Set.of("EXPERT"), 0, false));
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);
        ItemRow vetoedQualification = qualificationItem();
        vetoedQualification.setResult("FAIL");
        vetoedQualification.setVeto(true);
        vetoedQualification.setVetoReason("核心资格证明缺失");
        ItemRow otherPackageBlankQualification = qualificationItem(802L, 88L, 82L, 9003L, 702L);
        ItemRow otherPackageBlankScoring = pendingScoringItem(803L, 88L, 82L);

        when(expertReviewStore.findMemberByIdAndUserId(88L, 77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(expertReviewStore.listItemsForMember(501L, 88L))
                .thenReturn(List.of(vetoedQualification, otherPackageBlankQualification, otherPackageBlankScoring));

        BizException ex = assertThrows(BizException.class, () -> service.submit(88L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        assertEquals("资格项尚未完成", ex.getMessage());
        verify(expertReviewStore, never()).markItemsSubmitted(eq(501L), eq(88L), any(Instant.class));
        verify(expertReviewStore, never()).markMemberSubmitted(eq(88L), eq(77L), any(Instant.class));
    }

    @Test
    void autoSubmitOneExpiredReviewMarksPendingMembersOverdueAndDisablesExperts() {
        ReviewRow expired = runningReview(Instant.parse("2026-01-01T00:00:00Z"));
        ReviewRow completed = runningReview(expired.getDeadlineAt());
        completed.setStatus("SUCCESS");
        completed.setSubmittedExpertCount(2);
        MemberRow submitted = submittedMember(88L, 77L);
        MemberRow pending = member(89L, 78L);

        when(expertReviewStore.findFirstExpiredRunningReview(any(Instant.class))).thenReturn(expired);
        when(expertReviewStore.listMembers(501L)).thenReturn(
                List.of(submitted, pending),
                List.of(submitted, overdueSubmittedMember(89L, 78L))
        );
        when(expertReviewStore.markPendingMembersOverdueSubmitted(eq(501L), any(Instant.class))).thenReturn(1);
        when(expertReviewStore.refreshCompanyVetoStats(501L)).thenReturn(1);
        when(expertReviewStore.refreshSubmissionStats(501L)).thenReturn(1);
        when(expertReviewStore.findById(501L)).thenReturn(completed);
        when(expertReviewStore.markSubmittedMembersDisabled(501L)).thenReturn(2);

        boolean handled = service.autoSubmitOneExpiredReview();

        assertTrue(handled);
        verify(expertReviewStore).markOpenItemsSubmittedForPendingMembers(eq(501L), any(Instant.class));
        verify(expertReviewStore).markPendingMembersOverdueSubmitted(eq(501L), any(Instant.class));
        verify(expertReviewStore).refreshCompanyVetoStats(501L);
        verify(expertReviewStore).refreshSubmissionStats(501L);
        verify(expertAccountPort).disableTemporaryExpert(77L);
        verify(expertAccountPort).disableTemporaryExpert(78L);
        verify(expertReviewStore).markSubmittedMembersDisabled(501L);
    }

    @Test
    void confirmCompanyVetoMarksCompanyVetoed() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        CompanyRow pending = pendingVetoCompany();
        CompanyRow vetoed = pendingVetoCompany();
        vetoed.setCompanyStatus("VETOED");
        vetoed.setDisplayMode("NORMAL");
        vetoed.setConfirmedBy(9L);
        vetoed.setConfirmedAt(Instant.parse("2026-08-18T02:00:00Z"));

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findCompanyById(501L, 301L)).thenReturn(pending, vetoed);
        when(expertReviewStore.updateCompanyVetoDecision(
                eq(501L),
                eq(301L),
                eq("VETOED"),
                eq("NORMAL"),
                eq(9L),
                any(Instant.class)
        )).thenReturn(1);
        when(expertReviewStore.countPendingVetoCompanies(501L)).thenReturn(0);

        ExpertReviewAppService.VetoDecisionResult result = service.confirmCompanyVeto(100L, 301L);

        assertEquals(501L, result.getReviewId());
        assertEquals(100L, result.getProjectId());
        assertEquals(301L, result.getCompanyId());
        assertEquals("VETOED", result.getCompanyStatus());
        assertEquals("NORMAL", result.getDisplayMode());
        assertEquals(1, result.getVetoItemCount());
        assertEquals("专家77", result.getVetoExpertNames());
        assertEquals("资格不符合", result.getVetoReasonSummary());
        assertFalse(result.isHasOtherPendingVeto());
    }

    @Test
    void continueDisplayCompanySuppressesVetoConfirmation() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        CompanyRow pending = pendingVetoCompany();
        CompanyRow continued = pendingVetoCompany();
        continued.setCompanyStatus("NORMAL");
        continued.setDisplayMode("CONTINUE_DISPLAY");
        continued.setConfirmedBy(9L);
        continued.setConfirmedAt(Instant.parse("2026-08-18T02:10:00Z"));

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findCompanyById(501L, 301L)).thenReturn(pending, continued);
        when(expertReviewStore.updateCompanyVetoDecision(
                eq(501L),
                eq(301L),
                eq("NORMAL"),
                eq("CONTINUE_DISPLAY"),
                eq(9L),
                any(Instant.class)
        )).thenReturn(1);
        when(expertReviewStore.countPendingVetoCompanies(501L)).thenReturn(1);

        ExpertReviewAppService.VetoDecisionResult result = service.continueDisplayCompany(100L, 301L);

        assertEquals("NORMAL", result.getCompanyStatus());
        assertEquals("CONTINUE_DISPLAY", result.getDisplayMode());
        assertTrue(result.isHasOtherPendingVeto());
    }

    @Test
    void confirmCompanyVetoRejectsCompanyWithoutPendingVeto() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findCompanyById(501L, 301L)).thenReturn(company());

        assertThrows(BizException.class, () -> service.confirmCompanyVeto(100L, 301L));
    }

    @Test
    void resetMemberPasswordReturnsTemporaryPasswordOnce() {
        ReviewRow review = runningReview(Instant.now().plusSeconds(86_400L));
        MemberRow member = member(88L, 77L);
        ExpertAccountPort.ResetPasswordResult reset = new ExpertAccountPort.ResetPasswordResult();
        reset.setUserId(77L);
        reset.setTemporaryPassword("NewTemp123!");

        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.findLatestByProject(2L, 100L)).thenReturn(review);
        when(expertReviewStore.findMemberById(501L, 88L)).thenReturn(member);
        when(expertAccountPort.resetTemporaryExpertPassword(77L)).thenReturn(reset);

        ExpertReviewAppService.MemberPasswordResetResult result =
                service.resetMemberPassword(100L, 88L);

        assertEquals(501L, result.getReviewId());
        assertEquals(100L, result.getProjectId());
        assertEquals(88L, result.getMemberId());
        assertEquals(77L, result.getUserId());
        assertEquals("expert_77", result.getUsername());
        assertEquals("专家77", result.getDisplayName());
        assertEquals("NewTemp123!", result.getTemporaryPassword());
        verify(expertAccountPort).resetTemporaryExpertPassword(77L);
    }

    private static ProjectLookupPort.ProjectRef project() {
        ProjectLookupPort.ProjectRef ref = new ProjectLookupPort.ProjectRef();
        ref.setId(100L);
        ref.setOrgId(2L);
        ref.setName("投标项目A");
        ref.setStatus("ACTIVE");
        return ref;
    }

    private static ReviewRow buildingReview() {
        ReviewRow row = new ReviewRow();
        row.setId(501L);
        row.setOrgId(2L);
        row.setProjectId(100L);
        row.setStatus("BUILDING");
        row.setExpertCount(0);
        return row;
    }

    private static ReviewRow runningReview(Instant deadlineAt) {
        ReviewRow row = buildingReview();
        row.setStatus("RUNNING");
        row.setDeadlineAt(deadlineAt);
        row.setExpertCount(2);
        row.setPushMessage("请评审");
        return row;
    }

    private static MemberRow member(long memberId, long userId) {
        MemberRow row = new MemberRow();
        row.setId(memberId);
        row.setReviewId(501L);
        row.setProjectId(100L);
        row.setUserId(userId);
        row.setUsername("expert_" + userId);
        row.setDisplayName("专家" + userId);
        row.setStatus("ACTIVE");
        row.setSubmitStatus("NOT_SUBMITTED");
        return row;
    }

    private static MemberRow submittedMember(long memberId, long userId) {
        MemberRow row = member(memberId, userId);
        row.setStatus("READ_ONLY");
        row.setSubmitStatus("SUBMITTED");
        row.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        return row;
    }

    private static MemberRow pendingAppendMember(long memberId, long userId) {
        MemberRow row = member(memberId, userId);
        row.setStatus("PENDING_APPEND");
        return row;
    }

    private static MemberRow overdueSubmittedMember(long memberId, long userId) {
        MemberRow row = member(memberId, userId);
        row.setStatus("READ_ONLY");
        row.setSubmitStatus("OVERDUE_SUBMITTED");
        row.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        return row;
    }

    private static ReviewItemSeed seed(long sourceReviewItemId,
                                       long requirementItemId,
                                       long bidPackageId,
                                       String itemType,
                                       String requirementCode) {
        ReviewItemSeed row = new ReviewItemSeed();
        row.setSourceReviewItemId(sourceReviewItemId);
        row.setSourceReviewId(601L);
        row.setRequirementItemId(requirementItemId);
        row.setBidPackageId(bidPackageId);
        row.setItemType(itemType);
        row.setRequirementCode(requirementCode);
        row.setTitle(requirementCode + " 标题");
        row.setContent(requirementCode + " 内容");
        row.setAiStatus("无法判断");
        row.setEvidenceJson("[]");
        row.setFullScore(new BigDecimal("10.00"));
        row.setSortOrder(1);
        return row;
    }

    private static CompanyRow company() {
        return company(301L, 81L, "投标包A");
    }

    private static CompanyRow company(long companyId, long bidPackageId, String supplierName) {
        CompanyRow row = new CompanyRow();
        row.setId(companyId);
        row.setReviewId(501L);
        row.setProjectId(100L);
        row.setBidPackageId(bidPackageId);
        row.setSupplierName(supplierName);
        row.setCompanyStatus("NORMAL");
        row.setDisplayMode("NORMAL");
        row.setVetoItemCount(0);
        return row;
    }

    private static CompanyRow pendingVetoCompany() {
        CompanyRow row = company();
        row.setCompanyStatus("PENDING_VETO");
        row.setDisplayMode("PENDING_CONFIRMATION");
        row.setVetoItemCount(1);
        row.setVetoReasonSummary("资格不符合");
        row.setVetoExpertNames("专家77");
        return row;
    }

    private static ItemRow item() {
        ItemRow row = new ItemRow();
        row.setId(801L);
        row.setReviewId(501L);
        row.setMemberId(88L);
        row.setSourceReviewItemId(701L);
        row.setProjectId(100L);
        row.setBidPackageId(81L);
        row.setRequirementItemId(9002L);
        row.setItemType("SCORING");
        row.setRequirementCode("S1");
        row.setTitle("评分项");
        row.setContent("评分内容");
        row.setAiStatus("DONE");
        row.setAiSuggestedScore(new BigDecimal("9.50"));
        row.setAiDeductionReason("参考扣分");
        row.setFullScore(new BigDecimal("10.00"));
        row.setEvidenceJson("[]");
        row.setSortOrder(2);
        return row;
    }

    private static ItemRow scoringItem(long itemId, long bidPackageId, String score) {
        return scoringItem(itemId, 88L, bidPackageId, 9002L, 701L, score);
    }

    private static ItemRow scoringItem(long itemId,
                                       long memberId,
                                       long bidPackageId,
                                       long requirementItemId,
                                       long sourceReviewItemId,
                                       String score) {
        ItemRow row = item();
        row.setId(itemId);
        row.setMemberId(memberId);
        row.setBidPackageId(bidPackageId);
        row.setRequirementItemId(requirementItemId);
        row.setSourceReviewItemId(sourceReviewItemId);
        row.setResult("SCORE");
        row.setScoreValue(new BigDecimal(score));
        row.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        return row;
    }

    private static ItemRow pendingScoringItem(long itemId, long memberId, long bidPackageId) {
        ItemRow row = item();
        row.setId(itemId);
        row.setMemberId(memberId);
        row.setBidPackageId(bidPackageId);
        row.setResult(null);
        row.setScoreValue(null);
        return row;
    }

    private static ItemRow overdueBlankScoringItem(long itemId, long memberId, long bidPackageId) {
        ItemRow row = item();
        row.setId(itemId);
        row.setMemberId(memberId);
        row.setBidPackageId(bidPackageId);
        row.setResult(null);
        row.setScoreValue(null);
        row.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        return row;
    }

    private static ItemRow overdueScoredItem(long itemId, long memberId, long bidPackageId, String score) {
        ItemRow row = item();
        row.setId(itemId);
        row.setMemberId(memberId);
        row.setBidPackageId(bidPackageId);
        row.setResult("SCORE");
        row.setScoreValue(new BigDecimal(score));
        row.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
        return row;
    }

    private static ItemRow qualificationItem() {
        return qualificationItem(800L, 88L, 81L, 9001L, 700L);
    }

    private static ItemRow qualificationItem(long itemId,
                                             long memberId,
                                             long bidPackageId,
                                             long requirementItemId,
                                             long sourceReviewItemId) {
        ItemRow row = new ItemRow();
        row.setId(itemId);
        row.setReviewId(501L);
        row.setMemberId(memberId);
        row.setSourceReviewItemId(sourceReviewItemId);
        row.setProjectId(100L);
        row.setBidPackageId(bidPackageId);
        row.setRequirementItemId(requirementItemId);
        row.setItemType("QUALIFICATION");
        row.setRequirementCode("Q1");
        row.setTitle("资格项");
        row.setContent("资格内容");
        row.setAiStatus("无法判断");
        row.setEvidenceJson("[]");
        row.setSortOrder(1);
        return row;
    }

    private static EvidenceChunkRef evidenceRef(long chunkId, long documentId, long packageId, String fileName) {
        EvidenceChunkRef ref = new EvidenceChunkRef();
        ref.setChunkId(chunkId);
        ref.setDocumentId(documentId);
        ref.setPackageId(packageId);
        ref.setFileName(fileName);
        return ref;
    }

    private static AttachmentRow attachment(long attachmentId,
                                            long itemId,
                                            String fileName,
                                            String contentType,
                                            String objectKey) {
        AttachmentRow row = new AttachmentRow();
        row.setId(attachmentId);
        row.setItemId(itemId);
        row.setFileName(fileName);
        row.setContentType(contentType);
        row.setObjectKey(objectKey);
        row.setSortOrder(1);
        row.setCreatedAt(Instant.parse("2026-08-18T03:00:00Z"));
        return row;
    }
}
