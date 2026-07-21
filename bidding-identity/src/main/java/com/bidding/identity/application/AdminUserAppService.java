package com.bidding.identity.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.Role;
import com.bidding.identity.domain.model.RoleCode;
import com.bidding.identity.domain.model.UserAccount;
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

/**
 * 平台管理员业务：查角色、查用户、创建用户、改角色、重置密码、停用/删除。
 */
@Service
public class AdminUserAppService {

    /** 用户名：字母数字下划线，3~64 位（降低注入与脏数据风险） */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,64}$");

    /** 中国大陆手机号：1 开头共 11 位数字 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private static final int MAX_DISPLAY_NAME_LENGTH = 128;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String STATUS_DELETED = "DELETED";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserAppService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
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
            items.add(UserSummary.from(account));
        }
        long total = userRepository.countSearch(kw);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public UserSummary getUser(long userId) {
        checkPlatformAdmin();
        return UserSummary.from(requireUser(userId));
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
                                  List<String> roleCodes) {
        checkPlatformAdmin();

        if (orgId <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "orgId 不合法");
        }
        if (!userRepository.existsOrgById(orgId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "组织不存在");
        }

        String safeUsername = normalizeUsername(username);
        if (userRepository.existsByUsername(safeUsername)) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }

        String safePhone = normalizePhone(phone);
        if (userRepository.existsByPhone(safePhone)) {
            throw new BizException(ErrorCode.CONFLICT, "手机号已存在");
        }

        String safeDisplayName = normalizeDisplayName(displayName);
        PasswordPolicy.validateNewPassword(password);
        Set<String> roles = checkAndNormalizeRoles(roleCodes);

        String passwordHash = passwordEncoder.encode(password);
        // 新建账号一律强制首次改密
        long userId = userRepository.insert(
                orgId, safeUsername, passwordHash, safeDisplayName, safePhone, STATUS_ACTIVE, true);
        userRepository.replaceRoles(userId, roles);

        return UserSummary.from(requireUser(userId));
    }

    /**
     * 设置用户角色（全量覆盖），并吊销对方旧会话。
     */
    @Transactional
    public UserSummary replaceRoles(long userId, List<String> roleCodes) {
        CurrentUser operator = checkPlatformAdmin();

        UserAccount target = requireUser(userId);
        Set<String> nextRoles = checkAndNormalizeRoles(roleCodes);

        boolean removingOwnAdmin = operator.getUserId() == userId
                && operator.isPlatformAdmin()
                && !nextRoles.contains(RoleCode.PLATFORM_ADMIN.name());
        boolean wasAdmin = target.getRoleCodes().contains(RoleCode.PLATFORM_ADMIN.name());
        boolean willBeAdmin = nextRoles.contains(RoleCode.PLATFORM_ADMIN.name());

        if ((removingOwnAdmin || (wasAdmin && !willBeAdmin))
                && userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }

        userRepository.replaceRoles(userId, nextRoles);
        // 角色变化后旧 Token 立即失效
        userRepository.bumpTokenVersion(userId);

        return UserSummary.from(requireUser(userId));
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
        CurrentUser operator = checkPlatformAdmin();
        UserAccount target = requireUser(userId);

        String next = normalizeActiveOrDisabled(status);
        if (STATUS_DELETED.equalsIgnoreCase(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "已删除账号不能改状态");
        }

        if (STATUS_DISABLED.equals(next)) {
            ensureNotLastPlatformAdmin(operator, target);
        }

        userRepository.updateStatus(userId, next);
        if (STATUS_DISABLED.equals(next)) {
            userRepository.bumpTokenVersion(userId);
        }
        return UserSummary.from(requireUser(userId));
    }

    /**
     * 逻辑删除：status=DELETED，并吊销会话。
     */
    @Transactional
    public void deleteUser(long userId) {
        CurrentUser operator = checkPlatformAdmin();
        UserAccount target = requireUser(userId);

        if (STATUS_DELETED.equalsIgnoreCase(target.getStatus())) {
            return;
        }
        ensureNotLastPlatformAdmin(operator, target);

        userRepository.updateStatus(userId, STATUS_DELETED);
        userRepository.bumpTokenVersion(userId);
    }

    private void ensureNotLastPlatformAdmin(CurrentUser operator, UserAccount target) {
        boolean isAdmin = target.getRoleCodes().contains(RoleCode.PLATFORM_ADMIN.name());
        if (!isAdmin) {
            return;
        }
        if (userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }
        // 不允许把自己停用/删掉导致无人可管（上面已拦最后一个；这里再拦「操作自己且只剩自己」已覆盖）
        if (operator.getUserId() == target.getId()
                && userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }
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

    /** 返回给前端的用户信息（没有密码） */
    public static class UserSummary {
        private long id;
        private long orgId;
        private String username;
        private String displayName;
        private String phone;
        private String status;
        private boolean mustChangePassword;
        private Set<String> roleCodes;

        public static UserSummary from(UserAccount account) {
            UserSummary summary = new UserSummary();
            summary.id = account.getId();
            summary.orgId = account.getOrgId();
            summary.username = account.getUsername();
            summary.displayName = account.getDisplayName();
            summary.phone = account.getPhone();
            summary.status = account.getStatus();
            summary.mustChangePassword = account.isMustChangePassword();
            summary.roleCodes = new TreeSet<>(account.getRoleCodes());
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

        public String getStatus() {
            return status;
        }

        public boolean isMustChangePassword() {
            return mustChangePassword;
        }

        public Set<String> getRoleCodes() {
            return roleCodes;
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
