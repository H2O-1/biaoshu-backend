package com.bidding.project.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BidPackageReviewSummaryPort;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.port.DocumentProjectBindPort;
import com.bidding.common.port.OrgLookupPort;
import com.bidding.common.port.ProjectReviewProgressPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.bidding.project.domain.model.BizPackage;
import com.bidding.project.domain.model.Project;
import com.bidding.project.domain.port.PackageRepository;
import com.bidding.project.domain.port.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 招标方：本组织下的项目 CRUD。
 * 平台管理员无 TENDERER 角色时不可调用（403）。
 * 创建项目必须绑定一个本组织未占用的招标包，以及至少一个未占用的投标包（同一事务）；名称可空，空则自动生成。
 * 停用后仍可改基本信息（含名称）；后续标包/文件等业务另拦。
 * workflowStatus 为派生业务进度，不落库，与 status（ACTIVE/DISABLED）分离。
 */
@Service
public class ProjectAppService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_DELETED = "DELETED";
    private static final String INDEX_FAILED = "FAILED";

    /** 待补充招标文件（0 文件，或存在未解析成功的招标文件） */
    public static final String WORKFLOW_NEED_TENDER_FILES = "NEED_TENDER_FILES";
    /** 待补充投标文件（0 文件不算齐，或存在未解析成功的投标文件） */
    public static final String WORKFLOW_NEED_BID_FILES = "NEED_BID_FILES";
    /** 待AI审查 */
    public static final String WORKFLOW_READY_FOR_AI_REVIEW = "READY_FOR_AI_REVIEW";
    /** AI审查中 */
    public static final String WORKFLOW_AI_REVIEWING = "AI_REVIEWING";
    /** AI审查完成 */
    public static final String WORKFLOW_AI_REVIEW_SUCCESS = "AI_REVIEW_SUCCESS";
    /** AI审查失败 */
    public static final String WORKFLOW_AI_REVIEW_FAILED = "AI_REVIEW_FAILED";

    /** 主招标文件业务类型（前端上传 fileType；业务约定每项目至多一份） */
    public static final String FILE_TYPE_MAIN_TENDER = "招标文件";

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_AGENCY_LENGTH = 128;
    private static final int MAX_PROJECT_TYPE_LENGTH = 64;
    private static final int MAX_LEADER_NAME_LENGTH = 64;
    /** 按 workflowStatus 筛选时，最多拉取多少条再内存过滤（组织项目量通常远小于此） */
    private static final int WORKFLOW_FILTER_FETCH_LIMIT = 5000;
    private static final ZoneId NAME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter AUTO_NAME_MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter AUTO_NAME_SECOND =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ProjectRepository projectRepository;
    private final PackageRepository packageRepository;
    private final DocumentCountPort documentCountPort;
    private final DocumentProjectBindPort documentProjectBindPort;
    private final OrgLookupPort orgLookupPort;
    private final ProjectReviewProgressPort projectReviewProgressPort;
    private final BidPackageReviewSummaryPort bidPackageReviewSummaryPort;

    public ProjectAppService(ProjectRepository projectRepository,
                             PackageRepository packageRepository,
                             DocumentCountPort documentCountPort,
                             DocumentProjectBindPort documentProjectBindPort,
                             OrgLookupPort orgLookupPort,
                             ProjectReviewProgressPort projectReviewProgressPort,
                             BidPackageReviewSummaryPort bidPackageReviewSummaryPort) {
        this.projectRepository = projectRepository;
        this.packageRepository = packageRepository;
        this.documentCountPort = documentCountPort;
        this.documentProjectBindPort = documentProjectBindPort;
        this.orgLookupPort = orgLookupPort;
        this.projectReviewProgressPort = projectReviewProgressPort;
        this.bidPackageReviewSummaryPort = bidPackageReviewSummaryPort;
    }

    /**
     * 创建项目：绑定 1 个招标包 + 至少 1 个投标包（同一事务）。
     * 招标包 / 投标包须：本组织、类型正确、未删除、尚未绑定其他项目。
     */
    @Transactional
    public ProjectSummary create(String name,
                                 String agency,
                                 String projectType,
                                 String leaderName,
                                 long tenderPackageId,
                                 List<Long> bidPackageIds) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");

        String safeAgency = normalizeAgency(agency);
        String safeType = normalizeProjectType(projectType);
        String safeLeader = normalizeLeaderName(leaderName);
        List<Long> safeBidIds = normalizeBidPackageIds(bidPackageIds, tenderPackageId);

        // 先全部校验可绑，再建项目，避免半成品
        BizPackage tenderPkg = requireBindableTenderPackage(tenderPackageId, operator.getOrgId());
        List<BizPackage> bidPkgs = new ArrayList<>();
        for (int i = 0; i < safeBidIds.size(); i++) {
            bidPkgs.add(requireBindableBidPackage(safeBidIds.get(i), operator.getOrgId()));
        }

        String safeName = resolveCreateName(operator.getOrgId(), name);

        long projectId = projectRepository.insert(
                operator.getOrgId(),
                safeName,
                safeAgency,
                safeType,
                safeLeader,
                STATUS_ACTIVE,
                operator.getUserId()
        );

        int tenderBound = packageRepository.bindToProjectIfUnbound(
                tenderPkg.getId(), projectId, PackageAppService.TYPE_TENDER);
        if (tenderBound != 1) {
            throw new BizException(ErrorCode.CONFLICT, "招标包已被其他项目绑定，请重新选择");
        }
        // 先上传后绑包：回写包内已有文件的 project_id
        documentProjectBindPort.bindPackageDocumentsToProject(tenderPkg.getId(), projectId);

        for (int i = 0; i < bidPkgs.size(); i++) {
            BizPackage bidPkg = bidPkgs.get(i);
            int bidBound = packageRepository.bindToProjectIfUnbound(
                    bidPkg.getId(), projectId, PackageAppService.TYPE_BID);
            if (bidBound != 1) {
                throw new BizException(ErrorCode.CONFLICT, "投标包已被其他项目绑定，请重新选择");
            }
            documentProjectBindPort.bindPackageDocumentsToProject(bidPkg.getId(), projectId);
        }

        return toSummary(requireOwnedProject(projectId, operator.getOrgId()));
    }

    @Transactional(readOnly = true)
    public PageResult<ProjectSummary> list(String keyword,
                                           String status,
                                           String workflowStatus,
                                           int page,
                                           int size) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        String kw = keyword == null ? "" : keyword.trim();
        String statusKw = normalizeStatusFilter(status);
        String workflowKw = normalizeWorkflowFilter(workflowStatus);

        if (workflowKw.isEmpty()) {
            List<Project> projects = projectRepository.searchByOrgId(
                    operator.getOrgId(), kw, statusKw, offset, safeSize);
            List<ProjectSummary> items = new ArrayList<>();
            for (int i = 0; i < projects.size(); i++) {
                items.add(toSummary(projects.get(i)));
            }
            long total = projectRepository.countSearchByOrgId(operator.getOrgId(), kw, statusKw);
            return new PageResult<>(items, total, safePage, safeSize);
        }

        // 按派生状态筛选：先拉本组织候选再内存过滤分页（不落库，无法 SQL 直筛）
        long candidateTotal = projectRepository.countSearchByOrgId(
                operator.getOrgId(), kw, statusKw);
        int fetchLimit = (int) Math.min(Math.max(candidateTotal, 0L), WORKFLOW_FILTER_FETCH_LIMIT);
        List<Project> candidates = projectRepository.searchByOrgId(
                operator.getOrgId(), kw, statusKw, 0, fetchLimit);
        List<ProjectSummary> matched = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ProjectSummary summary = toSummary(candidates.get(i));
            if (workflowKw.equals(summary.getWorkflowStatus())) {
                matched.add(summary);
            }
        }
        long total = matched.size();
        List<ProjectSummary> items = new ArrayList<>();
        for (int i = offset; i < matched.size() && items.size() < safeSize; i++) {
            items.add(matched.get(i));
        }
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public ProjectSummary get(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");
        ProjectSummary summary = toSummary(requireOwnedProject(projectId, operator.getOrgId()));
        fillDetailPackages(summary, projectId);
        return summary;
    }

    /**
     * 项目详情补充：招标包对象 + 投标包列表（含文件解析 indexStatus）。
     * 列表接口不填，避免 N+1。
     */
    private void fillDetailPackages(ProjectSummary summary, long projectId) {
        Optional<BizPackage> tenderOpt = packageRepository.findByProjectIdAndType(
                projectId, PackageAppService.TYPE_TENDER);
        if (tenderOpt.isPresent()) {
            summary.setTenderPackage(toPackageSummary(tenderOpt.get()));
        } else {
            summary.setTenderPackage(null);
        }

        List<BizPackage> bidPkgs = packageRepository.searchByProjectIdAndType(
                projectId, PackageAppService.TYPE_BID, 0, 500);
        Map<Long, BidPackageReviewSummaryPort.Summary> reviewSummaries =
                reviewSummariesByPackage(projectId);
        List<PackageAppService.PackageSummary> bidList = new ArrayList<>();
        for (int i = 0; i < bidPkgs.size(); i++) {
            BizPackage bid = bidPkgs.get(i);
            bidList.add(toPackageSummary(bid, reviewSummaries.get(bid.getId())));
        }
        summary.setBidPackages(bidList);
    }

    /**
     * 更新基本信息（名称、代理机构、项目类型、负责人）。
     * 停用项目仍允许改基本信息；已删除不可改。
     */
    @Transactional
    public ProjectSummary update(long projectId,
                                 String name,
                                 String agency,
                                 String projectType,
                                 String leaderName) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");
        Project project = requireOwnedProject(projectId, operator.getOrgId());
        if (project.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        String safeName = normalizeName(name);
        String safeAgency = normalizeAgency(agency);
        String safeType = normalizeProjectType(projectType);
        String safeLeader = normalizeLeaderName(leaderName);

        if (projectRepository.existsByOrgIdAndName(operator.getOrgId(), safeName, projectId)) {
            throw new BizException(ErrorCode.CONFLICT, "本组织下项目名称已存在");
        }

        projectRepository.updateBasicInfo(projectId, safeName, safeAgency, safeType, safeLeader);
        return toSummary(requireOwnedProject(projectId, operator.getOrgId()));
    }

    @Transactional
    public ProjectSummary updateStatus(long projectId, String status) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");
        Project project = requireOwnedProject(projectId, operator.getOrgId());
        if (project.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        String next = normalizeActiveOrDisabled(status);
        projectRepository.updateStatus(projectId, next);
        return toSummary(requireOwnedProject(projectId, operator.getOrgId()));
    }

    /**
     * 逻辑删除项目。
     * 已绑定的招标包仍占着（不可再绑、不可删），释放规则以后再定。
     * 已绑定的投标包仍挂着 project_id；删投标包本身可直接删。
     */
    @Transactional
    public void delete(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");
        Project project = requireOwnedProject(projectId, operator.getOrgId());
        if (project.isDeleted()) {
            return;
        }
        projectRepository.updateStatus(projectId, STATUS_DELETED);
    }

    /**
     * 项目下添加投标包：将本组织未绑定的投标包绑到该项目。
     * 一投标包只能绑一个项目。
     *
     * @deprecated 请在创建项目时传入 bidPackageIds；本方法暂保留兼容。
     */
    @Deprecated
    @Transactional
    public PackageAppService.PackageSummary bindBidPackage(long projectId, long bidPackageId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");
        requireOwnedProject(projectId, operator.getOrgId());

        BizPackage pkg = requireBindableBidPackage(bidPackageId, operator.getOrgId());
        int bound = packageRepository.bindToProjectIfUnbound(
                pkg.getId(), projectId, PackageAppService.TYPE_BID);
        if (bound != 1) {
            throw new BizException(ErrorCode.CONFLICT, "该投标包已被其他项目绑定，请重新选择");
        }
        documentProjectBindPort.bindPackageDocumentsToProject(pkg.getId(), projectId);

        Optional<BizPackage> after = packageRepository.findById(bidPackageId);
        if (after.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "绑定后查询投标包失败");
        }
        return toPackageSummary(after.get());
    }

    /** 项目下已绑定的投标包列表 */
    @Transactional(readOnly = true)
    public PageResult<PackageAppService.PackageSummary> listBidPackages(
            long projectId, int page, int size) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作项目");
        requireOwnedProject(projectId, operator.getOrgId());

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;

        List<BizPackage> packages = packageRepository.searchByProjectIdAndType(
                projectId, PackageAppService.TYPE_BID, offset, safeSize);
        Map<Long, BidPackageReviewSummaryPort.Summary> reviewSummaries =
                reviewSummariesByPackage(projectId);
        List<PackageAppService.PackageSummary> items = new ArrayList<>();
        for (BizPackage pkg : packages) {
            items.add(toPackageSummary(pkg, reviewSummaries.get(pkg.getId())));
        }
        long total = packageRepository.countByProjectIdAndType(
                projectId, PackageAppService.TYPE_BID);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    private Map<Long, BidPackageReviewSummaryPort.Summary> reviewSummariesByPackage(long projectId) {
        Map<Long, BidPackageReviewSummaryPort.Summary> summaries =
                bidPackageReviewSummaryPort.findLatestSuccessByProjectId(projectId);
        if (summaries == null) {
            return Map.of();
        }
        return summaries;
    }

    private PackageAppService.PackageSummary toPackageSummary(BizPackage pkg) {
        return toPackageSummary(pkg, null);
    }

    private PackageAppService.PackageSummary toPackageSummary(
            BizPackage pkg, BidPackageReviewSummaryPort.Summary reviewSummary) {
        PackageAppService.PackageSummary summary = new PackageAppService.PackageSummary();
        summary.setId(pkg.getId());
        summary.setOrgId(pkg.getOrgId());
        summary.setProjectId(pkg.getProjectId());
        summary.setType(pkg.getType());
        summary.setName(pkg.getName());
        summary.setContactName(pkg.getContactName());
        summary.setContactPhone(pkg.getContactPhone());
        summary.setCompanyAddress(pkg.getCompanyAddress());
        summary.setPriceWithTax(pkg.getPriceWithTax());
        summary.setTaxRate(pkg.getTaxRate());
        summary.setPriceWithoutTax(pkg.getPriceWithoutTax());
        summary.setStatus(pkg.getStatus());
        summary.setCreatedBy(pkg.getCreatedBy());
        summary.setCreatedAt(pkg.getCreatedAt());
        summary.setUpdatedAt(pkg.getUpdatedAt());
        summary.setFileCount(documentCountPort.countActiveByPackageId(pkg.getId()));
        summary.setIndexStatus(documentCountPort.resolveIndexStatusByPackageId(pkg.getId()));
        if (reviewSummary != null) {
            summary.setSuggestedScoreTotal(reviewSummary.getSuggestedScoreTotal());
            summary.setFullScoreTotal(reviewSummary.getFullScoreTotal());
            summary.setQualificationMatchCount(reviewSummary.getQualificationMatchCount());
            summary.setQualificationMismatchCount(reviewSummary.getQualificationMismatchCount());
            summary.setQualificationUnknownCount(reviewSummary.getQualificationUnknownCount());
        }
        return summary;
    }

    private ProjectSummary toSummary(Project project) {
        ProjectSummary summary = new ProjectSummary();
        summary.setId(project.getId());
        summary.setOrgId(project.getOrgId());
        summary.setName(project.getName());
        String tendererOrgName = orgLookupPort.findNameById(project.getOrgId());
        summary.setTendererOrgName(tendererOrgName);
        summary.setAgency(project.getAgency());
        summary.setProjectType(project.getProjectType());
        summary.setLeaderName(project.getLeaderName());
        summary.setStatus(project.getStatus());
        summary.setCreatedBy(project.getCreatedBy());
        summary.setCreatedAt(project.getCreatedAt());
        summary.setUpdatedAt(project.getUpdatedAt());

        Optional<BizPackage> tenderOpt = packageRepository.findByProjectIdAndType(
                project.getId(), PackageAppService.TYPE_TENDER);
        String tenderPackageIndexStatus = null;
        if (tenderOpt.isPresent()) {
            summary.setTenderPackageId(tenderOpt.get().getId());
            summary.setTenderPackageCount(1);
            tenderPackageIndexStatus = documentCountPort.resolveIndexStatusByPackageId(tenderOpt.get().getId());
        } else {
            summary.setTenderPackageId(null);
            summary.setTenderPackageCount(0);
        }

        long tenderFileCount = documentCountPort.countActiveByProjectIdAndPackageType(
                project.getId(), PackageAppService.TYPE_TENDER);
        summary.setTenderFileCount(tenderFileCount);
        summary.setMainTenderFileName(
                documentCountPort.findActiveFileNameByProjectIdAndPackageTypeAndFileType(
                        project.getId(),
                        PackageAppService.TYPE_TENDER,
                        FILE_TYPE_MAIN_TENDER));

        long bidPackageCount = packageRepository.countByProjectIdAndType(
                project.getId(), PackageAppService.TYPE_BID);
        summary.setBidPackageCount(bidPackageCount);
        summary.setBidPackageUploadedCount(
                documentCountPort.countPackagesWithActiveFilesByProjectIdAndPackageType(
                        project.getId(), PackageAppService.TYPE_BID));
        summary.setBidFileCount(documentCountPort.countActiveByProjectIdAndPackageType(
                project.getId(), PackageAppService.TYPE_BID));

        String workflowStatus = resolveWorkflowStatus(project.getId());
        summary.setWorkflowStatus(workflowStatus);
        summary.setWorkflowMessage(resolveWorkflowMessage(
                workflowStatus, tendererOrgName, tenderPackageIndexStatus));
        summary.setAiReviewJobId(
                projectReviewProgressPort.findLatestAiReviewPipelineJobId(project.getId()));
        return summary;
    }

    private String resolveWorkflowMessage(String workflowStatus,
                                          String tendererOrgName,
                                          String tenderPackageIndexStatus) {
        if (WORKFLOW_NEED_TENDER_FILES.equals(workflowStatus)
                && INDEX_FAILED.equals(tenderPackageIndexStatus)) {
            return displayOrgName(tendererOrgName) + "招标文件解析失败";
        }
        if (WORKFLOW_NEED_TENDER_FILES.equals(workflowStatus)) {
            return "待补充招标文件";
        }
        if (WORKFLOW_NEED_BID_FILES.equals(workflowStatus)) {
            return "待补充投标文件";
        }
        if (WORKFLOW_READY_FOR_AI_REVIEW.equals(workflowStatus)) {
            return "待AI审查";
        }
        if (WORKFLOW_AI_REVIEWING.equals(workflowStatus)) {
            return "AI审查中";
        }
        if (WORKFLOW_AI_REVIEW_SUCCESS.equals(workflowStatus)) {
            return "AI审查完成";
        }
        if (WORKFLOW_AI_REVIEW_FAILED.equals(workflowStatus)) {
            return "AI审查失败";
        }
        return null;
    }

    private String displayOrgName(String tendererOrgName) {
        if (tendererOrgName == null || tendererOrgName.trim().isEmpty()) {
            return "";
        }
        return tendererOrgName.trim();
    }

    /**
     * 派生业务进度（互斥，按优先级）：
     * 待补充招标 → 待补充投标 → 待AI审查 / AI审查中 / 完成 / 失败。
     * <ul>
     *   <li>招标侧：0 文件，或存在 index 非 SUCCESS（解析中/失败/待解析）→ 待补充招标</li>
     *   <li>投标侧：0 文件不算齐；或存在 index 非 SUCCESS → 待补充投标</li>
     *   <li>两侧文件齐且均解析成功后：看审查任务 / 最新审查结果</li>
     * </ul>
     */
    String resolveWorkflowStatus(long projectId) {
        long tenderFiles = documentCountPort.countActiveByProjectIdAndPackageType(
                projectId, PackageAppService.TYPE_TENDER);
        long tenderNotReady = documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(
                projectId, PackageAppService.TYPE_TENDER);
        if (tenderFiles < 1 || tenderNotReady > 0) {
            return WORKFLOW_NEED_TENDER_FILES;
        }

        long bidFiles = documentCountPort.countActiveByProjectIdAndPackageType(
                projectId, PackageAppService.TYPE_BID);
        long bidNotReady = documentCountPort.countActiveNotSuccessIndexByProjectIdAndPackageType(
                projectId, PackageAppService.TYPE_BID);
        if (bidFiles < 1 || bidNotReady > 0) {
            return WORKFLOW_NEED_BID_FILES;
        }

        if (projectReviewProgressPort.hasInProgressReview(projectId)) {
            return WORKFLOW_AI_REVIEWING;
        }

        String latest = projectReviewProgressPort.findLatestReviewStatus(projectId);
        if (ReviewPort.STATUS_SUCCESS.equals(latest)) {
            return WORKFLOW_AI_REVIEW_SUCCESS;
        }
        if (ReviewPort.STATUS_FAILED.equals(latest)) {
            return WORKFLOW_AI_REVIEW_FAILED;
        }
        // BUILDING 已在 hasInProgressReview 覆盖；无审查或其它 → 待审查
        return WORKFLOW_READY_FOR_AI_REVIEW;
    }

    /**
     * 校验招标包可绑定：本组织、TENDER、未删除、未绑定。
     */
    private BizPackage requireBindableTenderPackage(long packageId, long orgId) {
        Optional<BizPackage> optional = packageRepository.findById(packageId);
        if (optional.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的招标包");
        }
        BizPackage pkg = optional.get();
        // 跨组织当无效，避免探 ID
        if (pkg.getOrgId() != orgId || pkg.isDeleted()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的招标包");
        }
        if (!pkg.isTender()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "创建项目只能绑定招标包");
        }
        if (pkg.isBoundToProject()) {
            throw new BizException(ErrorCode.CONFLICT, "该招标包已绑定其他项目，请重新选择");
        }
        return pkg;
    }

    /**
     * 校验投标包可绑定：本组织、BID、未删除、未绑定。
     */
    private BizPackage requireBindableBidPackage(long packageId, long orgId) {
        Optional<BizPackage> optional = packageRepository.findById(packageId);
        if (optional.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的投标包");
        }
        BizPackage pkg = optional.get();
        if (pkg.getOrgId() != orgId || pkg.isDeleted()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的投标包");
        }
        if (!pkg.isBid()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能添加投标包");
        }
        if (pkg.isBoundToProject()) {
            throw new BizException(ErrorCode.CONFLICT, "该投标包已绑定其他项目，请重新选择");
        }
        return pkg;
    }

    /** 去重并校验至少一个；不得与招标包 ID 混用。 */
    private List<Long> normalizeBidPackageIds(List<Long> bidPackageIds, long tenderPackageId) {
        if (bidPackageIds == null || bidPackageIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一个投标包");
        }
        List<Long> unique = new ArrayList<>();
        for (int i = 0; i < bidPackageIds.size(); i++) {
            Long id = bidPackageIds.get(i);
            if (id == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "投标包 ID 无效");
            }
            if (id == tenderPackageId) {
                throw new BizException(ErrorCode.BAD_REQUEST, "投标包列表不能包含招标包");
            }
            if (!unique.contains(id)) {
                unique.add(id);
            }
        }
        if (unique.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一个投标包");
        }
        return unique;
    }

    private Project requireOwnedProject(long projectId, long orgId) {
        Optional<Project> optionalProject = projectRepository.findById(projectId);
        if (optionalProject.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        Project project = optionalProject.get();
        // 跨组织一律当不存在，避免探 ID
        if (project.getOrgId() != orgId || project.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    /**
     * 创建时名称：有值则校验唯一；空白则生成「未命名-yyyyMMdd-HHmm」，冲突再加秒/序号。
     */
    private String resolveCreateName(long orgId, String name) {
        if (name != null && !name.trim().isEmpty()) {
            String safeName = normalizeName(name);
            if (projectRepository.existsByOrgIdAndName(orgId, safeName, null)) {
                throw new BizException(ErrorCode.CONFLICT, "本组织下项目名称已存在");
            }
            return safeName;
        }
        return allocateAutoName(orgId);
    }

    private String allocateAutoName(long orgId) {
        ZonedDateTime now = ZonedDateTime.now(NAME_ZONE);
        String minuteName = "未命名-" + AUTO_NAME_MINUTE.format(now);
        if (!projectRepository.existsByOrgIdAndName(orgId, minuteName, null)) {
            return minuteName;
        }
        String secondName = "未命名-" + AUTO_NAME_SECOND.format(now);
        if (!projectRepository.existsByOrgIdAndName(orgId, secondName, null)) {
            return secondName;
        }
        for (int i = 2; i <= 99; i++) {
            String candidate = secondName + "-" + i;
            if (candidate.length() > MAX_NAME_LENGTH) {
                candidate = candidate.substring(0, MAX_NAME_LENGTH);
            }
            if (!projectRepository.existsByOrgIdAndName(orgId, candidate, null)) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.CONFLICT, "自动生成项目名称冲突，请稍后重试或手动填写名称");
    }

    private String normalizeName(String name) {
        return normalizeRequiredText(name, "项目名称", MAX_NAME_LENGTH);
    }

    /** 可选文本：空白存 null */
    private String normalizeOptionalText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return normalizeRequiredText(value, label, maxLength);
    }

    private String normalizeAgency(String agency) {
        return normalizeOptionalText(agency, "代理机构", MAX_AGENCY_LENGTH);
    }

    private String normalizeProjectType(String projectType) {
        return normalizeOptionalText(projectType, "项目类型", MAX_PROJECT_TYPE_LENGTH);
    }

    private String normalizeLeaderName(String leaderName) {
        return normalizeOptionalText(leaderName, "项目负责人", MAX_LEADER_NAME_LENGTH);
    }

    private String normalizeRequiredText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "过长");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, label + "包含非法字符");
            }
        }
        return trimmed;
    }

    private String normalizeStatusFilter(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(normalized) && !STATUS_DISABLED.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 仅支持 ACTIVE 或 DISABLED");
        }
        return normalized;
    }

    private String normalizeWorkflowFilter(String workflowStatus) {
        if (workflowStatus == null || workflowStatus.trim().isEmpty()) {
            return "";
        }
        String normalized = workflowStatus.trim().toUpperCase(Locale.ROOT);
        if (!WORKFLOW_NEED_TENDER_FILES.equals(normalized)
                && !WORKFLOW_NEED_BID_FILES.equals(normalized)
                && !WORKFLOW_READY_FOR_AI_REVIEW.equals(normalized)
                && !WORKFLOW_AI_REVIEWING.equals(normalized)
                && !WORKFLOW_AI_REVIEW_SUCCESS.equals(normalized)
                && !WORKFLOW_AI_REVIEW_FAILED.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "workflowStatus 仅支持 NEED_TENDER_FILES / NEED_BID_FILES / "
                            + "READY_FOR_AI_REVIEW / AI_REVIEWING / "
                            + "AI_REVIEW_SUCCESS / AI_REVIEW_FAILED");
        }
        return normalized;
    }

    private String normalizeActiveOrDisabled(String status) {
        if (status == null || status.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 不能为空");
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_ACTIVE.equals(normalized) && !STATUS_DISABLED.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 仅支持 ACTIVE 或 DISABLED");
        }
        return normalized;
    }

    /** 项目摘要 */
    public static class ProjectSummary {
        private long id;
        private long orgId;
        private String name;
        /** 招标单位名称（当前项目所属组织名称）。 */
        private String tendererOrgName;
        private String agency;
        private String projectType;
        private String leaderName;
        private String status;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        /** 已绑定的招标包 ID；异常历史数据可能为空 */
        private Long tenderPackageId;
        private long tenderPackageCount;
        private long tenderFileCount;
        /** 主招标文件名（file_type=招标文件）；无则为 null */
        private String mainTenderFileName;
        /** 已绑投标包数（登记家数） */
        private long bidPackageCount;
        /** 至少上传过 1 个文件的投标包数 */
        private long bidPackageUploadedCount;
        /** 全部投标包未删除文件数 */
        private long bidFileCount;
        /** 派生业务进度，见 WORKFLOW_* 常量 */
        private String workflowStatus;
        /** 面向页面展示的业务进度文案。 */
        private String workflowMessage;
        /**
         * 最新一键 AI 审查总任务 jobId（type=AI_REVIEW_PIPELINE）；无则 null。
         * 前端可据此继续轮询 GET /api/jobs/{aiReviewJobId}。
         */
        private Long aiReviewJobId;
        /**
         * 仅详情填充：已绑招标包摘要（含 indexStatus）；列表接口为 null。
         */
        private PackageAppService.PackageSummary tenderPackage;
        /**
         * 仅详情填充：项目下投标包列表（含 indexStatus）；列表接口为 null。
         */
        private List<PackageAppService.PackageSummary> bidPackages;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getTendererOrgName() {
            return tendererOrgName;
        }

        public void setTendererOrgName(String tendererOrgName) {
            this.tendererOrgName = tendererOrgName;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getProjectType() {
            return projectType;
        }

        public void setProjectType(String projectType) {
            this.projectType = projectType;
        }

        public String getLeaderName() {
            return leaderName;
        }

        public void setLeaderName(String leaderName) {
            this.leaderName = leaderName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        public Long getTenderPackageId() {
            return tenderPackageId;
        }

        public void setTenderPackageId(Long tenderPackageId) {
            this.tenderPackageId = tenderPackageId;
        }

        public long getTenderPackageCount() {
            return tenderPackageCount;
        }

        public void setTenderPackageCount(long tenderPackageCount) {
            this.tenderPackageCount = tenderPackageCount;
        }

        public long getTenderFileCount() {
            return tenderFileCount;
        }

        public void setTenderFileCount(long tenderFileCount) {
            this.tenderFileCount = tenderFileCount;
        }

        public String getMainTenderFileName() {
            return mainTenderFileName;
        }

        public void setMainTenderFileName(String mainTenderFileName) {
            this.mainTenderFileName = mainTenderFileName;
        }

        public long getBidPackageCount() {
            return bidPackageCount;
        }

        public void setBidPackageCount(long bidPackageCount) {
            this.bidPackageCount = bidPackageCount;
        }

        public long getBidPackageUploadedCount() {
            return bidPackageUploadedCount;
        }

        public void setBidPackageUploadedCount(long bidPackageUploadedCount) {
            this.bidPackageUploadedCount = bidPackageUploadedCount;
        }

        public long getBidFileCount() {
            return bidFileCount;
        }

        public void setBidFileCount(long bidFileCount) {
            this.bidFileCount = bidFileCount;
        }

        public String getWorkflowStatus() {
            return workflowStatus;
        }

        public void setWorkflowStatus(String workflowStatus) {
            this.workflowStatus = workflowStatus;
        }

        public String getWorkflowMessage() {
            return workflowMessage;
        }

        public void setWorkflowMessage(String workflowMessage) {
            this.workflowMessage = workflowMessage;
        }

        public Long getAiReviewJobId() {
            return aiReviewJobId;
        }

        public void setAiReviewJobId(Long aiReviewJobId) {
            this.aiReviewJobId = aiReviewJobId;
        }

        public PackageAppService.PackageSummary getTenderPackage() {
            return tenderPackage;
        }

        public void setTenderPackage(PackageAppService.PackageSummary tenderPackage) {
            this.tenderPackage = tenderPackage;
        }

        public List<PackageAppService.PackageSummary> getBidPackages() {
            return bidPackages;
        }

        public void setBidPackages(List<PackageAppService.PackageSummary> bidPackages) {
            this.bidPackages = bidPackages;
        }
    }
}
