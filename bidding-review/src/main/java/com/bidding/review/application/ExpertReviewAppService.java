package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BinaryObjectStoragePort;
import com.bidding.common.port.ExpertAccountPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.bidding.common.security.UserContext;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.AttachmentRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.CompanyRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.EvidenceChunkRef;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ItemRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.MemberRow;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewItemSeed;
import com.bidding.review.infrastructure.persistence.JdbcExpertReviewStore.ReviewRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 项目专家评审：添加专家、推送、追加。
 */
@Service
public class ExpertReviewAppService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String PACKAGE_TYPE_BID = "BID";
    private static final String STATUS_BUILDING = "BUILDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String MEMBER_STATUS_ACTIVE = "ACTIVE";
    private static final String MEMBER_STATUS_PENDING_APPEND = "PENDING_APPEND";
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
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");
    private static final List<String> IMAGE_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp");

    private final ProjectLookupPort projectLookupPort;
    private final ProjectReviewProgressPort projectReviewProgressPort;
    private final PackageLookupPort packageLookupPort;
    private final ExpertAccountPort expertAccountPort;
    private final JdbcExpertReviewStore expertReviewStore;
    private final BinaryObjectStoragePort objectStoragePort;
    private final long maxAttachmentBytes;
    private final int maxAttachmentsPerItem;

    public ExpertReviewAppService(ProjectLookupPort projectLookupPort,
                                  ProjectReviewProgressPort projectReviewProgressPort,
                                  PackageLookupPort packageLookupPort,
                                  ExpertAccountPort expertAccountPort,
                                  JdbcExpertReviewStore expertReviewStore,
                                  BinaryObjectStoragePort objectStoragePort,
                                  @Value("${bidding.expert-review.attachment.max-size-bytes:10485760}")
                                  long maxAttachmentBytes,
                                  @Value("${bidding.expert-review.attachment.max-per-item:5}")
                                  int maxAttachmentsPerItem) {
        this.projectLookupPort = projectLookupPort;
        this.projectReviewProgressPort = projectReviewProgressPort;
        this.packageLookupPort = packageLookupPort;
        this.expertAccountPort = expertAccountPort;
        this.expertReviewStore = expertReviewStore;
        this.objectStoragePort = objectStoragePort;
        this.maxAttachmentBytes = maxAttachmentBytes;
        this.maxAttachmentsPerItem = maxAttachmentsPerItem;
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
        return createMember(review, project, displayName, false, MEMBER_STATUS_ACTIVE);
    }

    @Transactional
    public void removeMember(long projectId, long memberId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可移除专家");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        ReviewRow review = expertReviewStore.findLatestByProject(operator.getOrgId(), projectId);
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审不存在");
        }
        if (!STATUS_BUILDING.equalsIgnoreCase(review.getStatus())
                && !STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "当前专家评审状态不能移除专家");
        }
        MemberRow member = expertReviewStore.findMemberById(review.getId(), memberId);
        if (member == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家成员不存在");
        }
        if (member.getProjectId() != project.getId()) {
            throw new BizException(ErrorCode.CONFLICT, "专家成员项目不匹配");
        }
        if (STATUS_BUILDING.equalsIgnoreCase(review.getStatus())
                && !MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家成员状态不支持移除");
        }
        if (STATUS_RUNNING.equalsIgnoreCase(review.getStatus())
                && !MEMBER_STATUS_PENDING_APPEND.equalsIgnoreCase(member.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "追加专家已确认，不能移除");
        }
        int deleted = expertReviewStore.markMemberDeleted(review.getId(), memberId);
        if (deleted != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家成员状态已变化，请刷新后重试");
        }
        expertAccountPort.deleteTemporaryExpert(member.getUserId());

        int memberCount = expertReviewStore.countConfirmedMembers(review.getId());
        int updated = expertReviewStore.updateExpertCount(review.getId(), memberCount);
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审状态已变化，请刷新后重试");
        }
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
                expertCount,
                operator.getUserId()
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
        return createMember(review, project, displayName, true, MEMBER_STATUS_PENDING_APPEND);
    }

    @Transactional
    public AppendPushResult pushAppend(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可确认追加专家");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        requireAiReviewSuccess(projectId);
        ReviewRow review = expertReviewStore.findLatestByProject(operator.getOrgId(), projectId);
        if (review == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先推送专家评审");
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "当前项目未处于专家评审中");
        }

        List<MemberRow> pendingMembers = expertReviewStore.listPendingAppendMembers(review.getId());
        if (pendingMembers.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先生成追加专家账号");
        }
        List<ReviewItemSeed> seeds = expertReviewStore.listSeedTemplates(review.getId());
        if (seeds.isEmpty()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审待评项尚未生成，请刷新后重试");
        }

        int inserted = expertReviewStore.insertReviewItemsForMembers(
                review.getId(), projectId, pendingMembers, seeds);
        int expected = pendingMembers.size() * seeds.size();
        if (inserted != expected) {
            throw new BizException(ErrorCode.CONFLICT, "追加专家评审明细生成数量不一致，请刷新后重试");
        }

        List<Long> memberIds = new ArrayList<>();
        for (MemberRow member : pendingMembers) {
            memberIds.add(member.getId());
        }
        int activated = expertReviewStore.activatePendingAppendMembers(review.getId(), memberIds);
        if (activated != pendingMembers.size()) {
            throw new BizException(ErrorCode.CONFLICT, "追加专家状态已变化，请刷新后重试");
        }

        int memberCount = expertReviewStore.countConfirmedMembers(review.getId());
        int updated = expertReviewStore.updateExpertCount(review.getId(), memberCount);
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审状态已变化，请刷新后重试");
        }

        AppendPushResult result = new AppendPushResult();
        result.setReviewId(review.getId());
        result.setProjectId(project.getId());
        result.setStatus(review.getStatus());
        result.setAppendedExpertCount(pendingMembers.size());
        result.setExpertCount(memberCount);
        result.setReviewItemCount(inserted);
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
        Map<Long, List<ItemRow>> itemsByMemberId = loadItemsByMember(review.getId(), members);
        Map<Long, Integer> reviewItemCountsByPackage = countReviewItemsByPackage(itemsByMemberId);
        Map<Long, List<MemberScore>> memberScoresByPackage = collectSubmittedMemberScores(members, itemsByMemberId);
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
        snapshot.setSubmittedCompanyCount(review.isHasAnyCompleteSubmission() ? reviewItemCountsByPackage.size() : 0);
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
            int reviewItemCount = reviewItemCountsByPackage.getOrDefault(company.getBidPackageId(), 0);
            companyTabs.add(toCompanyTab(company, averageScore, ranks.get(company.getBidPackageId()), reviewItemCount));
            scoreCompareItems.add(toScoreCompareItem(
                    company,
                    averageScore,
                    ranks.get(company.getBidPackageId()),
                    memberScoresByPackage.getOrDefault(company.getBidPackageId(), List.of()),
                    reviewItemCount
            ));
            if (isPendingVeto(company)) {
                vetoPendingItems.add(toVetoPendingItem(company));
            }
        }
        snapshot.setCompanyTabs(companyTabs);
        snapshot.setScoreCompareItems(scoreCompareItems);
        snapshot.setAverageRanking(toAverageRanking(companyTabs));
        snapshot.setVetoPendingItems(vetoPendingItems);
        snapshot.setItemReviewGroups(buildItemReviewGroups(companies, members, itemsByMemberId));
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
        detail.setPusherName(review.getPusherName());

        List<ItemRow> items = expertReviewStore.listItemsForMember(review.getId(), member.getId());
        Map<Long, EvidenceChunkRef> evidenceChunkRefs = loadEvidenceChunkRefs(items);
        Map<Long, List<AttachmentRow>> attachmentsByItemId = loadAttachmentsByItemId(items);
        Map<Long, List<TaskItem>> itemsByPackage = groupItemsByPackage(items, evidenceChunkRefs, attachmentsByItemId);
        List<CompanyRow> companies = expertReviewStore.listCompanies(review.getId());
        List<TaskCompany> companyResults = new ArrayList<>();
        for (CompanyRow company : companies) {
            List<TaskItem> companyItems = itemsByPackage.getOrDefault(company.getBidPackageId(), List.of());
            TaskCompany taskCompany = toTaskCompany(company, companyItems.size());
            taskCompany.setItems(companyItems);
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
        ItemRow refreshed = requireTaskItem(review.getId(), member.getId(), itemId);
        Map<Long, EvidenceChunkRef> evidenceChunkRefs = loadEvidenceChunkRefs(List.of(refreshed));
        List<AttachmentRow> attachments = expertReviewStore.listAttachments(refreshed.getId());
        return toTaskItem(refreshed, evidenceChunkRefs, attachments);
    }

    @Transactional
    public AttachmentSummary uploadAttachment(long taskId, long itemId, MultipartFile file) {
        CurrentUser expert = requireExpert();
        MemberRow member = requireTaskMember(taskId, expert);
        ReviewRow review = requireEditableReview(member);
        ItemRow item = requireTaskItem(review.getId(), member.getId(), itemId);
        AttachmentUpload upload = validateAttachmentFile(file);

        int existing = expertReviewStore.countAttachments(item.getId());
        if (existing >= maxAttachmentsPerItem) {
            throw new BizException(ErrorCode.CONFLICT, "每个评审项最多上传 " + maxAttachmentsPerItem + " 个附件");
        }

        String objectKey = buildAttachmentObjectKey(expert.getOrgId(), review.getId(), item.getId(), upload.fileName);
        try (InputStream in = file.getInputStream()) {
            objectStoragePort.put(objectKey, in, upload.sizeBytes, upload.contentType);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取上传附件失败");
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL, "附件写入对象存储失败");
        }

        long attachmentId;
        try {
            attachmentId = expertReviewStore.insertAttachment(
                    item.getId(),
                    objectKey,
                    upload.fileName,
                    upload.contentType,
                    existing + 1
            );
        } catch (RuntimeException e) {
            objectStoragePort.delete(objectKey);
            throw e;
        }
        return toAttachmentSummary(requireAttachment(item.getId(), attachmentId));
    }

    @Transactional(readOnly = true)
    public List<AttachmentSummary> listAttachments(long taskId, long itemId) {
        CurrentUser expert = requireExpert();
        MemberRow member = requireTaskMember(taskId, expert);
        ReviewRow review = requireViewableReview(member);
        ItemRow item = requireTaskItem(review.getId(), member.getId(), itemId);
        return toAttachmentSummaries(expertReviewStore.listAttachments(item.getId()));
    }

    @Transactional(readOnly = true)
    public AttachmentContent openAttachmentForPreview(long taskId, long itemId, long attachmentId) {
        CurrentUser expert = requireExpert();
        MemberRow member = requireTaskMember(taskId, expert);
        ReviewRow review = requireViewableReview(member);
        ItemRow item = requireTaskItem(review.getId(), member.getId(), itemId);
        AttachmentRow attachment = requireAttachment(item.getId(), attachmentId);
        return openAttachmentContent(attachment);
    }

    @Transactional(readOnly = true)
    public AttachmentContent openProjectAttachmentForPreview(long projectId, long itemId, long attachmentId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可查看专家评审附件");
        ProjectLookupPort.ProjectRef project = requireProject(projectId, operator.getOrgId());
        ReviewRow review = expertReviewStore.findLatestByProject(project.getOrgId(), project.getId());
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审不存在");
        }
        ItemRow item = expertReviewStore.findItemById(review.getId(), itemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审条目不存在");
        }
        if (item.getProjectId() != project.getId()) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审条目项目不匹配");
        }
        AttachmentRow attachment = requireAttachment(item.getId(), attachmentId);
        return openAttachmentContent(attachment);
    }

    private AttachmentContent openAttachmentContent(AttachmentRow attachment) {
        InputStream stream;
        try {
            stream = objectStoragePort.open(attachment.getObjectKey());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取专家评审附件失败");
        }

        AttachmentContent content = new AttachmentContent();
        content.setInputStream(stream);
        content.setFileName(attachment.getFileName());
        content.setContentType(attachment.getContentType());
        return content;
    }

    @Transactional
    public void deleteAttachment(long taskId, long itemId, long attachmentId) {
        CurrentUser expert = requireExpert();
        MemberRow member = requireTaskMember(taskId, expert);
        ReviewRow review = requireEditableReview(member);
        ItemRow item = requireTaskItem(review.getId(), member.getId(), itemId);
        AttachmentRow attachment = requireAttachment(item.getId(), attachmentId);
        int deleted = expertReviewStore.deleteAttachment(item.getId(), attachmentId);
        if (deleted != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审附件状态已变化，请刷新后重试");
        }
        objectStoragePort.delete(attachment.getObjectKey());
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

    private MemberResult createMember(ReviewRow review,
                                      ProjectLookupPort.ProjectRef project,
                                      String displayName,
                                      boolean appended,
                                      String memberStatus) {
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
                account.getPasswordHash(),
                memberStatus
        );
        int memberCount = expertReviewStore.countConfirmedMembers(review.getId());
        int updated = expertReviewStore.updateExpertCount(review.getId(), memberCount);
        if (updated != 1) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审状态已变化，请刷新后重试");
        }

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
        cleanupPendingAppendMembers(reviewId);
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

    private void cleanupPendingAppendMembers(long reviewId) {
        List<MemberRow> pendingMembers = expertReviewStore.listPendingAppendMembers(reviewId);
        for (MemberRow member : pendingMembers) {
            int deleted = expertReviewStore.markMemberDeleted(reviewId, member.getId());
            if (deleted != 1) {
                throw new BizException(ErrorCode.CONFLICT, "待追加专家状态已变化，请刷新后重试");
            }
            expertAccountPort.deleteTemporaryExpert(member.getUserId());
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

    private ReviewRow requireViewableReview(MemberRow member) {
        ReviewRow review = expertReviewStore.findById(member.getReviewId());
        if (review == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审任务不存在");
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(review.getStatus())
                && !STATUS_SUCCESS.equalsIgnoreCase(review.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审任务不可查看");
        }
        if (!MEMBER_STATUS_ACTIVE.equalsIgnoreCase(member.getStatus())
                && !MEMBER_STATUS_READ_ONLY.equalsIgnoreCase(member.getStatus())
                && !MEMBER_STATUS_DISABLED.equalsIgnoreCase(member.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审任务不可查看");
        }
        return review;
    }

    private ItemRow requireTaskItem(long reviewId, long memberId, long itemId) {
        ItemRow item = expertReviewStore.findItemByIdForMember(reviewId, memberId, itemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审条目不存在");
        }
        return item;
    }

    private AttachmentRow requireAttachment(long itemId, long attachmentId) {
        AttachmentRow attachment = expertReviewStore.findAttachment(itemId, attachmentId);
        if (attachment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "专家评审附件不存在");
        }
        return attachment;
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
        List<Long> vetoedPackageIds = collectValidVetoPackageIds(items);
        for (ItemRow item : items) {
            if (vetoedPackageIds.contains(item.getBidPackageId())) {
                continue;
            }
            if (ReviewPort.MAIN_QUALIFICATION.equals(item.getItemType())) {
                validateQualificationBeforeSubmit(item);
            } else if (ReviewPort.MAIN_SCORING.equals(item.getItemType())) {
                validateScoringBeforeSubmit(item);
            } else {
                throw new BizException(ErrorCode.CONFLICT, "不支持的专家评审条目类型");
            }
        }
    }

    private List<Long> collectValidVetoPackageIds(List<ItemRow> items) {
        List<Long> result = new ArrayList<>();
        for (ItemRow item : items) {
            if (!item.isVeto()) {
                continue;
            }
            if (!ReviewPort.MAIN_QUALIFICATION.equals(item.getItemType())) {
                continue;
            }
            if (!ReviewPort.VERDICT_FAIL.equals(item.getResult())) {
                continue;
            }
            if (normalizeText(item.getVetoReason()) == null) {
                continue;
            }
            long bidPackageId = item.getBidPackageId();
            if (!result.contains(bidPackageId)) {
                result.add(bidPackageId);
            }
        }
        return result;
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

    private Map<Long, List<ItemRow>> loadItemsByMember(long reviewId, List<MemberRow> members) {
        Map<Long, List<ItemRow>> result = new LinkedHashMap<>();
        if (members == null || members.isEmpty()) {
            return result;
        }
        for (MemberRow member : members) {
            result.put(member.getId(), expertReviewStore.listItemsForMember(reviewId, member.getId()));
        }
        return result;
    }

    private Map<Long, List<MemberScore>> collectSubmittedMemberScores(List<MemberRow> members,
                                                                      Map<Long, List<ItemRow>> itemsByMemberId) {
        Map<Long, List<MemberScore>> result = new LinkedHashMap<>();
        if (members == null || members.isEmpty()) {
            return result;
        }
        for (MemberRow member : members) {
            if (!isCompletedSubmitStatus(member.getSubmitStatus())) {
                continue;
            }
            Map<Long, BigDecimal> totalsByPackage = new HashMap<>();
            List<Long> incompletePackages = new ArrayList<>();
            List<ItemRow> items = requireMemberItems(itemsByMemberId, member);
            for (ItemRow item : items) {
                if (!ReviewPort.MAIN_SCORING.equals(item.getItemType())) {
                    continue;
                }
                if (item.getSubmittedAt() == null) {
                    throw new BizException(ErrorCode.CONFLICT, "已提交评分项状态缺失");
                }
                if (item.getScoreValue() == null) {
                    incompletePackages.add(item.getBidPackageId());
                    totalsByPackage.remove(item.getBidPackageId());
                    continue;
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

    private Map<Long, Integer> countReviewItemsByPackage(Map<Long, List<ItemRow>> itemsByMemberId) {
        Map<Long, List<ItemGroupKey>> keysByPackage = new LinkedHashMap<>();
        for (List<ItemRow> items : itemsByMemberId.values()) {
            for (ItemRow item : items) {
                ItemGroupKey key = new ItemGroupKey(item.getBidPackageId(), item.getRequirementItemId());
                List<ItemGroupKey> keys = keysByPackage.computeIfAbsent(item.getBidPackageId(), ignored -> new ArrayList<>());
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<Long, List<ItemGroupKey>> entry : keysByPackage.entrySet()) {
            result.put(entry.getKey(), entry.getValue().size());
        }
        return result;
    }

    private List<ItemReviewGroup> buildItemReviewGroups(List<CompanyRow> companies,
                                                        List<MemberRow> members,
                                                        Map<Long, List<ItemRow>> itemsByMemberId) {
        Map<Long, CompanyRow> companiesByPackageId = new LinkedHashMap<>();
        Map<Long, Integer> companyOrders = new LinkedHashMap<>();
        for (int i = 0; i < companies.size(); i++) {
            CompanyRow company = companies.get(i);
            companiesByPackageId.put(company.getBidPackageId(), company);
            companyOrders.put(company.getBidPackageId(), i);
        }

        Map<ItemGroupKey, ItemReviewGroup> groups = new LinkedHashMap<>();
        List<Long> itemIds = new ArrayList<>();
        for (MemberRow member : members) {
            List<ItemRow> items = requireMemberItems(itemsByMemberId, member);
            for (ItemRow item : items) {
                if (!itemIds.contains(item.getId())) {
                    itemIds.add(item.getId());
                }
                ItemGroupKey key = new ItemGroupKey(item.getBidPackageId(), item.getRequirementItemId());
                ItemReviewGroup group = groups.get(key);
                if (group == null) {
                    CompanyRow company = companiesByPackageId.get(item.getBidPackageId());
                    if (company == null) {
                        throw new BizException(ErrorCode.CONFLICT, "专家评审公司快照缺失");
                    }
                    group = toItemReviewGroup(company, item);
                    groups.put(key, group);
                }
            }
        }

        Map<Long, List<AttachmentRow>> attachmentsByItemId = itemIds.isEmpty()
                ? new LinkedHashMap<>()
                : expertReviewStore.listAttachmentsByItemIds(itemIds);
        for (ItemReviewGroup group : groups.values()) {
            for (MemberRow member : members) {
                ItemRow item = findGroupItem(requireMemberItems(itemsByMemberId, member), group);
                if (item == null) {
                    throw new BizException(ErrorCode.CONFLICT, "专家评审明细缺失");
                }
                group.getExpertReviews().add(toExpertItemReview(
                        member,
                        item,
                        attachmentsByItemId.getOrDefault(item.getId(), List.of())
                ));
            }
            group.setAverageScore(calculateItemAverageScore(group));
        }

        List<ItemReviewGroup> result = new ArrayList<>(groups.values());
        result.sort(Comparator
                .comparingInt((ItemReviewGroup group) -> companyOrder(companyOrders, group.getBidPackageId()))
                .thenComparingInt(ItemReviewGroup::getSortOrder)
                .thenComparingLong(ItemReviewGroup::getRequirementItemId));
        return result;
    }

    private List<ItemRow> requireMemberItems(Map<Long, List<ItemRow>> itemsByMemberId, MemberRow member) {
        List<ItemRow> items = itemsByMemberId.get(member.getId());
        if (items == null) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审明细缺失");
        }
        return items;
    }

    private ItemRow findGroupItem(List<ItemRow> items, ItemReviewGroup group) {
        for (ItemRow item : items) {
            if (item.getBidPackageId() == group.getBidPackageId()
                    && item.getRequirementItemId() == group.getRequirementItemId()) {
                return item;
            }
        }
        return null;
    }

    private int companyOrder(Map<Long, Integer> companyOrders, long bidPackageId) {
        Integer order = companyOrders.get(bidPackageId);
        if (order == null) {
            throw new BizException(ErrorCode.CONFLICT, "专家评审公司快照缺失");
        }
        return order;
    }

    private ItemReviewGroup toItemReviewGroup(CompanyRow company, ItemRow item) {
        ItemReviewGroup result = new ItemReviewGroup();
        result.setCompanyId(company.getId());
        result.setBidPackageId(company.getBidPackageId());
        result.setSupplierName(company.getSupplierName());
        result.setRequirementItemId(item.getRequirementItemId());
        result.setSourceReviewItemId(item.getSourceReviewItemId());
        result.setItemType(item.getItemType());
        result.setRequirementCode(item.getRequirementCode());
        result.setTitle(item.getTitle());
        result.setContent(item.getContent());
        result.setAiStatus(item.getAiStatus());
        result.setAiVetoRisk(item.getAiVetoRisk());
        result.setAiJudgement(item.getAiJudgement());
        result.setAiSuggestedScore(item.getAiSuggestedScore());
        result.setAiDeductionReason(item.getAiDeductionReason());
        result.setFullScore(item.getFullScore());
        result.setSortOrder(item.getSortOrder());
        return result;
    }

    private ExpertItemReview toExpertItemReview(MemberRow member,
                                                ItemRow item,
                                                List<AttachmentRow> attachments) {
        ExpertItemReview result = new ExpertItemReview();
        result.setMemberId(member.getId());
        result.setUserId(member.getUserId());
        result.setUsername(member.getUsername());
        result.setDisplayName(member.getDisplayName());
        result.setMemberStatus(member.getStatus());
        result.setSubmitStatus(member.getSubmitStatus());
        result.setSubmitted(isCompletedSubmitStatus(member.getSubmitStatus()));
        result.setItemId(item.getId());
        result.setResult(item.getResult());
        result.setScoreValue(item.getScoreValue());
        result.setComment(item.getComment());
        result.setVeto(item.isVeto());
        result.setVetoReason(item.getVetoReason());
        result.setSubmittedAt(item.getSubmittedAt());
        result.setAttachments(toAttachmentSummaries(attachments));
        return result;
    }

    private BigDecimal calculateItemAverageScore(ItemReviewGroup group) {
        if (!ReviewPort.MAIN_SCORING.equals(group.getItemType())) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        for (ExpertItemReview review : group.getExpertReviews()) {
            if (isCompletedSubmitStatus(review.getSubmitStatus()) && review.getScoreValue() != null) {
                total = total.add(review.getScoreValue());
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
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

    private Map<Long, List<TaskItem>> groupItemsByPackage(
            List<ItemRow> rows,
            Map<Long, EvidenceChunkRef> evidenceChunkRefs,
            Map<Long, List<AttachmentRow>> attachmentsByItemId) {
        Map<Long, List<TaskItem>> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (ItemRow row : rows) {
            result.computeIfAbsent(row.getBidPackageId(), ignored -> new ArrayList<>())
                    .add(toTaskItem(
                            row,
                            evidenceChunkRefs,
                            attachmentsByItemId.getOrDefault(row.getId(), List.of())
                    ));
        }
        return result;
    }

    private TaskCompany toTaskCompany(CompanyRow row, int reviewItemCount) {
        TaskCompany result = new TaskCompany();
        result.setCompanyId(row.getId());
        result.setBidPackageId(row.getBidPackageId());
        result.setSupplierName(row.getSupplierName());
        result.setCompanyStatus(row.getCompanyStatus());
        result.setDisplayMode(row.getDisplayMode());
        result.setVetoItemCount(row.getVetoItemCount());
        result.setAverageScore(row.getAverageScore());
        result.setReviewItemCount(reviewItemCount);
        result.setParticipated(reviewItemCount > 0);
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

    private CompanyTab toCompanyTab(CompanyRow company,
                                    BigDecimal averageScore,
                                    Integer rank,
                                    int reviewItemCount) {
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
        result.setReviewItemCount(reviewItemCount);
        result.setParticipated(reviewItemCount > 0);
        return result;
    }

    private ScoreCompareItem toScoreCompareItem(CompanyRow company,
                                                BigDecimal averageScore,
                                                Integer rank,
                                                List<MemberScore> memberScores,
                                                int reviewItemCount) {
        ScoreCompareItem result = new ScoreCompareItem();
        result.setCompanyId(company.getId());
        result.setBidPackageId(company.getBidPackageId());
        result.setSupplierName(company.getSupplierName());
        result.setCompanyStatus(company.getCompanyStatus());
        result.setDisplayMode(company.getDisplayMode());
        result.setAverageScore(averageScore);
        result.setRank(rank);
        result.setReviewItemCount(reviewItemCount);
        result.setParticipated(reviewItemCount > 0);
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
        return toTaskItem(row, new LinkedHashMap<>(), List.of());
    }

    private TaskItem toTaskItem(ItemRow row, Map<Long, EvidenceChunkRef> evidenceChunkRefs) {
        return toTaskItem(row, evidenceChunkRefs, List.of());
    }

    private TaskItem toTaskItem(ItemRow row,
                                Map<Long, EvidenceChunkRef> evidenceChunkRefs,
                                List<AttachmentRow> attachments) {
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
        result.setTenderEvidence(parseEvidence(row.getTenderEvidenceJson(), evidenceChunkRefs));
        result.setBidEvidence(parseEvidence(row.getEvidenceJson(), evidenceChunkRefs));
        result.setResult(row.getResult());
        result.setScoreValue(row.getScoreValue());
        result.setComment(row.getComment());
        result.setVeto(row.isVeto());
        result.setVetoReason(row.getVetoReason());
        result.setSubmittedAt(row.getSubmittedAt());
        result.setAttachments(toAttachmentSummaries(attachments));
        return result;
    }

    private Map<Long, List<AttachmentRow>> loadAttachmentsByItemId(List<ItemRow> items) {
        List<Long> itemIds = new ArrayList<>();
        if (items == null) {
            return new LinkedHashMap<>();
        }
        for (ItemRow item : items) {
            if (!itemIds.contains(item.getId())) {
                itemIds.add(item.getId());
            }
        }
        if (itemIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return expertReviewStore.listAttachmentsByItemIds(itemIds);
    }

    private List<AttachmentSummary> toAttachmentSummaries(List<AttachmentRow> rows) {
        List<AttachmentSummary> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (AttachmentRow row : rows) {
            result.add(toAttachmentSummary(row));
        }
        return result;
    }

    private AttachmentSummary toAttachmentSummary(AttachmentRow row) {
        AttachmentSummary result = new AttachmentSummary();
        result.setAttachmentId(row.getId());
        result.setItemId(row.getItemId());
        result.setFileName(row.getFileName());
        result.setContentType(row.getContentType());
        result.setSortOrder(row.getSortOrder());
        result.setCreatedAt(row.getCreatedAt());
        return result;
    }

    private Map<Long, EvidenceChunkRef> loadEvidenceChunkRefs(List<ItemRow> items) {
        List<Long> chunkIds = collectEvidenceChunkIds(items);
        if (chunkIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<Long, EvidenceChunkRef> refs = expertReviewStore.findEvidenceChunkRefs(chunkIds);
        return refs == null ? new LinkedHashMap<>() : refs;
    }

    private List<Long> collectEvidenceChunkIds(List<ItemRow> items) {
        List<Long> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (ItemRow item : items) {
            collectEvidenceChunkIds(item.getTenderEvidenceJson(), result);
            collectEvidenceChunkIds(item.getEvidenceJson(), result);
        }
        return result;
    }

    private void collectEvidenceChunkIds(String json, List<Long> result) {
        List<EvidenceItem> evidence = parseEvidence(json, new LinkedHashMap<>());
        for (int i = 0; i < evidence.size(); i++) {
            Long fragmentId = evidence.get(i).getFragmentId();
            if (fragmentId != null && !result.contains(fragmentId)) {
                result.add(fragmentId);
            }
        }
    }

    private List<EvidenceItem> parseEvidence(String json, Map<Long, EvidenceChunkRef> refs) {
        List<EvidenceItem> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonNode arr = OBJECT_MAPPER.readTree(json);
            if (arr == null || !arr.isArray()) {
                return result;
            }
            for (int i = 0; i < arr.size(); i++) {
                JsonNode node = arr.get(i);
                if (node == null || !node.isObject()) {
                    continue;
                }
                Long fragmentId = readLong(node, "fragmentId");
                EvidenceItem item = new EvidenceItem();
                item.setFragmentId(fragmentId);
                item.setPage(readInt(node, "page"));
                item.setQuote(readText(node, "quote"));
                EvidenceChunkRef ref = fragmentId == null || refs == null ? null : refs.get(fragmentId);
                if (ref != null) {
                    item.setDocumentId(ref.getDocumentId());
                    item.setFileName(ref.getFileName());
                    item.setPackageId(ref.getPackageId());
                }
                result.add(item);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private static Long readLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer readInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String readText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
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

    private AttachmentUpload validateAttachmentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的附件");
        }
        long size = file.getSize();
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件不能为空");
        }
        if (size > maxAttachmentBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "单个附件不能超过 " + (maxAttachmentBytes / (1024 * 1024)) + "MB");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件文件名无效");
        }
        String safeName = sanitizeFileName(originalName.trim());
        String ext = extractExt(safeName);
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 JPG / PNG / WEBP 图片");
        }
        String contentType = normalizeImageContentType(file.getContentType(), ext);
        return new AttachmentUpload(safeName, contentType, size);
    }

    private String normalizeImageContentType(String rawContentType, String ext) {
        if (rawContentType != null && !rawContentType.isBlank()) {
            String normalized = rawContentType.trim().toLowerCase(Locale.ROOT);
            if (!IMAGE_CONTENT_TYPES.contains(normalized)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 JPG / PNG / WEBP 图片");
            }
            return normalized;
        }
        if ("png".equals(ext)) {
            return "image/png";
        }
        if ("webp".equals(ext)) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private String sanitizeFileName(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        if (base.isEmpty() || base.length() > 256) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件文件名无效或过长");
        }
        for (int i = 0; i < base.length(); i++) {
            if (Character.isISOControl(base.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "附件文件名包含非法字符");
            }
        }
        return base;
    }

    private String extractExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件缺少扩展名");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String buildAttachmentObjectKey(long orgId, long reviewId, long itemId, String fileName) {
        return "org/" + orgId + "/expert-review/" + reviewId + "/item/" + itemId + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "_" + fileName;
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

    private static class ItemGroupKey {
        private final long bidPackageId;
        private final long requirementItemId;

        private ItemGroupKey(long bidPackageId, long requirementItemId) {
            this.bidPackageId = bidPackageId;
            this.requirementItemId = requirementItemId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ItemGroupKey that)) {
                return false;
            }
            return bidPackageId == that.bidPackageId
                    && requirementItemId == that.requirementItemId;
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(bidPackageId);
            result = 31 * result + Long.hashCode(requirementItemId);
            return result;
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

    public static class AttachmentContent {
        private InputStream inputStream;
        private String fileName;
        private String contentType;

        public InputStream getInputStream() {
            return inputStream;
        }

        public void setInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
    }

    public static class AttachmentSummary {
        private long attachmentId;
        private long itemId;
        private String fileName;
        private String contentType;
        private int sortOrder;
        private Instant createdAt;

        public long getAttachmentId() {
            return attachmentId;
        }

        public void setAttachmentId(long attachmentId) {
            this.attachmentId = attachmentId;
        }

        public long getItemId() {
            return itemId;
        }

        public void setItemId(long itemId) {
            this.itemId = itemId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }

    private static class AttachmentUpload {
        private final String fileName;
        private final String contentType;
        private final long sizeBytes;

        AttachmentUpload(String fileName, String contentType, long sizeBytes) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
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

    public static class AppendPushResult {
        private long reviewId;
        private long projectId;
        private String status;
        private int appendedExpertCount;
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

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getAppendedExpertCount() {
            return appendedExpertCount;
        }

        public void setAppendedExpertCount(int appendedExpertCount) {
            this.appendedExpertCount = appendedExpertCount;
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
        private List<ItemReviewGroup> itemReviewGroups = new ArrayList<>();

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

        public List<ItemReviewGroup> getItemReviewGroups() {
            return itemReviewGroups;
        }

        public void setItemReviewGroups(List<ItemReviewGroup> itemReviewGroups) {
            this.itemReviewGroups = itemReviewGroups == null ? new ArrayList<>() : itemReviewGroups;
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
        private int reviewItemCount;
        private boolean participated;

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

        public int getReviewItemCount() {
            return reviewItemCount;
        }

        public void setReviewItemCount(int reviewItemCount) {
            this.reviewItemCount = reviewItemCount;
        }

        public boolean isParticipated() {
            return participated;
        }

        public void setParticipated(boolean participated) {
            this.participated = participated;
        }
    }

    public static class ItemReviewGroup {
        private long companyId;
        private long bidPackageId;
        private String supplierName;
        private long requirementItemId;
        private long sourceReviewItemId;
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
        private BigDecimal averageScore;
        private int sortOrder;
        private List<ExpertItemReview> expertReviews = new ArrayList<>();

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

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
        }

        public long getSourceReviewItemId() {
            return sourceReviewItemId;
        }

        public void setSourceReviewItemId(long sourceReviewItemId) {
            this.sourceReviewItemId = sourceReviewItemId;
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

        public BigDecimal getAverageScore() {
            return averageScore;
        }

        public void setAverageScore(BigDecimal averageScore) {
            this.averageScore = averageScore;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public List<ExpertItemReview> getExpertReviews() {
            return expertReviews;
        }

        public void setExpertReviews(List<ExpertItemReview> expertReviews) {
            this.expertReviews = expertReviews == null ? new ArrayList<>() : expertReviews;
        }
    }

    public static class ExpertItemReview {
        private long memberId;
        private long userId;
        private String username;
        private String displayName;
        private String memberStatus;
        private String submitStatus;
        private boolean submitted;
        private long itemId;
        private String result;
        private BigDecimal scoreValue;
        private String comment;
        private boolean veto;
        private String vetoReason;
        private Instant submittedAt;
        private List<AttachmentSummary> attachments = new ArrayList<>();

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

        public boolean isSubmitted() {
            return submitted;
        }

        public void setSubmitted(boolean submitted) {
            this.submitted = submitted;
        }

        public long getItemId() {
            return itemId;
        }

        public void setItemId(long itemId) {
            this.itemId = itemId;
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

        public List<AttachmentSummary> getAttachments() {
            return attachments;
        }

        public void setAttachments(List<AttachmentSummary> attachments) {
            this.attachments = attachments == null ? new ArrayList<>() : attachments;
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
        private int reviewItemCount;
        private boolean participated;
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

        public int getReviewItemCount() {
            return reviewItemCount;
        }

        public void setReviewItemCount(int reviewItemCount) {
            this.reviewItemCount = reviewItemCount;
        }

        public boolean isParticipated() {
            return participated;
        }

        public void setParticipated(boolean participated) {
            this.participated = participated;
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
        private String pusherName;
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

        public String getPusherName() {
            return pusherName;
        }

        public void setPusherName(String pusherName) {
            this.pusherName = pusherName;
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
        private int reviewItemCount;
        private boolean participated;
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

        public int getReviewItemCount() {
            return reviewItemCount;
        }

        public void setReviewItemCount(int reviewItemCount) {
            this.reviewItemCount = reviewItemCount;
        }

        public boolean isParticipated() {
            return participated;
        }

        public void setParticipated(boolean participated) {
            this.participated = participated;
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
        private List<EvidenceItem> tenderEvidence = new ArrayList<>();
        private List<EvidenceItem> bidEvidence = new ArrayList<>();
        private String result;
        private BigDecimal scoreValue;
        private String comment;
        private boolean veto;
        private String vetoReason;
        private Instant submittedAt;
        private List<AttachmentSummary> attachments = new ArrayList<>();

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

        public List<EvidenceItem> getTenderEvidence() {
            return tenderEvidence;
        }

        public void setTenderEvidence(List<EvidenceItem> tenderEvidence) {
            this.tenderEvidence = tenderEvidence == null ? new ArrayList<>() : tenderEvidence;
        }

        public List<EvidenceItem> getBidEvidence() {
            return bidEvidence;
        }

        public void setBidEvidence(List<EvidenceItem> bidEvidence) {
            this.bidEvidence = bidEvidence == null ? new ArrayList<>() : bidEvidence;
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

        public List<AttachmentSummary> getAttachments() {
            return attachments;
        }

        public void setAttachments(List<AttachmentSummary> attachments) {
            this.attachments = attachments == null ? new ArrayList<>() : attachments;
        }
    }

    public static class EvidenceItem {
        private Long fragmentId;
        private Integer page;
        private String quote;
        private Long documentId;
        private String fileName;
        private Long packageId;

        public Long getFragmentId() {
            return fragmentId;
        }

        public void setFragmentId(Long fragmentId) {
            this.fragmentId = fragmentId;
        }

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public String getQuote() {
            return quote;
        }

        public void setQuote(String quote) {
            this.quote = quote;
        }

        public Long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(Long documentId) {
            this.documentId = documentId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public Long getPackageId() {
            return packageId;
        }

        public void setPackageId(Long packageId) {
            this.packageId = packageId;
        }
    }
}
