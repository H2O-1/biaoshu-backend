package com.bidding.identity.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Org;
import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.OrgRepository;
import com.bidding.identity.domain.port.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.time.Instant;

/**
 * 平台管理员业务：查角色、查用户、创建用户、改角色、重置密码、停用/物理删除。
 */
@Service
public class AdminUserAppService {

    /** 用户名：字母数字下划线，3~64 位（降低注入与脏数据风险） */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,64}$");

    /** 中国大陆手机号：1 开头共 11 位数字 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** 邮箱：简单格式校验 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final int MAX_DISPLAY_NAME_LENGTH = 128;
    private static final int MAX_EMAIL_LENGTH = 128;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_DELETED = "DELETED";

    private final UserRepository userRepository;
    private final OrgRepository orgRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserAppService(UserRepository userRepository,
                               OrgRepository orgRepository,
                               PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles() {
        checkPlatformAdmin();
        return userRepository.listRoles();
    }

    @Transactional(readOnly = true)
    public PageResult<UserSummary> listUsers(String keyword, int page, int size) {
        checkPlatformAdmin();

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();

        List<UserAccount> accounts = userRepository.search(kw, offset, safeSize);
        List<UserSummary> items = new ArrayList<>();
        for (UserAccount account : accounts) {
            items.add(toSummary(account));
        }
        long total = userRepository.countSearch(kw);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public UserSummary getUser(long userId) {
        checkPlatformAdmin();
        return toSummary(requireUser(userId));
    }

    /**
     * 平台方账号分页列表（仅 PLATFORM_ADMIN / PLATFORM_SUPER_ADMIN，排除已删除）。
     * status 可筛 ACTIVE / DISABLED；空=全部。
     * activeCount / disabledCount 为全量平台账号统计，不受 keyword / status 影响。
     */
    @Transactional(readOnly = true)
    public PlatformAdminPage listPlatformAdmins(String keyword, String status, int page, int size) {
        checkPlatformAdmin();

        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();
        String statusKw = normalizeStatusFilter(status);

        List<UserAccount> accounts = userRepository.searchPlatformAdmins(kw, statusKw, offset, safeSize);
        List<UserSummary> items = new ArrayList<>();
        for (UserAccount account : accounts) {
            items.add(toSummary(account));
        }
        long total = userRepository.countPlatformAdmins(kw, statusKw);

        PlatformAdminPage pageResult = new PlatformAdminPage();
        pageResult.setItems(items);
        pageResult.setTotal(total);
        pageResult.setPage(safePage);
        pageResult.setSize(safeSize);
        pageResult.setActiveCount(userRepository.countPlatformAdminsByStatus(STATUS_ACTIVE));
        pageResult.setDisabledCount(userRepository.countPlatformAdminsByStatus(STATUS_DISABLED));
        return pageResult;
    }

    /**
     * 平台方账号详情。
     */
    @Transactional(readOnly = true)
    public UserSummary getPlatformAdmin(long userId) {
        checkPlatformAdmin();
        UserAccount account = requireUser(userId);
        if (STATUS_DELETED.equalsIgnoreCase(account.getStatus())
                || !account.isPlatformAdminAccount()) {
            throw new BizException(ErrorCode.NOT_FOUND, "平台账号不存在");
        }
        return toSummary(account);
    }

    /**
     * 创建用户：指定组织、手机号、初始角色；创建后必须首次改密。
     */
    @Transactional
    public UserSummary createUser(long orgId,
                                  String username,
                                  String password,
                                  String displayName,
                                  String phone,
                                  String email,
                                  List<String> roleCodes) {
        CurrentUser operator = checkPlatformAdmin();

        if (orgId <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "orgId 不合法");
        }
        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty() || optionalOrg.get().isDeleted()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织不存在");
        }
        if (!optionalOrg.get().isActive()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织已停用，不能创建用户");
        }

        String safeUsername = normalizeUsername(username);
        if (userRepository.existsByUsername(safeUsername)) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }

        String safePhone = normalizePhone(phone);
        if (userRepository.existsByPhone(safePhone)) {
            throw new BizException(ErrorCode.CONFLICT, "手机号已存在");
        }

        String safeEmail = normalizeEmail(email);
        if (userRepository.existsByEmail(safeEmail)) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已存在");
        }

        String safeDisplayName = normalizeDisplayName(displayName);
        PasswordPolicy.validateNewPassword(password);
        Set<String> roles = checkAndNormalizeRoles(roleCodes);
        applySuperAdminRoleRules(operator, Set.of(), roles);

        String passwordHash = passwordEncoder.encode(password);
        // 新建账号一律强制首次改密
        long userId = userRepository.insert(
                orgId, safeUsername, passwordHash, safeDisplayName, safePhone, safeEmail,
                STATUS_ACTIVE, true, operator.getUserId());
        userRepository.replaceRoles(userId, roles);
        if (roles.contains(RoleCode.PLATFORM_SUPER_ADMIN.name())) {
            revokeOtherSuperAdmins(userId);
        }

        return toSummary(requireUser(userId));
    }

    /**
     * 创建平台管理员：固定挂系统组织，角色固定 PLATFORM_ADMIN；手机号/邮箱为空。
     * 任意平台管理员（含超管）可调用。
     */
    @Transactional
    public UserSummary createPlatformAdmin(String username, String password, String displayName) {
        CurrentUser operator = checkPlatformAdmin();

        Optional<Long> optionalOrgId = orgRepository.findSystemOrgId();
        if (optionalOrgId.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "缺少系统组织");
        }
        long orgId = optionalOrgId.get();
        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty() || optionalOrg.get().isDeleted() || !optionalOrg.get().isActive()) {
            throw new BizException(ErrorCode.INTERNAL, "系统组织不可用");
        }

        String safeUsername = normalizeUsername(username);
        if (userRepository.existsByUsername(safeUsername)) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }

        String safeDisplayName = normalizeDisplayName(displayName);
        PasswordPolicy.validateNewPassword(password);

        Set<String> roles = new HashSet<>();
        roles.add(RoleCode.PLATFORM_ADMIN.name());

        String passwordHash = passwordEncoder.encode(password);
        long userId = userRepository.insert(
                orgId, safeUsername, passwordHash, safeDisplayName, null, null,
                STATUS_ACTIVE, true, operator.getUserId());
        userRepository.replaceRoles(userId, roles);

        return toSummary(requireUser(userId));
    }

    /**
     * 修改指定招标方组织下招标方账号的负责人姓名、手机号、邮箱（均必填）。
     * 不改公司名称、城市、登录账号。
     */
    @Transactional
    public UserSummary updateTendererProfile(long orgId,
                                             long userId,
                                             String displayName,
                                             String phone,
                                             String email) {
        checkPlatformAdmin();

        Optional<Org> optionalOrg = orgRepository.findById(orgId);
        if (optionalOrg.isEmpty() || optionalOrg.get().isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "组织不存在");
        }
        Org org = optionalOrg.get();
        if (org.isSystem()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "系统组织下账号请使用其他管理接口");
        }

        UserAccount target = requireUser(userId);
        if (STATUS_DELETED.equalsIgnoreCase(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已删除账号不能修改资料");
        }
        if (target.getOrgId() != orgId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户不属于该组织");
        }
        if (!target.getRoleCodes().contains(RoleCode.TENDERER.name())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅可修改招标方账号资料");
        }

        String safeDisplayName = normalizeDisplayName(displayName);
        String safePhone = normalizePhone(phone);
        if (userRepository.existsByPhoneExceptUserId(safePhone, userId)) {
            throw new BizException(ErrorCode.CONFLICT, "手机号已存在");
        }
        String safeEmail = normalizeEmail(email);
        if (userRepository.existsByEmailExceptUserId(safeEmail, userId)) {
            throw new BizException(ErrorCode.CONFLICT, "邮箱已存在");
        }

        userRepository.updateProfile(userId, safeDisplayName, safePhone, safeEmail);
        return toSummary(requireUser(userId));
    }

    /**
     * 设置用户角色（全量覆盖），并吊销对方旧会话。
     */
    @Transactional
    public UserSummary replaceRoles(long userId, List<String> roleCodes) {
        CurrentUser operator = checkPlatformAdmin();

        UserAccount target = requireUser(userId);
        Set<String> nextRoles = checkAndNormalizeRoles(roleCodes);
        applySuperAdminRoleRules(operator, target.getRoleCodes(), nextRoles);

        boolean wasAdmin = target.getRoleCodes().contains(RoleCode.PLATFORM_ADMIN.name())
                || target.getRoleCodes().contains(RoleCode.PLATFORM_SUPER_ADMIN.name());
        boolean willBeAdmin = nextRoles.contains(RoleCode.PLATFORM_ADMIN.name())
                || nextRoles.contains(RoleCode.PLATFORM_SUPER_ADMIN.name());

        if (wasAdmin && !willBeAdmin
                && countEffectivePlatformAdmins() <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }

        userRepository.replaceRoles(userId, nextRoles);
        if (nextRoles.contains(RoleCode.PLATFORM_SUPER_ADMIN.name())
                && !target.getRoleCodes().contains(RoleCode.PLATFORM_SUPER_ADMIN.name())) {
            revokeOtherSuperAdmins(userId);
        }
        // 角色变化后旧 Token 立即失效
        userRepository.bumpTokenVersion(userId);

        return toSummary(requireUser(userId));
    }

    /**
     * 重置密码：生成临时密码，强制改密，并吊销旧会话。
     * 临时密码明文仅本次响应返回，供管理员页面一次性展示。
     */
    @Transactional
    public ResetPasswordResult resetPassword(long userId) {
        checkPlatformAdmin();

        UserAccount target = requireUser(userId);
        if (STATUS_DELETED.equalsIgnoreCase(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已删除账号不能重置密码");
        }

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        String hash = passwordEncoder.encode(temporaryPassword);
        userRepository.updatePassword(userId, hash, true);
        userRepository.bumpTokenVersion(userId);

        ResetPasswordResult result = new ResetPasswordResult();
        result.setUserId(userId);
        result.setReset(true);
        result.setTemporaryPassword(temporaryPassword);
        result.setMessage("临时密码已生成，请立即复制保存，关闭后不可再查看");
        return result;
    }

    /**
     * 更新账号状态：ACTIVE / DISABLED。停用时吊销会话。
     */
    @Transactional
    public UserSummary updateStatus(long userId, String status) {
        checkPlatformAdmin();
        UserAccount target = requireUser(userId);

        String next = normalizeActiveOrDisabled(status);
        if (STATUS_DELETED.equalsIgnoreCase(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已删除账号不能改状态");
        }

        if (STATUS_DISABLED.equals(next)) {
            ensureNotLastPlatformAdmin(target);
            ensureNotLastSuperAdmin(target);
        }

        userRepository.updateStatus(userId, next);
        if (STATUS_DISABLED.equals(next)) {
            userRepository.bumpTokenVersion(userId);
        }
        return toSummary(requireUser(userId));
    }

    /**
     * 物理删除账号：释放用户名、手机号、邮箱；历史业务记录保留创建人 ID 快照。
     */
    @Transactional
    public void deleteUser(long userId) {
        checkPlatformAdmin();
        UserAccount target = requireUser(userId);

        ensureNotLastPlatformAdmin(target);
        ensureNotLastSuperAdmin(target);

        userRepository.deleteById(userId);
    }

    private void ensureNotLastPlatformAdmin(UserAccount target) {
        boolean isAdmin = target.getRoleCodes().contains(RoleCode.PLATFORM_ADMIN.name())
                || target.getRoleCodes().contains(RoleCode.PLATFORM_SUPER_ADMIN.name());
        if (!isAdmin) {
            return;
        }
        if (countEffectivePlatformAdmins() <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }
    }

    private void ensureNotLastSuperAdmin(UserAccount target) {
        if (!target.getRoleCodes().contains(RoleCode.PLATFORM_SUPER_ADMIN.name())) {
            return;
        }
        if (userRepository.countUsersWithRole(RoleCode.PLATFORM_SUPER_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台超级管理员");
        }
    }

    /**
     * 超管全局唯一：仅现任超管可授予/摘除 PLATFORM_SUPER_ADMIN；
     * 授予时自动附带 PLATFORM_ADMIN。
     */
    private void applySuperAdminRoleRules(CurrentUser operator,
                                          Set<String> currentRoles,
                                          Set<String> nextRoles) {
        boolean wasSuper = currentRoles.contains(RoleCode.PLATFORM_SUPER_ADMIN.name());
        boolean willBeSuper = nextRoles.contains(RoleCode.PLATFORM_SUPER_ADMIN.name());

        if (wasSuper == willBeSuper) {
            return;
        }
        if (!operator.isPlatformSuperAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台超级管理员可变更超管角色");
        }
        if (wasSuper && !willBeSuper
                && userRepository.countUsersWithRole(RoleCode.PLATFORM_SUPER_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台超级管理员");
        }
        if (willBeSuper) {
            // 超管同时具备普管能力
            nextRoles.add(RoleCode.PLATFORM_ADMIN.name());
        }
    }

    /**
     * 将 PLATFORM_SUPER_ADMIN 从除 keepUserId 外的所有账号上摘掉，并吊销其会话。
     */
    private void revokeOtherSuperAdmins(long keepUserId) {
        List<Long> holderIds = userRepository.listUserIdsWithRole(
                RoleCode.PLATFORM_SUPER_ADMIN.name());
        for (Long holderId : holderIds) {
            if (holderId == null || holderId == keepUserId) {
                continue;
            }
            Optional<UserAccount> optionalHolder = userRepository.findById(holderId);
            if (optionalHolder.isEmpty()) {
                continue;
            }
            UserAccount holder = optionalHolder.get();
            Set<String> roles = new HashSet<>(holder.getRoleCodes());
            if (!roles.remove(RoleCode.PLATFORM_SUPER_ADMIN.name())) {
                continue;
            }
            if (roles.isEmpty()) {
                roles.add(RoleCode.PLATFORM_ADMIN.name());
            }
            userRepository.replaceRoles(holder.getId(), roles);
            userRepository.bumpTokenVersion(holder.getId());
        }
    }

    /** 未删除账号中，具备平台管理能力（普管或超管）的去重人数。 */
    private long countEffectivePlatformAdmins() {
        return userRepository.countDistinctActiveUsersWithAnyRole(List.of(
                RoleCode.PLATFORM_ADMIN.name(),
                RoleCode.PLATFORM_SUPER_ADMIN.name()));
    }

    private UserAccount requireUser(long userId) {
        Optional<UserAccount> optionalUser = userRepository.findById(userId);
        if (optionalUser.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return optionalUser.get();
    }

    private String normalizeUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        String trimmed = username.trim();
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名仅允许字母、数字、下划线，长度 3~64");
        }
        return trimmed;
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "显示名不能为空");
        }
        String trimmed = displayName.trim();
        if (trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "显示名过长");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "显示名包含非法字符");
            }
        }
        return trimmed;
    }

    /** 校验并规范化手机号 */
    private String normalizePhone(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号不能为空");
        }
        String trimmed = phone.trim();
        if (!PHONE_PATTERN.matcher(trimmed).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "手机号格式不正确，须为 11 位中国大陆手机号");
        }
        return trimmed;
    }

    /** 校验并规范化邮箱（统一转小写，避免大小写重复） */
    private String normalizeEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_EMAIL_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱过长");
        }
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
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

    private Set<String> checkAndNormalizeRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少指定一个角色");
        }
        Set<String> result = new HashSet<>();
        for (String raw : roleCodes) {
            RoleCode role = RoleCode.tryParse(raw);
            if (role == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知角色: " + raw);
            }
            result.add(role.name());
        }
        return result;
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

    private UserSummary toSummary(UserAccount account) {
        UserSummary summary = UserSummary.from(account);
        summary.setCreatedByName(resolveCreatedByName(account.getCreatedBy()));
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

    /** 平台方账号分页（含全量正常/停用计数）。 */
    public static class PlatformAdminPage {
        private List<UserSummary> items = new ArrayList<>();
        private long total;
        private int page;
        private int size;
        private long activeCount;
        private long disabledCount;

        public List<UserSummary> getItems() {
            return items;
        }

        public void setItems(List<UserSummary> items) {
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
    }

    /** 返回给前端的用户信息（没有密码） */
    public static class UserSummary {
        private long id;
        private long orgId;
        private String username;
        private String displayName;
        private String phone;
        private String email;
        private String status;
        private boolean mustChangePassword;
        private Set<String> roleCodes;
        private Long createdBy;
        private String createdByName;
        private Instant createdAt;
        private Instant updatedAt;

        public static UserSummary from(UserAccount account) {
            UserSummary summary = new UserSummary();
            summary.id = account.getId();
            summary.orgId = account.getOrgId();
            summary.username = account.getUsername();
            summary.displayName = account.getDisplayName();
            summary.phone = account.getPhone();
            summary.email = account.getEmail();
            summary.status = account.getStatus();
            summary.mustChangePassword = account.isMustChangePassword();
            summary.roleCodes = new TreeSet<>(account.getRoleCodes());
            summary.createdBy = account.getCreatedBy();
            summary.createdAt = account.getCreatedAt();
            summary.updatedAt = account.getUpdatedAt();
            return summary;
        }

        public long getId() {
            return id;
        }

        public long getOrgId() {
            return orgId;
        }

        public String getUsername() {
            return username;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getPhone() {
            return phone;
        }

        public String getEmail() {
            return email;
        }

        public String getStatus() {
            return status;
        }

        public boolean isMustChangePassword() {
            return mustChangePassword;
        }

        public Set<String> getRoleCodes() {
            return roleCodes;
        }

        public Long getCreatedBy() {
            return createdBy;
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

        public Instant getUpdatedAt() {
            return updatedAt;
        }
    }

    /** 重置密码一次性结果（含明文临时密码） */
    public static class ResetPasswordResult {
        private long userId;
        private boolean reset;
        private String temporaryPassword;
        private String message;

        public long getUserId() {
            return userId;
        }

        public void setUserId(long userId) {
            this.userId = userId;
        }

        public boolean isReset() {
            return reset;
        }

        public void setReset(boolean reset) {
            this.reset = reset;
        }

        public String getTemporaryPassword() {
            return temporaryPassword;
        }

        public void setTemporaryPassword(String temporaryPassword) {
            this.temporaryPassword = temporaryPassword;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
