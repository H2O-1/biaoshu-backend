package com.bidding.common.security;

import java.util.Collections;
import java.util.Set;

/**
 * 请求上下文中的当前登录用户。平台级角色挂在账号上。
 */
public record CurrentUser(long userId, long orgId, String username, Set<String> roleCodes) {

    public CurrentUser {
        roleCodes = roleCodes == null ? Set.of() : Collections.unmodifiableSet(Set.copyOf(roleCodes));
    }

    public boolean hasRole(String roleCode) {
        return roleCodes.contains(roleCode);
    }

    public boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }
}
