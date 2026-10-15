package com.bidding.identity.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentCountPort;
import com.bidding.common.port.DocumentIndexCountPort;
import com.bidding.common.port.PackageCountPort;
import com.bidding.common.port.ProjectCountPort;
import com.bidding.common.port.ReviewStatusCountPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import com.bidding.identity.infrastructure.city.CityCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台管理员：组织主数据 + 组织下账号二级列表。
 * 编码创建后不可修改；名称与城市可改；逻辑删除仅超管。
 * 组织与账号分开：允许空组织，账号后续挂到组织下。
 */
@Service
public class AdminOrgAppService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_DELETED = "DELETED";
    public static final String ORG_TYPE_SYSTEM = "SYSTEM";
    public static final String ORG_TYPE_TENDERER = "TENDERER";
    public static final String ORG_TYPE_BIDDER = "BIDDER";
    private static final String PACKAGE_TYPE_TENDER = "TENDER";
    private static final String PACKAGE_TYPE_BID = "BID";
    private static final String INDEX_SUCCESS = "SUCCESS";
    private static final String INDEX_FAILED = "FAILED";
    private static final String REVIEW_SUCCESS = "SUCCESS";
    private static final String REVIEW_FAILED = "FAILED";
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_CITY_LENGTH = 64;

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final CityCatalog cityCatalog;
    private final ProjectCountPort projectCountPort;
    private final PackageCountPort packageCountPort;
    private final DocumentIndexCountPort documentIndexCountPort;
    private final DocumentCountPort documentCountPort;
    private final ReviewStatusCountPort reviewStatusCountPort;

    public AdminOrgAppService(OrgRepository orgRepository,
                              UserRepository userRepository,
                              CityCatalog cityCatalog,
                              ProjectCountPort projectCountPort,
                              PackageCountPort packageCountPort,
                              DocumentIndexCountPort documentIndexCountPort,
                              DocumentCountPort documentCountPort,
                              ReviewStatusCountPort reviewStatusCountPort) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.cityCatalog = cityCatalog;
        this.projectCountPort = projectCountPort;
        this.packageCountPort = packageCountPort;
        this.documentIndexCountPort = documentIndexCountPort;
        this.documentCountPort = documentCountPort;
        this.reviewStatusCountPort = reviewStatusCountPort;
    }

    /**
     * 创建组织。一个组织固定为招标方或投标方。
     * 允许先建空组织（无账号）。
     */
    @Transactional
    public OrgSummary createOrg(String name, String city, String orgType) {
        CurrentUser operator = checkPlatformAdmin();

        String safeName = normalizeName(name);
        if (orgRepository.existsByName(safeName)) {
            throw new BizException(ErrorCode.CONFLICT, "公司名称已存在");
        }
        String safeCity = normalizeCity(city);
        String safeOrgType = normalizeOrgType(orgType);
        String code = generateUniqueCode();

        long orgId = orgRepository.insert(
                code, safeName, safeCity, safeOrgType, STATUS_ACTIVE, operator.getUserId());
        return toSummary(requireOrg(orgId));
    }

    public OrgSummary createOrg(String name, String city) {
        return createOrg(name, city, ORG_TYPE_TENDERER);
    }

    /**
     * 编辑组织：可改公司名称、所在城市；编码不可改；系统组织不可改。
     */
    @Transactional
    public OrgSummary updateOrg(long orgId, String name, String city) {
        checkPlatformAdmin();
        Org org = requireActiveOrDisabledOrg(orgId);
        if (org.isSystem()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统组织不允许修改");
        }

        String safeName = normalizeName(name);
        if (orgRepository.existsByNameExceptOrgId(safeName, orgId)) {
            throw new BizException(ErrorCode.CONFLICT, "公司名称已存在");
        }
        String safeCity = normalizeCity(city);

        orgRepository.updateNameAndCity(orgId, safeName, safeCity);
        return toSummary(requireActiveOrDisabledOrg(orgId));
    }

    /**
     * 组织分页列表。
     * keyword 模糊搜索组织名称、城市，以及组织下未删除账号的负责人、手机号、账号、邮箱。
     * activeCount / disabledCount / pendingConfigCount 为全量统计，不受 keyword / city / status 筛选影响。
     * pendingConfigCount：未删除且下属无未删除账号的组织数。
     */
    @Transactional(readOnly = true)
    public OrgPage listOrgs(String nameKeyword, String city, String status,
                            int page, int size) {
        checkPlatformAdmin();

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 500);
        int offset = (safePage - 1) * safeSize;

        String nameKw = nameKeyword == null ? "" : nameKeyword.trim();
        String cityKw = city == null ? "" : city.trim();
        String statusKw = normalizeStatusFilter(status);

        List<Org> orgs = orgRepository.search(nameKw, cityKw, statusKw, offset, safeSize);
        List<OrgSummary> items = new ArrayList<>();
        for (Org org : orgs) {
            items.add(toSummary(org));
        }
        long total = orgRepository.countSearch(nameKw, cityKw, statusKw);

        OrgPage pageResult = new OrgPage();
        pageResult.setItems(items);
        pageResult.setTotal(total);
        pageResult.setPage(safePage);
        pageResult.setSize(safeSize);
        pageResult.setActiveCount(orgRepository.countByStatus(STATUS_ACTIVE));
        pageResult.setDisabledCount(orgRepository.countByStatus(STATUS_DISABLED));
        pageResult.setPendingConfigCount(orgRepository.countPendingConfig());
        return pageResult;
    }

    @Transactional(readOnly = true)
    public OrgDetail getOrg(long orgId) {
        checkPlatformAdmin();
        Org org = requireActiveOrDisabledOrg(orgId);
        OrgSummary summary = toSummary(org);
        OrgDetail detail = new OrgDetail();
        detail.setId(summary.getId());
        detail.setCode(summary.getCode());
        detail.setName(summary.getName());
        detail.setCity(summary.getCity());
        detail.setOrgType(summary.getOrgType());
        detail.setStatus(summary.getStatus());
        detail.setUserCount(summary.getUserCount());
        detail.setProjectCount(summary.getProjectCount());
        detail.setTenderPackageCount(summary.getTenderPackageCount());
        detail.setBidPackageCount(summary.getBidPackageCount());
        detail.setCreatedBy(summary.getCreatedBy());
        detail.setCreatedByName(summary.getCreatedByName());
        detail.setCreatedAt(summary.getCreatedAt());
        detail.setUpdatedAt(summary.getUpdatedAt());
        detail.setDocumentIndexSuccessCount(
                documentIndexCountPort.countActiveByOrgIdAndIndexStatus(orgId, INDEX_SUCCESS));
        detail.setDocumentIndexFailedCount(
                documentIndexCountPort.countActiveByOrgIdAndIndexStatus(orgId, INDEX_FAILED));
        detail.setTenderPackageIndexSuccessCount(
                packageCountPort.countActiveByOrgIdAndTypeAndIndexStatus(orgId, PACKAGE_TYPE_TENDER, INDEX_SUCCESS));
        detail.setTenderPackageIndexFailedCount(
                packageCountPort.countActiveByOrgIdAndTypeAndIndexStatus(orgId, PACKAGE_TYPE_TENDER, INDEX_FAILED));
        detail.setBidPackageIndexSuccessCount(
                packageCountPort.countActiveByOrgIdAndTypeAndIndexStatus(orgId, PACKAGE_TYPE_BID, INDEX_SUCCESS));
        detail.setBidPackageIndexFailedCount(
                packageCountPort.countActiveByOrgIdAndTypeAndIndexStatus(orgId, PACKAGE_TYPE_BID, INDEX_FAILED));
        detail.setReviewSuccessCount(
                reviewStatusCountPort.countByOrgIdAndStatus(orgId, REVIEW_SUCCESS));
        detail.setReviewFailedCount(
                reviewStatusCountPort.countByOrgIdAndStatus(orgId, REVIEW_FAILED));
        return detail;
    }

    /**
     * 二级列表：某组织下全部用户（排除 DELETED），分页 + 关键字。
     * 系统组织也可查看。返回结构复用用户摘要。
     */
    @Transactional(readOnly = true)
    public PageResult<AdminUserAppService.UserSummary> listOrgUsers(long orgId,
                                                                    String keyword,
                                                                    int page,
                                                                    int size) {
        checkPlatformAdmin();
        // 组织不存在或已删除则 404；存在即可查（含 SYSTEM）
        requireActiveOrDisabledOrg(orgId);

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();

        List<UserAccount> accounts = userRepository.searchByOrgId(orgId, kw, offset, safeSize);
        List<AdminUserAppService.UserSummary> items = new ArrayList<>();
        for (UserAccount account : accounts) {
            AdminUserAppService.UserSummary summary = AdminUserAppService.UserSummary.from(account);
            summary.setCreatedByName(resolveCreatedByName(account.getCreatedBy()));
            items.add(summary);
        }
        long total = userRepository.countSearchByOrgId(orgId, kw);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    /**
     * 启用 / 停用组织。系统组织不允许停用。
     * 停用时吊销该组织下未删除用户的会话；不改用户自身 status。
     * 重新启用不 bump；已是停用再停用则跳过 bump。
     */
    @Transactional
    public OrgSummary updateStatus(long orgId, String status) {
        checkPlatformAdmin();
        Org org = requireActiveOrDisabledOrg(orgId);
        if (org.isSystem()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统组织不允许变更状态");
        }

        String next = normalizeActiveOrDisabled(status);
        boolean becomingDisabled = STATUS_DISABLED.equals(next) && !STATUS_DISABLED.equalsIgnoreCase(org.getStatus());

        orgRepository.updateStatus(orgId, next);

        // 仅在「变为停用」时批量吊销会话；用户 status 保持不变
        if (becomingDisabled) {
            userRepository.bumpTokenVersionByOrgId(orgId);
        }
        return toSummary(requireActiveOrDisabledOrg(orgId));
    }

    /**
     * 逻辑删除组织：仅超管；系统组织不可删；仍有用户/项目/标包/文件时禁止。
     * 删除后吊销该组织下未删除用户会话。
     */
    @Transactional
    public void deleteOrg(long orgId) {
        CurrentUser operator = checkPlatformAdmin();
        if (!operator.isPlatformSuperAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台超级管理员可删除组织");
        }
        Org org = requireActiveOrDisabledOrg(orgId);
        if (org.isSystem()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统组织不允许删除");
        }

        long userCount = orgRepository.countUsersByOrgId(orgId);
        if (userCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织下仍有用户，无法删除");
        }
        long projectCount = projectCountPort.countByOrgId(orgId);
        if (projectCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织下仍有项目，无法删除");
        }
        // type 空串 = 不限类型（招标包 + 投标包）
        long packageCount = packageCountPort.countActiveByOrgIdAndType(orgId, "");
        if (packageCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织下仍有标包，无法删除");
        }
        long documentCount = documentCountPort.countActiveByOrgId(orgId);
        if (documentCount > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织下仍有文件，无法删除");
        }

        orgRepository.updateStatus(orgId, STATUS_DELETED);
        userRepository.bumpTokenVersionByOrgId(orgId);
    }

    private OrgSummary toSummary(Org org) {
        long orgId = org.getId();
        OrgSummary summary = new OrgSummary();
        summary.setId(orgId);
        summary.setCode(org.getCode());
        summary.setName(org.getName());
        summary.setCity(org.getCity());
        summary.setOrgType(org.getOrgType());
        summary.setStatus(org.getStatus());
        summary.setUserCount(orgRepository.countUsersByOrgId(orgId));
        summary.setProjectCount(projectCountPort.countByOrgId(orgId));
        summary.setTenderPackageCount(
                packageCountPort.countActiveByOrgIdAndType(orgId, PACKAGE_TYPE_TENDER));
        summary.setBidPackageCount(
                packageCountPort.countActiveByOrgIdAndType(orgId, PACKAGE_TYPE_BID));
        summary.setCreatedBy(org.getCreatedBy());
        summary.setCreatedByName(resolveCreatedByName(org.getCreatedBy()));
        summary.setCreatedAt(org.getCreatedAt());
        summary.setUpdatedAt(org.getUpdatedAt());
        return summary;
    }

    private String resolveCreatedByName(Long createdBy) {
        if (createdBy == null) {
            return null;
        }
        Optional<UserAccount> optionalUser = userRepository.findById(createdBy);
        if (optionalUser.isEmpty()) {
            return null;
        }
        return optionalUser.get().getDisplayName();
    }

    private Org requireActiveOrDisabledOrg(long orgId) {
        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty() || optionalOrg.get().isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "组织不存在");
        }
        return optionalOrg.get();
    }

    private Org requireOrg(long orgId) {
        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "组织不存在");
        }
        return optionalOrg.get();
    }

    private String generateUniqueCode() {
        // 自动生成唯一编码；创建后不可改
        for (int i = 0; i < 5; i++) {
            String code = "ORG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
            if (!orgRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new BizException(ErrorCode.INTERNAL, "生成组织编码失败，请重试");
    }

    private String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "公司名称过长");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "公司名称包含非法字符");
            }
        }
        return trimmed;
    }

    /**
     * 所在城市必须从全国地级市词典中选择（精确匹配正式名，如「深圳市」）。
     */
    private String normalizeCity(String city) {
        if (city == null || city.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所在城市不能为空");
        }
        String trimmed = city.trim();
        if (trimmed.length() > MAX_CITY_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所在城市过长");
        }
        if (!cityCatalog.containsName(trimmed)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择有效的所在城市");
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

    private String normalizeOrgType(String orgType) {
        if (orgType == null || orgType.trim().isEmpty()) {
            return ORG_TYPE_TENDERER;
        }
        String normalized = orgType.trim().toUpperCase(Locale.ROOT);
        if (!ORG_TYPE_TENDERER.equals(normalized) && !ORG_TYPE_BIDDER.equals(normalized)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "orgType 仅支持 TENDERER 或 BIDDER");
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

    private CurrentUser checkPlatformAdmin() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isPlatformAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可操作");
        }
        return user;
    }

    /** 组织摘要（列表 / 详情） */
    public static class OrgSummary {
        private long id;
        private String code;
        private String name;
        private String city;
        private String orgType;
        private String status;
        private long userCount;
        private long projectCount;
        private long tenderPackageCount;
        private long bidPackageCount;
        private Long createdBy;
        private String createdByName;
        private Instant createdAt;
        private Instant updatedAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        public String getOrgType() {
            return orgType;
        }

        public void setOrgType(String orgType) {
            this.orgType = orgType;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public long getUserCount() {
            return userCount;
        }

        public void setUserCount(long userCount) {
            this.userCount = userCount;
        }

        public long getProjectCount() {
            return projectCount;
        }

        public void setProjectCount(long projectCount) {
            this.projectCount = projectCount;
        }

        public long getTenderPackageCount() {
            return tenderPackageCount;
        }

        public void setTenderPackageCount(long tenderPackageCount) {
            this.tenderPackageCount = tenderPackageCount;
        }

        public long getBidPackageCount() {
            return bidPackageCount;
        }

        public void setBidPackageCount(long bidPackageCount) {
            this.bidPackageCount = bidPackageCount;
        }

        public Long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(Long createdBy) {
            this.createdBy = createdBy;
        }

        public String getCreatedByName() {
            return createdByName;
        }

        public void setCreatedByName(String createdByName) {
            this.createdByName = createdByName;
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
    }

    /** 组织分页（含全量正常/停用/待配置计数）。 */
    public static class OrgPage {
        private List<OrgSummary> items = new ArrayList<>();
        private long total;
        private int page;
        private int size;
        private long activeCount;
        private long disabledCount;
        private long pendingConfigCount;

        public List<OrgSummary> getItems() {
            return items;
        }

        public void setItems(List<OrgSummary> items) {
            this.items = items;
        }

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getActiveCount() {
            return activeCount;
        }

        public void setActiveCount(long activeCount) {
            this.activeCount = activeCount;
        }

        public long getDisabledCount() {
            return disabledCount;
        }

        public void setDisabledCount(long disabledCount) {
            this.disabledCount = disabledCount;
        }

        public long getPendingConfigCount() {
            return pendingConfigCount;
        }

        public void setPendingConfigCount(long pendingConfigCount) {
            this.pendingConfigCount = pendingConfigCount;
        }
    }

    /**
     * 组织详情：摘要字段 + 解析/审查统计（仅详情接口）。
     */
    public static class OrgDetail extends OrgSummary {
        private long documentIndexSuccessCount;
        private long documentIndexFailedCount;
        private long tenderPackageIndexSuccessCount;
        private long tenderPackageIndexFailedCount;
        private long bidPackageIndexSuccessCount;
        private long bidPackageIndexFailedCount;
        private long reviewSuccessCount;
        private long reviewFailedCount;

        public long getDocumentIndexSuccessCount() {
            return documentIndexSuccessCount;
        }

        public void setDocumentIndexSuccessCount(long documentIndexSuccessCount) {
            this.documentIndexSuccessCount = documentIndexSuccessCount;
        }

        public long getDocumentIndexFailedCount() {
            return documentIndexFailedCount;
        }

        public void setDocumentIndexFailedCount(long documentIndexFailedCount) {
            this.documentIndexFailedCount = documentIndexFailedCount;
        }

        public long getTenderPackageIndexSuccessCount() {
            return tenderPackageIndexSuccessCount;
        }

        public void setTenderPackageIndexSuccessCount(long tenderPackageIndexSuccessCount) {
            this.tenderPackageIndexSuccessCount = tenderPackageIndexSuccessCount;
        }

        public long getTenderPackageIndexFailedCount() {
            return tenderPackageIndexFailedCount;
        }

        public void setTenderPackageIndexFailedCount(long tenderPackageIndexFailedCount) {
            this.tenderPackageIndexFailedCount = tenderPackageIndexFailedCount;
        }

        public long getBidPackageIndexSuccessCount() {
            return bidPackageIndexSuccessCount;
        }

        public void setBidPackageIndexSuccessCount(long bidPackageIndexSuccessCount) {
            this.bidPackageIndexSuccessCount = bidPackageIndexSuccessCount;
        }

        public long getBidPackageIndexFailedCount() {
            return bidPackageIndexFailedCount;
        }

        public void setBidPackageIndexFailedCount(long bidPackageIndexFailedCount) {
            this.bidPackageIndexFailedCount = bidPackageIndexFailedCount;
        }

        public long getReviewSuccessCount() {
            return reviewSuccessCount;
        }

        public void setReviewSuccessCount(long reviewSuccessCount) {
            this.reviewSuccessCount = reviewSuccessCount;
        }

        public long getReviewFailedCount() {
            return reviewFailedCount;
        }

        public void setReviewFailedCount(long reviewFailedCount) {
            this.reviewFailedCount = reviewFailedCount;
        }
    }
}
