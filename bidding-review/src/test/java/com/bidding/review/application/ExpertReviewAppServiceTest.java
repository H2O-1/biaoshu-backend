package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.port.ExpertAccountPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.CompanyRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ItemRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.MemberRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewItemSeed;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    private ExpertReviewAppService service;

    @BeforeEach
    void setUp() {
        service = new ExpertReviewAppService(
                projectLookupPort,
                projectReviewProgressPort,
                packageLookupPort,
                expertAccountPort,
                expertReviewStore
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
        when(expertReviewStore.insertMember(501L, 100L, 77L, "expert_x", "张三", "hash-1"))
                .thenReturn(88L);
        when(expertReviewStore.countMembers(501L)).thenReturn(1);
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
        when(expertReviewStore.updatePushInfo(501L, Instant.parse("2026-08-20T09:00:00Z"), "请评审", 2))
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
        MemberRow member = member(88L, 77L);
        CompanyRow company = company();
        ItemRow item = item();

        when(expertReviewStore.findCurrentMemberByUserId(77L)).thenReturn(member);
        when(expertReviewStore.findById(501L)).thenReturn(review);
        when(projectLookupPort.findActiveById(100L)).thenReturn(project());
        when(expertReviewStore.listItemsForMember(501L, 88L)).thenReturn(List.of(item));
        when(expertReviewStore.listCompanies(501L)).thenReturn(List.of(company));

        ExpertReviewAppService.CurrentTaskDetail detail = service.getCurrentTask();

        assertEquals(88L, detail.getTaskId());
        assertEquals(501L, detail.getReviewId());
        assertEquals("投标项目A", detail.getProjectName());
        assertEquals("RUNNING", detail.getReviewStatus());
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

        ExpertReviewAppService.ReviewSnapshot snapshot = service.getSnapshot(100L);

        assertTrue(snapshot.getExpertMembers().get(1).isSubmitted());
        assertEquals("OVERDUE_SUBMITTED", snapshot.getExpertMembers().get(1).getSubmitStatus());
        assertEquals(new BigDecimal("9.00"), snapshot.getCompanyTabs().get(0).getAverageScore());
        assertEquals(1, snapshot.getScoreCompareItems().get(0).getExpertScores().size());
        assertEquals(88L, snapshot.getScoreCompareItems().get(0).getExpertScores().get(0).getMemberId());
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
        return row;
    }

    private static ItemRow scoringItem(long itemId, long bidPackageId, String score) {
        ItemRow row = item();
        row.setId(itemId);
        row.setBidPackageId(bidPackageId);
        row.setResult("SCORE");
        row.setScoreValue(new BigDecimal(score));
        row.setSubmittedAt(Instant.parse("2026-08-18T02:00:00Z"));
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
        ItemRow row = new ItemRow();
        row.setId(800L);
        row.setReviewId(501L);
        row.setMemberId(88L);
        row.setSourceReviewItemId(700L);
        row.setProjectId(100L);
        row.setBidPackageId(81L);
        row.setRequirementItemId(9001L);
        row.setItemType("QUALIFICATION");
        row.setRequirementCode("Q1");
        row.setTitle("资格项");
        row.setContent("资格内容");
        row.setAiStatus("无法判断");
        row.setEvidenceJson("[]");
        return row;
    }
}
