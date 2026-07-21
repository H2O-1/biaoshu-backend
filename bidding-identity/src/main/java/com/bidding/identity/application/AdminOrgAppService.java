package com.bidding.identity.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.ProjectCountPort;
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
 * 公司名称、编码创建后不可修改；本期不做删除。
 * 组织与账号分开：允许空组织，账号后续挂到组织下。
 */
@Service
public class AdminOrgAppService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_CITY_LENGTH = 64;

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final CityCatalog cityCatalog;
    private final ProjectCountPort projectCountPort;

    public AdminOrgAppService(OrgRepository orgRepository,
                              UserRepository userRepository,
                              CityCatalog cityCatalog,
                              ProjectCountPort projectCountPort) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.cityCatalog = cityCatalog;
        this.projectCountPort = projectCountPort;
    }

    /**
     * 创建组织。组织类型当前固定为招标方业务场景，不单独存类型字段。
     * 允许先建空组织（无账号）。
     */
    @Transactional
    public OrgSummary createOrg(String name, String city) {
        CurrentUser operator = checkPlatformAdmin();

        String safeName = normalizeName(name);
        if (orgRepository.existsByName(safeName)) {
            throw new BizException(ErrorCode.CONFLICT, "公司名称已存在");
        }
        String safeCity = normalizeCity(city);
        String code = generateUniqueCode();

        long orgId = orgRepository.insert(
                code, safeName, safeCity, STATUS_ACTIVE, operator.getUserId());
        return toSummary(requireOrg(orgId));
    }

    @Transactional(readOnly = true)
    public PageResult<OrgSummary> listOrgs(String nameKeyword, String city, String status,
                                           int page, int size) {
        checkPlatformAdmin();

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
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
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public OrgSummary getOrg(long orgId) {
        checkPlatformAdmin();
        return toSummary(requireOrg(orgId));
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
        // 组织不存在则 404；存在即可查（含 SYSTEM）
        requireOrg(orgId);

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();

        List<UserAccount> accounts = userRepository.searchByOrgId(orgId, kw, offset, safeSize);
        List<AdminUserAppService.UserSummary> items = new ArrayList<>();
        for (UserAccount account : accounts) {
            items.add(AdminUserAppService.UserSummary.from(account));
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
        Org org = requireOrg(orgId);
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
        return toSummary(requireOrg(orgId));
    }

    private OrgSummary toSummary(Org org) {
        OrgSummary summary = new OrgSummary();
        summary.setId(org.getId());
        summary.setCode(org.getCode());
        summary.setName(org.getName());
        summary.setCity(org.getCity());
        summary.setStatus(org.getStatus());
        summary.setUserCount(orgRepository.countUsersByOrgId(org.getId()));
        summary.setProjectCount(projectCountPort.countByOrgId(org.getId()));
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
        private String status;
        private long userCount;
        private long projectCount;
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
}
