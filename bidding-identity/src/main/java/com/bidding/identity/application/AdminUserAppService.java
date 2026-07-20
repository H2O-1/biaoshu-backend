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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 平台管理员业务：查角色、查用户、创建用户、改用户角色。
 */
@Service
public class AdminUserAppService {

    /** 用户名：字母数字下划线，3~64 位（降低注入与脏数据风险） */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,64}$");

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;

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
        Optional<UserAccount> optionalUser = userRepository.findById(userId);
        if (optionalUser.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return UserSummary.from(optionalUser.get());
    }

    /**
     * 创建用户：指定组织、初始角色；密码至少 8 位。
     * SQL 使用参数绑定；用户名等字段再做格式校验，降低注入与脏数据风险。
     */
    @Transactional
    public UserSummary createUser(long orgId,
                                  String username,
                                  String password,
                                  String displayName,
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

        String safeDisplayName = normalizeDisplayName(displayName);
        checkPassword(password);
        Set<String> roles = checkAndNormalizeRoles(roleCodes);

        String passwordHash = passwordEncoder.encode(password);
        long userId = userRepository.insert(orgId, safeUsername, passwordHash, safeDisplayName, "ACTIVE");
        userRepository.replaceRoles(userId, roles);

        Optional<UserAccount> created = userRepository.findById(userId);
        if (created.isEmpty()) {
            throw new BizException(ErrorCode.INTERNAL, "创建用户失败");
        }
        return UserSummary.from(created.get());
    }

    /**
     * 设置用户角色（全量覆盖：最终就是这次传来的列表）。
     * 系统至少要留下一名平台管理员。
     */
    @Transactional
    public UserSummary replaceRoles(long userId, List<String> roleCodes) {
        CurrentUser operator = checkPlatformAdmin();

        Optional<UserAccount> optionalTarget = userRepository.findById(userId);
        if (optionalTarget.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        UserAccount target = optionalTarget.get();

        Set<String> nextRoles = checkAndNormalizeRoles(roleCodes);

        boolean removingOwnAdmin = operator.getUserId() == userId
                && operator.isPlatformAdmin()
                && !nextRoles.contains(RoleCode.PLATFORM_ADMIN.name());
        boolean wasAdmin = target.getRoleCodes().contains(RoleCode.PLATFORM_ADMIN.name());
        boolean willBeAdmin = nextRoles.contains(RoleCode.PLATFORM_ADMIN.name());

        // 不能删掉系统里最后一个平台管理员
        if ((removingOwnAdmin || (wasAdmin && !willBeAdmin))
                && userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }

        userRepository.replaceRoles(userId, nextRoles);

        Optional<UserAccount> afterUpdate = userRepository.findById(userId);
        if (afterUpdate.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return UserSummary.from(afterUpdate.get());
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
        // 拒绝控制字符，避免奇怪注入/展示问题
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "显示名包含非法字符");
            }
        }
        return trimmed;
    }

    private void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
    }

    /** 检查角色码是否合法，并转成 Set */
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

    /** 当前登录人必须是平台管理员 */
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
        private String status;
        private Set<String> roleCodes;

        public static UserSummary from(UserAccount account) {
            UserSummary summary = new UserSummary();
            summary.id = account.getId();
            summary.orgId = account.getOrgId();
            summary.username = account.getUsername();
            summary.displayName = account.getDisplayName();
            summary.status = account.getStatus();
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

        public String getStatus() {
            return status;
        }

        public Set<String> getRoleCodes() {
            return roleCodes;
        }
    }
}
