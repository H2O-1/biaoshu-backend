package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ExpertAccountPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.bidding.common.security.UserContext;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.CompanyRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ItemRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.MemberRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewItemSeed;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目专家评审：添加专家、推送、追加。
 */
@Service
public class ExpertReviewAppService {

    private static final String PACKAGE_TYPE_BID = "BID";
    private static final String STATUS_BUILDING = "BUILDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String MEMBER_STATUS_ACTIVE = "ACTIVE";
    private static final String MEMBER_STATUS_READ_ONLY = "READ_ONLY";
    private static final String MEMBER_STATUS_DISABLED = "DISABLED";
    private static final String SUBMIT_STATUS_NOT_SUBMITTED = "NOT_SUBMITTED";
    private static final String SUBMIT_STATUS_SUBMITTED = "SUBMITTED";
    private static final String SUBMIT_STATUS_OVERDUE_SUBMITTED = "OVERDUE_SUBMITTED";
    private static final String COMPANY_STATUS_NORMAL = "NORMAL";
    private static final String COMPANY_STATUS_PENDING_VETO = "PENDING_VETO";
    private static final String COMPANY_STATUS_VETOED = "VETOED";
    private static final String DISPLAY_MODE_NORMAL = "NORMAL";
    private static final String DISPLAY_MODE_PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    private static final String DISPLAY_MODE_CONTINUE_DISPLAY = "CONTINUE_DISPLAY";
    private static final String ROLE_EXPERT = "EXPERT";
    private static final String RESULT_SCORE = "SCORE";

    private final ProjectLookupPort projectLookupPort;
    private final ProjectReviewProgressPort projectReviewProgressPort;
    private final PackageLookupPort packageLookupPort;
    private final ExpertAccountPort expertAccountPort;
    private final JdbcExpertReviewStore expertReviewStore;

    public ExpertReviewAppService(ProjectLookupPort projectLookupPort,
                                  ProjectReviewProgressPort projectReviewProgressPort,
                                  PackageLookupPort packageLookupPort,
                                  ExpertAccountPort expertAccountPort,
                                  JdbcExpertReviewStore expertReviewStore) {
        this.projectLookupPort = projectLookupPort;
        this.projectReviewProgressPort = projectReviewProgressPort;
        this.packageLookupPort = packageLookupPort;
        this.expertAccountPort = expertAccountPort;
        this.expertReviewStore = expertReviewStore;
    }

