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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminUserAppService {

    private final UserRepository userRepository;

    public AdminUserAppService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<Role> listRoles() {
        requirePlatformAdmin();
        return userRepository.listRoles();
    }

    @Transactional(readOnly = true)
    public PageResult<UserSummary> listUsers(String keyword, int page, int size) {
        requirePlatformAdmin();
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();
        List<UserSummary> items = userRepository.search(kw, offset, safeSize).stream()
                .map(UserSummary::from)
                .toList();
        long total = userRepository.countSearch(kw);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public UserSummary getUser(long userId) {
        requirePlatformAdmin();
        return userRepository.findById(userId)
                .map(UserSummary::from)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    @Transactional
    public UserSummary replaceRoles(long userId, List<String> roleCodes) {
        CurrentUser operator = requirePlatformAdmin();
        UserAccount target = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));

        Set<String> next = normalizeRoleCodes(roleCodes);

        boolean removingOwnAdmin = operator.userId() == userId
                && operator.isPlatformAdmin()
                && !next.contains(RoleCode.PLATFORM_ADMIN.name());
        boolean wasAdmin = target.roleCodes().contains(RoleCode.PLATFORM_ADMIN.name());
        boolean willBeAdmin = next.contains(RoleCode.PLATFORM_ADMIN.name());

        if ((removingOwnAdmin || (wasAdmin && !willBeAdmin))
                && userRepository.countUsersWithRole(RoleCode.PLATFORM_ADMIN.name()) <= 1) {
            throw new BizException(ErrorCode.CONFLICT, "系统至少保留一名平台管理员");
        }

        userRepository.replaceRoles(userId, next);
        return userRepository.findById(userId)
                .map(UserSummary::from)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private static Set<String> normalizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少指定一个角色");
        }
        Set<String> next = new LinkedHashSet<>();
        for (String raw : roleCodes) {
            RoleCode code = RoleCode.tryParse(raw)
                    .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "未知角色: " + raw));
            next.add(code.name());
        }
        return next;
    }

    private CurrentUser requirePlatformAdmin() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isPlatformAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可操作");
        }
        return user;
    }

    public record UserSummary(
            long id,
            long orgId,
            String username,
            String displayName,
            String status,
            Set<String> roleCodes
    ) {
        static UserSummary from(UserAccount account) {
            return new UserSummary(
                    account.id(),
                    account.orgId(),
                    account.username(),
                    account.displayName(),
                    account.status(),
                    account.roleCodes().stream().sorted().collect(Collectors.toCollection(LinkedHashSet::new))
            );
        }
    }
}