    @Transactional
    public MemberResult addMember(long projectId, String displayName) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可推送专家评审");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        requireAiReviewSuccess(projectId);
        ReviewRow review = requireOrCreateBuildingReview(project);
        if (STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "专家评审已开始，请使用追加专家");
        }
        return createMember(review, project, displayName, false);
    }

    @Transactional
    public PushResult push(long projectId, Instant deadlineAt, String pushMessage) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可推送专家评审");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        requireAiReviewSuccess(projectId);

        ReviewRow review = expertReviewStore.findLatestByProject(operator.getOrgId(), projectId);
        if (review == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先添加专家后再推送");
        }
        if (!STATUS_BUILDING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审已推送");
        }

        int expertCount = expertReviewStore.countMembers(review.getId());
        if (expertCount <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先添加专家后再推送");
        }
        List<PackageLookupPort.PackageRef> bidPackages =
                packageLookupPort.listActiveByProjectIdAndType(projectId, PACKAGE_TYPE_BID);
        if (bidPackages == null || bidPackages.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目下没有投标包，无法推送专家评审");
        }

        List<Long> bidPackageIds = toPackageIds(bidPackages);
        List<ReviewItemSeed> seeds = expertReviewStore.findLatestAiReviewSeeds(
                operator.getOrgId(), projectId, bidPackageIds);
        if (seeds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前 AI 审查没有需要专家评审的条目");
        }
        List<MemberRow> members = expertReviewStore.listMembers(review.getId());
        if (members.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先添加专家后再推送");
        }

        expertReviewStore.insertCompanies(review.getId(), projectId, bidPackages);
        expertReviewStore.insertReviewItemsForMembers(
                review.getId(), projectId, members, seeds);
        int updated = expertReviewStore.updatePushInfo(
                review.getId(),
                deadlineAt,
                normalizePushMessage(pushMessage),
                expertCount
        );
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审状态已变化，请刷新后重试");
        }

        PushResult result = new PushResult();
        result.setReviewId(review.getId());
        result.setProjectId(project.getId());
        result.setStatus(STATUS_RUNNING);
        result.setDeadlineAt(deadlineAt);
        result.setPushMessage(normalizePushMessage(pushMessage));
        result.setExpertCount(expertCount);
        result.setProjectName(project.getName());
        result.setReviewItemCount(seeds.size());
        return result;
    }

    @Transactional
    public MemberResult append(long projectId, String displayName) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可追加专家");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        requireAiReviewSuccess(projectId);
        ReviewRow review = expertReviewStore.findLatestByProject(operator.getOrgId(), projectId);
        if (review == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先推送专家评审");
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "当前项目未处于专家评审中");
        }
        MemberResult result = createMember(review, project, displayName, true);
        List<ReviewItemSeed> seeds = expertReviewStore.listSeedTemplates(review.getId());
        if (seeds.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审待评项尚未生成，请刷新后重试");
        }
        expertReviewStore.insertReviewItemsForMembers(
                review.getId(), projectId, List.of(toMemberRow(result, projectId)), seeds);
        return result;
    }

    @Transactional(readOnly = true)
    public ReviewSnapshot getSnapshot(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可查看专家评审");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        ReviewRow review = expertReviewStore.findLatestByProject(operator.getOrgId(), projectId);
        if (review == null) {
            return null;
        }

        List<MemberRow> members = expertReviewStore.listMembers(review.getId());
        List<CompanyRow> companies = expertReviewStore.listCompanies(review.getId());
        Map<Long, List<MemberScore>> memberScoresByPackage = collectSubmittedMemberScores(review.getId(), members);
        Map<Long, BigDecimal> averageScores = calculateAverageScores(memberScoresByPackage);
        Map<Long, Integer> ranks = calculateRanks(companies, averageScores, review.isHasAnyCompleteSubmission());

        ReviewSnapshot snapshot = new ReviewSnapshot();
        snapshot.setReviewId(review.getId());
        snapshot.setProjectId(review.getProjectId());
        snapshot.setProjectName(project.getName());
        snapshot.setProjectStatus(project.getStatus());
        snapshot.setStatus(review.getStatus());
        snapshot.setDeadlineAt(review.getDeadlineAt());
        snapshot.setPushMessage(review.getPushMessage());
        snapshot.setExpertCount(review.getExpertCount());
        snapshot.setSubmittedExpertCount(review.getSubmittedExpertCount());
        snapshot.setSubmittedCompanyCount(review.getSubmittedCompanyCount());
        snapshot.setHasAnyCompleteSubmission(review.isHasAnyCompleteSubmission());
        snapshot.setLocked(review.isLocked());

        List<ExpertMemberSummary> memberResults = new ArrayList<>();
        for (MemberRow member : members) {
            memberResults.add(toExpertMemberSummary(review.getId(), member));
        }
        snapshot.setExpertMembers(memberResults);

        List<CompanyTab> companyTabs = new ArrayList<>();
        List<ScoreCompareItem> scoreCompareItems = new ArrayList<>();
        List<VetoPendingItem> vetoPendingItems = new ArrayList<>();
        for (CompanyRow company : companies) {
            BigDecimal averageScore = visibleAverageScore(company, averageScores, review.isHasAnyCompleteSubmission());
            companyTabs.add(toCompanyTab(company, averageScore, ranks.get(company.getBidPackageId())));
            scoreCompareItems.add(toScoreCompareItem(
                    company,
                    averageScore,
                    ranks.get(company.getBidPackageId()),
                    memberScoresByPackage.getOrDefault(company.getBidPackageId(), List.of())
            ));
            if (isPendingVeto(company)) {
                vetoPendingItems.add(toVetoPendingItem(company));
            }
        }
        snapshot.setCompanyTabs(companyTabs);
        snapshot.setScoreCompareItems(scoreCompareItems);
        snapshot.setAverageRanking(toAverageRanking(companyTabs));
        snapshot.setVetoPendingItems(vetoPendingItems);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public CurrentTaskDetail getCurrentTask() {
        CurrentUser expert = requireExpert();
        MemberRow member = expertReviewStore.findCurrentMemberByUserId(expert.getUserId());
        if (member == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "当前没有专家评审任务");
        }
        ReviewRow review = expertReviewStore.findById(member.getReviewId());
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审任务不存在");
        }
        ProjectLookupPort.ProjectRef project = projectLookupPort.findActiveById(member.getProjectId());
        if (project == null || project.getOrgId() != expert.getOrgId()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        CurrentTaskDetail detail = new CurrentTaskDetail();
        detail.setTaskId(member.getId());
        detail.setMemberId(member.getId());
        detail.setReviewId(review.getId());
        detail.setProjectId(project.getId());
        detail.setProjectName(project.getName());
        detail.setReviewStatus(review.getStatus());
        detail.setMemberStatus(member.getStatus());
        detail.setSubmitStatus(member.getSubmitStatus());
        detail.setDeadlineAt(review.getDeadlineAt());
        detail.setRemainingDays(remainingDays(review.getDeadlineAt()));
        detail.setReadOnly(isReadOnly(review, member));
        detail.setSubmittedAt(member.getSubmittedAt());
        detail.setPushMessage(review.getPushMessage());

        List<ItemRow> items = expertReviewStore.listItemsForMember(review.getId(), member.getId());
        Map<Long, List<TaskItem>> itemsByPackage = groupItemsByPackage(items);
        List<CompanyRow> companies = expertReviewStore.listCompanies(review.getId());
        List<TaskCompany> companyResults = new ArrayList<>();
        for (CompanyRow company : companies) {
            TaskCompany taskCompany = toTaskCompany(company);
            taskCompany.setItems(itemsByPackage.getOrDefault(company.getBidPackageId(), List.of()));
            companyResults.add(taskCompany);
        }
        detail.setCompanies(companyResults);
        return detail;
    }

    @Transactional
    public TaskItem saveItem(long taskId, long itemId, SaveItemCommand command) {
        CurrentUser expert = requireExpert();
        MemberRow member = requireTaskMember(taskId, expert);
        ReviewRow review = requireEditableReview(member);
        ItemRow item = requireTaskItem(review.getId(), member.getId(), itemId);
        ItemUpdate update = normalizeItemUpdate(item, command);

        int updated = expertReviewStore.updateItemResult(
                review.getId(),
                member.getId(),
                item.getId(),
                update.result,
                update.scoreValue,
                update.comment,
                update.veto,
                update.vetoReason
        );
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审条目状态已变化，请刷新后重试");
        }
        return toTaskItem(requireTaskItem(review.getId(), member.getId(), itemId));
    }

    @Transactional
    public SubmitResult submit(long taskId) {
        CurrentUser expert = requireExpert();
        MemberRow member = requireTaskMember(taskId, expert);
        ReviewRow review = requireEditableReview(member);
        List<ItemRow> items = expertReviewStore.listItemsForMember(review.getId(), member.getId());
        validateBeforeSubmit(items);

        Instant submittedAt = Instant.now();
        int submittedItems = expertReviewStore.markItemsSubmitted(review.getId(), member.getId(), submittedAt);
        if (submittedItems != items.size()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审条目状态已变化，请刷新后重试");
        }
        int submittedMember = expertReviewStore.markMemberSubmitted(member.getId(), expert.getUserId(), submittedAt);
        if (submittedMember != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审任务状态已变化，请刷新后重试");
        }
        int refreshedCompanies = expertReviewStore.refreshCompanyVetoStats(review.getId());
        if (refreshedCompanies <= 0) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审公司状态缺失");
        }
        expertReviewStore.refreshSubmissionStats(review.getId());
        ReviewRow refreshed = expertReviewStore.findById(review.getId());
        if (refreshed == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审任务不存在");
        }
        boolean reviewCompleted = STATUS_SUCCESS.equalsIgnoreCase(refreshed.getStatus());
        if (reviewCompleted) {
            cleanupCompletedReview(refreshed.getId());
        }

        SubmitResult result = new SubmitResult();
        result.setTaskId(member.getId());
        result.setMemberId(member.getId());
        result.setReviewId(review.getId());
        result.setProjectId(member.getProjectId());
        result.setReviewStatus(refreshed.getStatus());
        result.setMemberStatus(reviewCompleted ? MEMBER_STATUS_DISABLED : MEMBER_STATUS_READ_ONLY);
        result.setSubmitStatus(SUBMIT_STATUS_SUBMITTED);
        result.setSubmittedAt(submittedAt);
        result.setSubmittedExpertCount(refreshed.getSubmittedExpertCount());
        result.setReviewCompleted(reviewCompleted);
        return result;
    }

    @Transactional
    public VetoDecisionResult confirmCompanyVeto(long projectId, long companyId) {
        return decideCompanyVeto(projectId, companyId, COMPANY_STATUS_VETOED, DISPLAY_MODE_NORMAL);
    }

    @Transactional
    public VetoDecisionResult continueDisplayCompany(long projectId, long companyId) {
        return decideCompanyVeto(projectId, companyId, COMPANY_STATUS_NORMAL, DISPLAY_MODE_CONTINUE_DISPLAY);
    }

    @Transactional
    public MemberPasswordResetResult resetMemberPassword(long projectId, long memberId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可重置专家密码");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        ReviewRow review = expertReviewStore.findLatestByProject(project.getOrgId(), project.getId());
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审不存在");
        }
        MemberRow member = expertReviewStore.findMemberById(review.getId(), memberId);
        if (member == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家成员不存在");
        }
        if (member.getProjectId() != project.getId()) {
            throw new BizException(ErrorCode.CONFLICT, "专家成员项目不匹配");
        }
        ExpertAccountPort.ResetPasswordResult reset =
                expertAccountPort.resetTemporaryExpertPassword(member.getUserId());

        MemberPasswordResetResult result = new MemberPasswordResetResult();
        result.setReviewId(review.getId());
        result.setProjectId(project.getId());
        result.setMemberId(member.getId());
        result.setUserId(reset.getUserId());
        result.setUsername(member.getUsername());
        result.setDisplayName(member.getDisplayName());
        result.setTemporaryPassword(reset.getTemporaryPassword());
        return result;
    }

    @Transactional
    public boolean autoSubmitOneExpiredReview() {
        Instant submittedAt = Instant.now();
        ReviewRow review = expertReviewStore.findFirstExpiredRunningReview(submittedAt);
        if (review == null) {
            return false;
        }
        autoSubmitExpiredReview(review, submittedAt);
        return true;
    }

    private MemberResult createMember(ReviewRow review, ProjectLookupPort.ProjectRef project,
                                      String displayName, boolean appended) {
        String safeDisplayName = normalizeDisplayName(displayName);
        if (expertReviewStore.existsMemberDisplayName(review.getId(), safeDisplayName)) {
            throw new BizException(ErrorCode.CONFLICT, "该专家已添加");
        }

        ExpertAccountPort.CreateResult account =
                expertAccountPort.createTemporaryExpert(project.getOrgId(), safeDisplayName, requireCurrentUser().getUserId());
        long memberId = expertReviewStore.insertMember(
                review.getId(),
                project.getId(),
                account.getUserId(),
                account.getUsername(),
                account.getDisplayName(),
                account.getPasswordHash()
        );
        int memberCount = expertReviewStore.countMembers(review.getId());
        expertReviewStore.updateExpertCount(review.getId(), memberCount);

        MemberResult result = new MemberResult();
        result.setReviewId(review.getId());
        result.setMemberId(memberId);
        result.setUserId(account.getUserId());
        result.setUsername(account.getUsername());
        result.setDisplayName(account.getDisplayName());
        result.setTemporaryPassword(account.getTemporaryPassword());
        result.setAppended(appended);
        return result;
    }

    private List<Long> toPackageIds(List<PackageLookupPort.PackageRef> packages) {
        List<Long> result = new ArrayList<>();
        if (packages == null) {
            return result;
        }
        for (PackageLookupPort.PackageRef pkg : packages) {
            if (pkg != null) {
                result.add(pkg.getId());
            }
        }
        return result;
    }

    private MemberRow toMemberRow(MemberResult result, long projectId) {
        MemberRow row = new MemberRow();
        row.setId(result.getMemberId());
        row.setReviewId(result.getReviewId());
        row.setProjectId(projectId);
        row.setUserId(result.getUserId());
        row.setUsername(result.getUsername());
        row.setDisplayName(result.getDisplayName());
        row.setStatus(MEMBER_STATUS_ACTIVE);
        row.setSubmitStatus(SUBMIT_STATUS_NOT_SUBMITTED);
        return row;
    }

    private MemberRow requireTaskMember(long taskId, CurrentUser expert) {
        MemberRow member = expertReviewStore.findMemberByIdAndUserId(taskId, expert.getUserId());
        if (member == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审任务不存在");
        }
        if (member.getProjectId() <= 0) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审任务项目缺失");
        }
        return member;
    }

    private void autoSubmitExpiredReview(ReviewRow review, Instant submittedAt) {
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审未处于进行中，不能自动提交");
        }
        if (review.getDeadlineAt() == null || review.getDeadlineAt().isAfter(submittedAt)) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审尚未截止，不能自动提交");
        }

        List<MemberRow> members = expertReviewStore.listMembers(review.getId());
        int overdueMemberCount = countOverdueMembers(members);
        expertReviewStore.markOpenItemsSubmittedForPendingMembers(review.getId(), submittedAt);
        int overdueMembers = expertReviewStore.markPendingMembersOverdueSubmitted(review.getId(), submittedAt);
        if (overdueMembers != overdueMemberCount) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审成员逾期提交状态已变化，请刷新后重试");
        }
        int refreshedCompanies = expertReviewStore.refreshCompanyVetoStats(review.getId());
        if (refreshedCompanies <= 0) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审公司状态缺失");
        }
        expertReviewStore.refreshSubmissionStats(review.getId());
        ReviewRow refreshed = expertReviewStore.findById(review.getId());
        if (refreshed == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审任务不存在");
        }
        if (!STATUS_SUCCESS.equalsIgnoreCase(refreshed.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审逾期收口失败");
        }
        cleanupCompletedReview(refreshed.getId());
    }

    private int countOverdueMembers(List<MemberRow> members) {
        if (members.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审成员缺失，不能自动提交");
        }
        int count = 0;
        for (MemberRow member : members) {
            if (MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())
                    && SUBMIT_STATUS_NOT_SUBMITTED.equalsIgnoreCase(member.getSubmitStatus())) {
                count++;
                continue;
            }
            if (MEMBER_STATUS_READ_ONLY.equalsIgnoreCase(member.getStatus())
                    && isCompletedSubmitStatus(member.getSubmitStatus())) {
                continue;
            }
            throw new BizException(ErrorCode.CONFLICT, "专家评审成员状态不支持自动提交");
        }
        return count;
    }

    private void cleanupCompletedReview(long reviewId) {
        List<MemberRow> members = expertReviewStore.listMembers(reviewId);
        if (members.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审成员缺失，不能清理");
        }
        for (MemberRow member : members) {
            if (!MEMBER_STATUS_READ_ONLY.equalsIgnoreCase(member.getStatus())
                    || !isCompletedSubmitStatus(member.getSubmitStatus())) {
                throw new BizException(ErrorCode.CONFLICT, "专家评审成员提交状态不完整，不能清理");
            }
            expertAccountPort.disableTemporaryExpert(member.getUserId());
        }
        int disabled = expertReviewStore.markSubmittedMembersDisabled(reviewId);
        if (disabled != members.size()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审成员清理状态已变化，请刷新后重试");
        }
    }

    private ReviewRow requireEditableReview(MemberRow member) {
        ReviewRow review = expertReviewStore.findById(member.getReviewId());
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审任务不存在");
        }
        requireEditable(review, member);
        return review;
    }

    private ItemRow requireTaskItem(long reviewId, long memberId, long itemId) {
        ItemRow item = expertReviewStore.findItemByIdForMember(reviewId, memberId, itemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审条目不存在");
        }
        return item;
    }

    private VetoDecisionResult decideCompanyVeto(long projectId,
                                                 long companyId,
                                                 String targetCompanyStatus,
                                                 String targetDisplayMode) {
        CurrentUser operator = requireCurrentUser();
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        ReviewRow review = requireVetoDecisionReview(project);
        requirePendingVetoCompany(review.getId(), companyId);

        Instant confirmedAt = Instant.now();
        int updated = expertReviewStore.updateCompanyVetoDecision(
                review.getId(),
                companyId,
                targetCompanyStatus,
                targetDisplayMode,
                operator.getUserId(),
                confirmedAt
        );
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "废标状态已变化，请刷新后重试");
        }
        CompanyRow refreshed = expertReviewStore.findCompanyById(review.getId(), companyId);
        if (refreshed == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审公司不存在");
        }
        return toVetoDecisionResult(review, refreshed, expertReviewStore.countPendingVetoCompanies(review.getId()));
    }

    private ReviewRow requireVetoDecisionReview(ProjectLookupPort.ProjectRef project) {
        ReviewRow review = expertReviewStore.findLatestByProject(project.getOrgId(), project.getId());
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审不存在");
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())
                && !STATUS_SUCCESS.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审未进入可处理状态");
        }
        return review;
    }

    private CompanyRow requirePendingVetoCompany(long reviewId, long companyId) {
        CompanyRow company = expertReviewStore.findCompanyById(reviewId, companyId);
        if (company == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审公司不存在");
        }
        if (!COMPANY_STATUS_PENDING_VETO.equalsIgnoreCase(company.getCompanyStatus())
                || !DISPLAY_MODE_PENDING_CONFIRMATION.equalsIgnoreCase(company.getDisplayMode())) {
            throw new BizException(ErrorCode.CONFLICT, "当前公司没有待确认废标");
        }
        return company;
    }

    private void requireEditable(ReviewRow review, MemberRow member) {
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审未处于进行中");
        }
        if (!MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审任务已只读");
        }
        if (!SUBMIT_STATUS_NOT_SUBMITTED.equalsIgnoreCase(member.getSubmitStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审已提交，不能修改");
        }
        Instant deadlineAt = review.getDeadlineAt();
        if (deadlineAt != null && !deadlineAt.isAfter(Instant.now())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审已截止，不能修改");
        }
    }

    private ItemUpdate normalizeItemUpdate(ItemRow item, SaveItemCommand command) {
        if (command == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体不能为空");
        }
        if (ReviewPort.MAIN_QUALIFICATION.equals(item.getItemType())) {
            return normalizeQualificationUpdate(command);
        }
        if (ReviewPort.MAIN_SCORING.equals(item.getItemType())) {
            return normalizeScoringUpdate(item, command);
        }
        throw new BizException(ErrorCode.CONFLICT, "不支持的专家评审条目类型");
    }

    private ItemUpdate normalizeQualificationUpdate(SaveItemCommand command) {
        String result = normalizeResult(command.getResult());
        if (!ReviewPort.VERDICT_PASS.equals(result) && !ReviewPort.VERDICT_FAIL.equals(result)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资格项结果仅支持 PASS / FAIL");
        }
        if (command.getScoreValue() != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资格项不能填写评分");
        }
        String comment = normalizeText(command.getComment());
        String vetoReason = normalizeText(command.getVetoReason());
        if (command.isVeto() && !ReviewPort.VERDICT_FAIL.equals(result)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有资格项不合格时才能建议废标");
        }
        if (command.isVeto() && vetoReason == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "建议废标时必须填写废标原因");
        }
        return new ItemUpdate(result, null, comment, command.isVeto(), command.isVeto() ? vetoReason : null);
    }

    private ItemUpdate normalizeScoringUpdate(ItemRow item, SaveItemCommand command) {
        String result = normalizeText(command.getResult());
        if (result != null && !RESULT_SCORE.equals(result.toUpperCase())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项结果仅支持 SCORE 或留空");
        }
        if (command.getScoreValue() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项分值不能为空");
        }
        if (command.getScoreValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项分值不能小于 0");
        }
        if (item.getFullScore() == null) {
            throw new BizException(ErrorCode.CONFLICT, "评分项满分缺失");
        }
        if (command.getScoreValue().compareTo(item.getFullScore()) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项分值不能超过满分");
        }
        if (command.isVeto() || normalizeText(command.getVetoReason()) != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项不支持废标建议");
        }
        return new ItemUpdate(
                RESULT_SCORE,
                command.getScoreValue(),
                normalizeText(command.getComment()),
                false,
                null
        );
    }

    private void validateBeforeSubmit(List<ItemRow> items) {
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审待评项不存在");
        }
        for (ItemRow item : items) {
            if (ReviewPort.MAIN_QUALIFICATION.equals(item.getItemType())) {
                validateQualificationBeforeSubmit(item);
            } else if (ReviewPort.MAIN_SCORING.equals(item.getItemType())) {
                validateScoringBeforeSubmit(item);
            } else {
                throw new BizException(ErrorCode.CONFLICT, "不支持的专家评审条目类型");
            }
        }
    }

    private void validateQualificationBeforeSubmit(ItemRow item) {
        String result = item.getResult();
        if (!ReviewPort.VERDICT_PASS.equals(result) && !ReviewPort.VERDICT_FAIL.equals(result)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "资格项尚未完成");
        }
        if (item.isVeto() && !ReviewPort.VERDICT_FAIL.equals(result)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有资格项不合格时才能建议废标");
        }
        if (item.isVeto() && normalizeText(item.getVetoReason()) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "建议废标时必须填写废标原因");
        }
    }

    private void validateScoringBeforeSubmit(ItemRow item) {
        if (!RESULT_SCORE.equals(item.getResult())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项尚未完成");
        }
        if (item.getScoreValue() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项尚未评分");
        }
        if (item.getScoreValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项分值不能小于 0");
        }
        if (item.getFullScore() == null) {
            throw new BizException(ErrorCode.CONFLICT, "评分项满分缺失");
        }
        if (item.getScoreValue().compareTo(item.getFullScore()) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评分项分值不能超过满分");
        }
    }

    private ProjectLookupPort.ProjectRef requireProject(long projectId, long orgId) {
        ProjectLookupPort.ProjectRef project = projectLookupPort.findActiveById(projectId);
        if (project == null || project.getOrgId() != orgId) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    private void requireAiReviewSuccess(long projectId) {
        String latest = projectReviewProgressPort.findLatestReviewStatus(projectId);
        if (!STATUS_SUCCESS.equalsIgnoreCase(latest)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目尚未完成 AI 审查");
        }
    }

    private ReviewRow requireOrCreateBuildingReview(ProjectLookupPort.ProjectRef project) {
        ReviewRow review = expertReviewStore.findLatestByProject(project.getOrgId(), project.getId());
        if (review != null) {
            if (STATUS_SUCCESS.equalsIgnoreCase(review.getStatus())) {
                throw new BizException(ErrorCode.CONFLICT, "项目专家评审已完成");
            }
            return review;
        }
        long reviewId = expertReviewStore.createBuilding(project.getOrgId(), project.getId(), requireCurrentUser().getUserId());
        ReviewRow created = expertReviewStore.findLatestByProject(project.getOrgId(), project.getId());
        if (created == null || created.getId() != reviewId) {
            throw new BizException(ErrorCode.INTERNAL, "创建专家评审主记录失败");
        }
        return created;
    }

    private CurrentUser requireCurrentUser() {
        CurrentUser user = TendererAuth.requireTenderer();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    private CurrentUser requireExpert() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.hasRole(ROLE_EXPERT)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅专家可查看专家评审任务");
        }
        return user;
    }

    private boolean isReadOnly(ReviewRow review, MemberRow member) {
        if (review == null || member == null) {
            return true;
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            return true;
        }
        if (!MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())) {
            return true;
        }
        if (!SUBMIT_STATUS_NOT_SUBMITTED.equalsIgnoreCase(member.getSubmitStatus())) {
            return true;
        }
        Instant deadlineAt = review.getDeadlineAt();
        return deadlineAt != null && !deadlineAt.isAfter(Instant.now());
    }

    private boolean isCompletedSubmitStatus(String submitStatus) {
        return SUBMIT_STATUS_SUBMITTED.equalsIgnoreCase(submitStatus)
                || SUBMIT_STATUS_OVERDUE_SUBMITTED.equalsIgnoreCase(submitStatus);
    }

    private Long remainingDays(Instant deadlineAt) {
        if (deadlineAt == null) {
            return null;
        }
        long seconds = Duration.between(Instant.now(), deadlineAt).getSeconds();
        if (seconds <= 0) {
            return 0L;
        }
        return (seconds + 86_399L) / 86_400L;
    }

    private Map<Long, List<MemberScore>> collectSubmittedMemberScores(long reviewId, List<MemberRow> members) {
        Map<Long, List<MemberScore>> result = new LinkedHashMap<>();
        if (members == null || members.isEmpty()) {
            return result;
        }
        for (MemberRow member : members) {
            if (!isCompletedSubmitStatus(member.getSubmitStatus())) {
                continue;
            }
            boolean overdueSubmitted = SUBMIT_STATUS_OVERDUE_SUBMITTED.equalsIgnoreCase(member.getSubmitStatus());
            Map<Long, BigDecimal> totalsByPackage = new HashMap<>();
            List<Long> incompletePackages = new ArrayList<>();
            List<ItemRow> items = expertReviewStore.listItemsForMember(reviewId, member.getId());
            for (ItemRow item : items) {
                if (!ReviewPort.MAIN_SCORING.equals(item.getItemType())) {
                    continue;
                }
                if (item.getSubmittedAt() == null) {
                    throw new BizException(ErrorCode.CONFLICT, "已提交评分项状态缺失");
                }
                if (item.getScoreValue() == null && overdueSubmitted) {
                    incompletePackages.add(item.getBidPackageId());
                    totalsByPackage.remove(item.getBidPackageId());
                    continue;
                }
                if (item.getScoreValue() == null) {
                    throw new BizException(ErrorCode.CONFLICT, "已提交评分项分值缺失");
                }
                if (incompletePackages.contains(item.getBidPackageId())) {
                    continue;
                }
                totalsByPackage.merge(item.getBidPackageId(), item.getScoreValue(), BigDecimal::add);
            }
            for (Map.Entry<Long, BigDecimal> entry : totalsByPackage.entrySet()) {
                MemberScore score = new MemberScore(
                        member.getId(),
                        member.getUsername(),
                        member.getDisplayName(),
                        entry.getValue().setScale(2, RoundingMode.HALF_UP)
                );
                result.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(score);
            }
        }
        return result;
    }

    private Map<Long, BigDecimal> calculateAverageScores(Map<Long, List<MemberScore>> memberScoresByPackage) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Map.Entry<Long, List<MemberScore>> entry : memberScoresByPackage.entrySet()) {
            BigDecimal total = BigDecimal.ZERO;
            for (MemberScore score : entry.getValue()) {
                total = total.add(score.score);
            }
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), total.divide(
                        BigDecimal.valueOf(entry.getValue().size()), 2, RoundingMode.HALF_UP));
            }
        }
        return result;
    }

    private Map<Long, Integer> calculateRanks(List<CompanyRow> companies,
                                              Map<Long, BigDecimal> averageScores,
                                              boolean hasAnyCompleteSubmission) {
        Map<Long, Integer> result = new HashMap<>();
        if (!hasAnyCompleteSubmission) {
            return result;
        }
        List<CompanyScoreRank> rankItems = new ArrayList<>();
        for (CompanyRow company : companies) {
            BigDecimal averageScore = visibleAverageScore(company, averageScores, true);
            if (averageScore != null) {
                rankItems.add(new CompanyScoreRank(company.getBidPackageId(), company.getSupplierName(), averageScore));
            }
        }
        rankItems.sort(Comparator
                .comparing(CompanyScoreRank::getAverageScore).reversed()
                .thenComparing(CompanyScoreRank::getSupplierName));
        BigDecimal previousScore = null;
        int currentRank = 0;
        for (int i = 0; i < rankItems.size(); i++) {
            CompanyScoreRank item = rankItems.get(i);
            if (previousScore == null || item.getAverageScore().compareTo(previousScore) != 0) {
                currentRank = i + 1;
                previousScore = item.getAverageScore();
            }
            result.put(item.getBidPackageId(), currentRank);
        }
        return result;
    }

    private BigDecimal visibleAverageScore(CompanyRow company,
                                           Map<Long, BigDecimal> averageScores,
                                           boolean hasAnyCompleteSubmission) {
        if (!hasAnyCompleteSubmission || COMPANY_STATUS_VETOED.equalsIgnoreCase(company.getCompanyStatus())) {
            return null;
        }
        return averageScores.get(company.getBidPackageId());
    }

    private boolean isPendingVeto(CompanyRow company) {
        return COMPANY_STATUS_PENDING_VETO.equalsIgnoreCase(company.getCompanyStatus())
                && DISPLAY_MODE_PENDING_CONFIRMATION.equalsIgnoreCase(company.getDisplayMode());
    }

    private Map<Long, List<TaskItem>> groupItemsByPackage(List<ItemRow> rows) {
        Map<Long, List<TaskItem>> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (ItemRow row : rows) {
            result.computeIfAbsent(row.getBidPackageId(), ignored -> new ArrayList<>())
                    .add(toTaskItem(row));
        }
        return result;
    }

    private TaskCompany toTaskCompany(CompanyRow row) {
        TaskCompany result = new TaskCompany();
        result.setCompanyId(row.getId());
        result.setBidPackageId(row.getBidPackageId());
        result.setSupplierName(row.getSupplierName());
        result.setCompanyStatus(row.getCompanyStatus());
        result.setDisplayMode(row.getDisplayMode());
        result.setVetoItemCount(row.getVetoItemCount());
        result.setAverageScore(row.getAverageScore());
        return result;
    }

    private ExpertMemberSummary toExpertMemberSummary(long reviewId, MemberRow member) {
        ExpertMemberSummary result = new ExpertMemberSummary();
        result.setReviewId(reviewId);
        result.setMemberId(member.getId());
        result.setUserId(member.getUserId());
        result.setUsername(member.getUsername());
        result.setDisplayName(member.getDisplayName());
        result.setStatus(member.getStatus());
        result.setSubmitStatus(member.getSubmitStatus());
        result.setSubmittedAt(member.getSubmittedAt());
        result.setSubmitted(isCompletedSubmitStatus(member.getSubmitStatus()));
        return result;
    }

    private CompanyTab toCompanyTab(CompanyRow company, BigDecimal averageScore, Integer rank) {
        CompanyTab result = new CompanyTab();
        result.setCompanyId(company.getId());
        result.setBidPackageId(company.getBidPackageId());
        result.setSupplierName(company.getSupplierName());
        result.setCompanyStatus(company.getCompanyStatus());
        result.setDisplayMode(company.getDisplayMode());
        result.setVetoItemCount(company.getVetoItemCount());
        result.setVetoReasonSummary(company.getVetoReasonSummary());
        result.setVetoExpertNames(company.getVetoExpertNames());
        result.setAverageScore(averageScore);
        result.setRank(rank);
        result.setConfirmedBy(company.getConfirmedBy());
        result.setConfirmedAt(company.getConfirmedAt());
        return result;
    }

    private ScoreCompareItem toScoreCompareItem(CompanyRow company,
                                                BigDecimal averageScore,
                                                Integer rank,
                                                List<MemberScore> memberScores) {
        ScoreCompareItem result = new ScoreCompareItem();
        result.setCompanyId(company.getId());
        result.setBidPackageId(company.getBidPackageId());
        result.setSupplierName(company.getSupplierName());
        result.setCompanyStatus(company.getCompanyStatus());
        result.setDisplayMode(company.getDisplayMode());
        result.setAverageScore(averageScore);
        result.setRank(rank);
        if (!COMPANY_STATUS_VETOED.equalsIgnoreCase(company.getCompanyStatus())) {
            List<ExpertScore> expertScores = new ArrayList<>();
            for (MemberScore memberScore : memberScores) {
                expertScores.add(toExpertScore(memberScore));
            }
            result.setExpertScores(expertScores);
        }
        return result;
    }

    private ExpertScore toExpertScore(MemberScore memberScore) {
        ExpertScore result = new ExpertScore();
        result.setMemberId(memberScore.memberId);
        result.setUsername(memberScore.username);
        result.setDisplayName(memberScore.displayName);
        result.setScore(memberScore.score);
        return result;
    }

    private List<AverageRankingItem> toAverageRanking(List<CompanyTab> companyTabs) {
        List<AverageRankingItem> result = new ArrayList<>();
        for (CompanyTab tab : companyTabs) {
            if (tab.getAverageScore() == null || tab.getRank() == null) {
                continue;
            }
            AverageRankingItem item = new AverageRankingItem();
            item.setRank(tab.getRank());
            item.setCompanyId(tab.getCompanyId());
            item.setBidPackageId(tab.getBidPackageId());
            item.setSupplierName(tab.getSupplierName());
            item.setCompanyStatus(tab.getCompanyStatus());
            item.setDisplayMode(tab.getDisplayMode());
            item.setAverageScore(tab.getAverageScore());
            result.add(item);
        }
        result.sort(Comparator
                .comparing(AverageRankingItem::getRank)
                .thenComparing(AverageRankingItem::getSupplierName));
        return result;
    }

    private VetoPendingItem toVetoPendingItem(CompanyRow company) {
        VetoPendingItem result = new VetoPendingItem();
        result.setCompanyId(company.getId());
        result.setBidPackageId(company.getBidPackageId());
        result.setSupplierName(company.getSupplierName());
        result.setVetoItemCount(company.getVetoItemCount());
        result.setVetoReasonSummary(company.getVetoReasonSummary());
        result.setVetoExpertNames(company.getVetoExpertNames());
        return result;
    }

    private VetoDecisionResult toVetoDecisionResult(ReviewRow review, CompanyRow company, int pendingVetoCount) {
        VetoDecisionResult result = new VetoDecisionResult();
        result.setReviewId(review.getId());
        result.setProjectId(review.getProjectId());
        result.setCompanyId(company.getId());
        result.setBidPackageId(company.getBidPackageId());
        result.setSupplierName(company.getSupplierName());
        result.setCompanyStatus(company.getCompanyStatus());
        result.setDisplayMode(company.getDisplayMode());
        result.setVetoItemCount(company.getVetoItemCount());
        result.setVetoReasonSummary(company.getVetoReasonSummary());
        result.setVetoExpertNames(company.getVetoExpertNames());
        result.setConfirmedBy(company.getConfirmedBy());
        result.setConfirmedAt(company.getConfirmedAt());
        result.setHasOtherPendingVeto(pendingVetoCount > 0);
        return result;
    }

    private TaskItem toTaskItem(ItemRow row) {
        TaskItem result = new TaskItem();
        result.setItemId(row.getId());
        result.setSourceReviewItemId(row.getSourceReviewItemId());
        result.setRequirementItemId(row.getRequirementItemId());
        result.setBidPackageId(row.getBidPackageId());
        result.setItemType(row.getItemType());
        result.setRequirementCode(row.getRequirementCode());
        result.setTitle(row.getTitle());
        result.setContent(row.getContent());
        result.setAiStatus(row.getAiStatus());
        result.setAiVetoRisk(row.getAiVetoRisk());
        result.setAiJudgement(row.getAiJudgement());
        result.setAiSuggestedScore(row.getAiSuggestedScore());
        result.setAiDeductionReason(row.getAiDeductionReason());
        result.setFullScore(row.getFullScore());
        result.setEvidenceJson(row.getEvidenceJson());
        result.setResult(row.getResult());
        result.setScoreValue(row.getScoreValue());
        result.setComment(row.getComment());
        result.setVeto(row.isVeto());
        result.setVetoReason(row.getVetoReason());
        result.setSubmittedAt(row.getSubmittedAt());
        return result;
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "专家姓名不能为空");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "专家姓名过长");
        }
        return trimmed;
    }

    private String normalizePushMessage(String pushMessage) {
        if (pushMessage == null) {
            return null;
        }
        String trimmed = pushMessage.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeResult(String result) {
        String normalized = normalizeText(result);
        if (normalized == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评审结果不能为空");
        }
        return normalized.toUpperCase();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private MemberResult toMemberResult(long reviewId, MemberRow member, boolean appended) {
        MemberResult result = new MemberResult();
        result.setReviewId(reviewId);
        result.setMemberId(member.getId());
        result.setUserId(member.getUserId());
        result.setUsername(member.getUsername());
        result.setDisplayName(member.getDisplayName());
        result.setAppended(appended);
        return result;
    }

    private static class ItemUpdate {
        private final String result;
        private final BigDecimal scoreValue;
        private final String comment;
        private final boolean veto;
        private final String vetoReason;

        private ItemUpdate(String result,
                           BigDecimal scoreValue,
                           String comment,
                           boolean veto,
                           String vetoReason) {
            this.result = result;
            this.scoreValue = scoreValue;
            this.comment = comment;
            this.veto = veto;
            this.vetoReason = vetoReason;
        }
    }

    private static class MemberScore {
        private final long memberId;
        private final String username;
        private final String displayName;
        private final BigDecimal score;

        private MemberScore(long memberId, String username, String displayName, BigDecimal score) {
            this.memberId = memberId;
            this.username = username;
            this.displayName = displayName;
            this.score = score;
        }
    }

    private static class CompanyScoreRank {
        private final long bidPackageId;
        private final String supplierName;
        private final BigDecimal averageScore;

        private CompanyScoreRank(long bidPackageId, String supplierName, BigDecimal averageScore) {
            this.bidPackageId = bidPackageId;
            this.supplierName = supplierName;
            this.averageScore = averageScore;
        }

        private long getBidPackageId() {
            return bidPackageId;
        }

        private String getSupplierName() {
            return supplierName;
        }

        private BigDecimal getAverageScore() {
            return averageScore;
        }
    }

    public static class SaveItemCommand {
        private String result;
        private BigDecimal scoreValue;
        private String comment;
        private boolean veto;
        private String vetoReason;

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public BigDecimal getScoreValue() {
            return scoreValue;
        }

        public void setScoreValue(BigDecimal scoreValue) {
            this.scoreValue = scoreValue;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public boolean isVeto() {
            return veto;
        }

        public void setVeto(boolean veto) {
            this.veto = veto;
        }

        public String getVetoReason() {
            return vetoReason;
        }

        public void setVetoReason(String vetoReason) {
            this.vetoReason = vetoReason;
        }
    }

    public static class SubmitResult {
        private long taskId;
        private long memberId;
        private long reviewId;
        private long projectId;
        private String reviewStatus;
        private String memberStatus;
        private String submitStatus;
        private Instant submittedAt;
        private int submittedExpertCount;
        private boolean reviewCompleted;

        public long getTaskId() {
            return taskId;
        }

        public void setTaskId(long taskId) {
            this.taskId = taskId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getMemberStatus() {
            return memberStatus;
        }

        public void setMemberStatus(String memberStatus) {
            this.memberStatus = memberStatus;
        }

        public String getSubmitStatus() {
            return submitStatus;
        }

        public void setSubmitStatus(String submitStatus) {
            this.submitStatus = submitStatus;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }

        public int getSubmittedExpertCount() {
            return submittedExpertCount;
        }

        public void setSubmittedExpertCount(int submittedExpertCount) {
            this.submittedExpertCount = submittedExpertCount;
        }

        public boolean isReviewCompleted() {
            return reviewCompleted;
        }

        public void setReviewCompleted(boolean reviewCompleted) {
            this.reviewCompleted = reviewCompleted;
        }
    }

    public static class VetoDecisionResult {
        private long reviewId;
        private long projectId;
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private String companyStatus;
        private String displayMode;
        private int vetoItemCount;
        private String vetoReasonSummary;
        private String vetoExpertNames;
        private Long confirmedBy;
        private Instant confirmedAt;
        private boolean hasOtherPendingVeto;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getCompanyId() {
            return companyId;
        }

        public void setCompanyId(long companyId) {
            this.companyId = companyId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getCompanyStatus() {
            return companyStatus;
        }

        public void setCompanyStatus(String companyStatus) {
            this.companyStatus = companyStatus;
        }

        public String getDisplayMode() {
            return displayMode;
        }

        public void setDisplayMode(String displayMode) {
            this.displayMode = displayMode;
        }

        public int getVetoItemCount() {
            return vetoItemCount;
        }

        public void setVetoItemCount(int vetoItemCount) {
            this.vetoItemCount = vetoItemCount;
        }

        public String getVetoReasonSummary() {
            return vetoReasonSummary;
        }

        public void setVetoReasonSummary(String vetoReasonSummary) {
            this.vetoReasonSummary = vetoReasonSummary;
        }

        public String getVetoExpertNames() {
            return vetoExpertNames;
        }

        public void setVetoExpertNames(String vetoExpertNames) {
            this.vetoExpertNames = vetoExpertNames;
        }

        public Long getConfirmedBy() {
            return confirmedBy;
        }

        public void setConfirmedBy(Long confirmedBy) {
            this.confirmedBy = confirmedBy;
        }

        public Instant getConfirmedAt() {
            return confirmedAt;
        }

        public void setConfirmedAt(Instant confirmedAt) {
            this.confirmedAt = confirmedAt;
        }

        public boolean isHasOtherPendingVeto() {
            return hasOtherPendingVeto;
        }

        public void setHasOtherPendingVeto(boolean hasOtherPendingVeto) {
            this.hasOtherPendingVeto = hasOtherPendingVeto;
        }
    }

    public static class MemberPasswordResetResult {
        private long reviewId;
        private long projectId;
        private long memberId;
        private long userId;
        private String username;
        private String displayName;
        private String temporaryPassword;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }
    }

    public static class MemberResult {
        private long reviewId;
        private long memberId;
        private long userId;
        private String username;
        private String displayName;
        private String temporaryPassword;
        private boolean appended;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }

        public boolean isAppended() {
            return appended;
        }

        public void setAppended(boolean appended) {
            this.appended = appended;
        }
    }

    public static class PushResult {
        private long reviewId;
        private long projectId;
        private String projectName;
        private String status;
        private Instant deadlineAt;
        private String pushMessage;
        private int expertCount;
        private int reviewItemCount;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(Instant deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public String getPushMessage() {
            return pushMessage;
        }

        public void setPushMessage(String pushMessage) {
            this.pushMessage = pushMessage;
        }

        public int getExpertCount() {
            return expertCount;
        }

        public void setExpertCount(int expertCount) {
            this.expertCount = expertCount;
        }

        public int getReviewItemCount() {
            return reviewItemCount;
        }

        public void setReviewItemCount(int reviewItemCount) {
            this.reviewItemCount = reviewItemCount;
        }
    }

    public static class ReviewSnapshot {
        private long reviewId;
        private long projectId;
        private String projectName;
        private String projectStatus;
        private String status;
        private Instant deadlineAt;
        private String pushMessage;
        private int expertCount;
        private int submittedExpertCount;
        private int submittedCompanyCount;
        private boolean hasAnyCompleteSubmission;
        private boolean locked;
        private List<ExpertMemberSummary> expertMembers = new ArrayList<>();
        private List<CompanyTab> companyTabs = new ArrayList<>();
        private List<ScoreCompareItem> scoreCompareItems = new ArrayList<>();
        private List<AverageRankingItem> averageRanking = new ArrayList<>();
        private List<VetoPendingItem> vetoPendingItems = new ArrayList<>();

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getProjectStatus() {
            return projectStatus;
        }

        public void setProjectStatus(String projectStatus) {
            this.projectStatus = projectStatus;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(Instant deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public String getPushMessage() {
            return pushMessage;
        }

        public void setPushMessage(String pushMessage) {
            this.pushMessage = pushMessage;
        }

        public int getExpertCount() {
            return expertCount;
        }

        public void setExpertCount(int expertCount) {
            this.expertCount = expertCount;
        }

        public int getSubmittedExpertCount() {
            return submittedExpertCount;
        }

        public void setSubmittedExpertCount(int submittedExpertCount) {
            this.submittedExpertCount = submittedExpertCount;
        }

        public int getSubmittedCompanyCount() {
            return submittedCompanyCount;
        }

        public void setSubmittedCompanyCount(int submittedCompanyCount) {
            this.submittedCompanyCount = submittedCompanyCount;
        }

        public boolean isHasAnyCompleteSubmission() {
            return hasAnyCompleteSubmission;
        }

        public void setHasAnyCompleteSubmission(boolean hasAnyCompleteSubmission) {
            this.hasAnyCompleteSubmission = hasAnyCompleteSubmission;
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }

        public List<ExpertMemberSummary> getExpertMembers() {
            return expertMembers;
        }

        public void setExpertMembers(List<ExpertMemberSummary> expertMembers) {
            this.expertMembers = expertMembers == null ? new ArrayList<>() : expertMembers;
        }

        public List<CompanyTab> getCompanyTabs() {
            return companyTabs;
        }

        public void setCompanyTabs(List<CompanyTab> companyTabs) {
            this.companyTabs = companyTabs == null ? new ArrayList<>() : companyTabs;
        }

        public List<ScoreCompareItem> getScoreCompareItems() {
            return scoreCompareItems;
        }

        public void setScoreCompareItems(List<ScoreCompareItem> scoreCompareItems) {
            this.scoreCompareItems = scoreCompareItems == null ? new ArrayList<>() : scoreCompareItems;
        }

        public List<AverageRankingItem> getAverageRanking() {
            return averageRanking;
        }

        public void setAverageRanking(List<AverageRankingItem> averageRanking) {
            this.averageRanking = averageRanking == null ? new ArrayList<>() : averageRanking;
        }

        public List<VetoPendingItem> getVetoPendingItems() {
            return vetoPendingItems;
        }

        public void setVetoPendingItems(List<VetoPendingItem> vetoPendingItems) {
            this.vetoPendingItems = vetoPendingItems == null ? new ArrayList<>() : vetoPendingItems;
        }
    }

    public static class ExpertMemberSummary {
        private long reviewId;
        private long memberId;
        private long userId;
        private String username;
        private String displayName;
        private String status;
        private String submitStatus;
        private Instant submittedAt;
        private boolean submitted;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getSubmitStatus() {
            return submitStatus;
        }

        public void setSubmitStatus(String submitStatus) {
            this.submitStatus = submitStatus;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }

        public boolean isSubmitted() {
            return submitted;
        }

        public void setSubmitted(boolean submitted) {
            this.submitted = submitted;
        }
    }

    public static class CompanyTab {
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private String companyStatus;
        private String displayMode;
        private int vetoItemCount;
        private String vetoReasonSummary;
        private String vetoExpertNames;
        private BigDecimal averageScore;
        private Integer rank;
        private Long confirmedBy;
        private Instant confirmedAt;

        public long getCompanyId() {
            return companyId;
        }

        public void setCompanyId(long companyId) {
            this.companyId = companyId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getCompanyStatus() {
            return companyStatus;
        }

        public void setCompanyStatus(String companyStatus) {
            this.companyStatus = companyStatus;
        }

        public String getDisplayMode() {
            return displayMode;
        }

        public void setDisplayMode(String displayMode) {
            this.displayMode = displayMode;
        }

        public int getVetoItemCount() {
            return vetoItemCount;
        }

        public void setVetoItemCount(int vetoItemCount) {
            this.vetoItemCount = vetoItemCount;
        }

        public String getVetoReasonSummary() {
            return vetoReasonSummary;
        }

        public void setVetoReasonSummary(String vetoReasonSummary) {
            this.vetoReasonSummary = vetoReasonSummary;
        }

        public String getVetoExpertNames() {
            return vetoExpertNames;
        }

        public void setVetoExpertNames(String vetoExpertNames) {
            this.vetoExpertNames = vetoExpertNames;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public Integer getRank() {
            return rank;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }

        public Long getConfirmedBy() {
            return confirmedBy;
        }

        public void setConfirmedBy(Long confirmedBy) {
            this.confirmedBy = confirmedBy;
        }

        public Instant getConfirmedAt() {
            return confirmedAt;
        }

        public void setConfirmedAt(Instant confirmedAt) {
            this.confirmedAt = confirmedAt;
        }
    }

    public static class ScoreCompareItem {
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private String companyStatus;
        private String displayMode;
        private BigDecimal averageScore;
        private Integer rank;
        private List<ExpertScore> expertScores = new ArrayList<>();

        public long getCompanyId() {
            return companyId;
        }

        public void setCompanyId(long companyId) {
            this.companyId = companyId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getCompanyStatus() {
            return companyStatus;
        }

        public void setCompanyStatus(String companyStatus) {
            this.companyStatus = companyStatus;
        }

        public String getDisplayMode() {
            return displayMode;
        }

        public void setDisplayMode(String displayMode) {
            this.displayMode = displayMode;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public Integer getRank() {
            return rank;
        }

        public void setRank(Integer rank) {
            this.rank = rank;
        }

        public List<ExpertScore> getExpertScores() {
            return expertScores;
        }

        public void setExpertScores(List<ExpertScore> expertScores) {
            this.expertScores = expertScores == null ? new ArrayList<>() : expertScores;
        }
    }

    public static class ExpertScore {
        private long memberId;
        private String username;
        private String displayName;
        private BigDecimal score;

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public BigDecimal getScore() {
            return score;
        }

        public void setScore(BigDecimal score) {
            this.score = score;
        }
    }

    public static class AverageRankingItem {
        private int rank;
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private String companyStatus;
        private String displayMode;
        private BigDecimal averageScore;

        public int getRank() {
            return rank;
        }

        public void setRank(int rank) {
            this.rank = rank;
        }

        public long getCompanyId() {
            return companyId;
        }

        public void setCompanyId(long companyId) {
            this.companyId = companyId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getCompanyStatus() {
            return companyStatus;
        }

        public void setCompanyStatus(String companyStatus) {
            this.companyStatus = companyStatus;
        }

        public String getDisplayMode() {
            return displayMode;
        }

        public void setDisplayMode(String displayMode) {
            this.displayMode = displayMode;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }
    }

    public static class VetoPendingItem {
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private int vetoItemCount;
        private String vetoReasonSummary;
        private String vetoExpertNames;

        public long getCompanyId() {
            return companyId;
        }

        public void setCompanyId(long companyId) {
            this.companyId = companyId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public int getVetoItemCount() {
            return vetoItemCount;
        }

        public void setVetoItemCount(int vetoItemCount) {
            this.vetoItemCount = vetoItemCount;
        }

        public String getVetoReasonSummary() {
            return vetoReasonSummary;
        }

        public void setVetoReasonSummary(String vetoReasonSummary) {
            this.vetoReasonSummary = vetoReasonSummary;
        }

        public String getVetoExpertNames() {
            return vetoExpertNames;
        }

        public void setVetoExpertNames(String vetoExpertNames) {
            this.vetoExpertNames = vetoExpertNames;
        }
    }

    public static class CurrentTaskDetail {
        private long taskId;
        private long memberId;
        private long reviewId;
        private long projectId;
        private String projectName;
        private String reviewStatus;
        private String memberStatus;
        private String submitStatus;
        private Instant deadlineAt;
        private Long remainingDays;
        private boolean readOnly;
        private Instant submittedAt;
        private String pushMessage;
        private List<TaskCompany> companies = new ArrayList<>();

        public long getTaskId() {
            return taskId;
        }

        public void setTaskId(long taskId) {
            this.taskId = taskId;
        }

        public long getMemberId() {
            return memberId;
        }

        public void setMemberId(long memberId) {
            this.memberId = memberId;
        }

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public void setProjectName(String projectName) {
            this.projectName = projectName;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getMemberStatus() {
            return memberStatus;
        }

        public void setMemberStatus(String memberStatus) {
            this.memberStatus = memberStatus;
        }

        public String getSubmitStatus() {
            return submitStatus;
        }

        public void setSubmitStatus(String submitStatus) {
            this.submitStatus = submitStatus;
        }

        public Instant getDeadlineAt() {
            return deadlineAt;
        }

        public void setDeadlineAt(Instant deadlineAt) {
            this.deadlineAt = deadlineAt;
        }

        public Long getRemainingDays() {
            return remainingDays;
        }

        public void setRemainingDays(Long remainingDays) {
            this.remainingDays = remainingDays;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setReadOnly(boolean readOnly) {
            this.readOnly = readOnly;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }

        public String getPushMessage() {
            return pushMessage;
        }

        public void setPushMessage(String pushMessage) {
            this.pushMessage = pushMessage;
        }

        public List<TaskCompany> getCompanies() {
            return companies;
        }

        public void setCompanies(List<TaskCompany> companies) {
            this.companies = companies == null ? new ArrayList<>() : companies;
        }
    }

    public static class TaskCompany {
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private String companyStatus;
        private String displayMode;
        private int vetoItemCount;
        private BigDecimal averageScore;
        private List<TaskItem> items = new ArrayList<>();

        public long getCompanyId() {
            return companyId;
        }

        public void setCompanyId(long companyId) {
            this.companyId = companyId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getSupplierName() {
            return supplierName;
        }

        public void setSupplierName(String supplierName) {
            this.supplierName = supplierName;
        }

        public String getCompanyStatus() {
            return companyStatus;
        }

        public void setCompanyStatus(String companyStatus) {
            this.companyStatus = companyStatus;
        }

        public String getDisplayMode() {
            return displayMode;
        }

        public void setDisplayMode(String displayMode) {
            this.displayMode = displayMode;
        }

        public int getVetoItemCount() {
            return vetoItemCount;
        }

        public void setVetoItemCount(int vetoItemCount) {
            this.vetoItemCount = vetoItemCount;
        }

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public List<TaskItem> getItems() {
            return items;
        }

        public void setItems(List<TaskItem> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }
    }

    public static class TaskItem {
        private long itemId;
        private long sourceReviewItemId;
        private long requirementItemId;
        private long bidPackageId;
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String aiStatus;
        private Boolean aiVetoRisk;
        private String aiJudgement;
        private BigDecimal aiSuggestedScore;
        private String aiDeductionReason;
        private BigDecimal fullScore;
        private String evidenceJson;
        private String result;
        private BigDecimal scoreValue;
        private String comment;
        private boolean veto;
        private String vetoReason;
        private Instant submittedAt;

        public long getItemId() {
            return itemId;
        }

        public void setItemId(long itemId) {
            this.itemId = itemId;
        }

        public long getSourceReviewItemId() {
            return sourceReviewItemId;
        }

        public void setSourceReviewItemId(long sourceReviewItemId) {
            this.sourceReviewItemId = sourceReviewItemId;
        }

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getItemType() {
            return itemType;
        }

        public void setItemType(String itemType) {
            this.itemType = itemType;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getAiStatus() {
            return aiStatus;
        }

        public void setAiStatus(String aiStatus) {
            this.aiStatus = aiStatus;
        }

        public Boolean getAiVetoRisk() {
            return aiVetoRisk;
        }

        public void setAiVetoRisk(Boolean aiVetoRisk) {
            this.aiVetoRisk = aiVetoRisk;
        }

        public String getAiJudgement() {
            return aiJudgement;
        }

        public void setAiJudgement(String aiJudgement) {
            this.aiJudgement = aiJudgement;
        }

        public BigDecimal getAiSuggestedScore() {
            return aiSuggestedScore;
        }

        public void setAiSuggestedScore(BigDecimal aiSuggestedScore) {
            this.aiSuggestedScore = aiSuggestedScore;
        }

        public String getAiDeductionReason() {
            return aiDeductionReason;
        }

        public void setAiDeductionReason(String aiDeductionReason) {
            this.aiDeductionReason = aiDeductionReason;
        }

        public BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public BigDecimal getScoreValue() {
            return scoreValue;
        }

        public void setScoreValue(BigDecimal scoreValue) {
            this.scoreValue = scoreValue;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }

        public boolean isVeto() {
            return veto;
        }

        public void setVeto(boolean veto) {
            this.veto = veto;
        }

        public String getVetoReason() {
            return vetoReason;
        }

        public void setVetoReason(String vetoReason) {
            this.vetoReason = vetoReason;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }
    }
}
