package com.bidding.common.security;

import java.util.HashSet;
import java.util.Set;

/**
 * 当前登录用户（从 JWT 里解析出来）。
 * roleCodes：平台角色，例如 PLATFORM_ADMIN。
 */
public class CurrentUser {

    private final long userId;
    private final long orgId;
    private final String username;
    private final Set<String> roleCodes;

    public CurrentUser(long userId, long orgId, String username, Set<String> roleCodes) {
        this.userId = userId;
        this.orgId = orgId;
        this.username = username;
        // 复制一份，避免外面改到内部集合
        if (roleCodes == null) {
            this.roleCodes = new HashSet<>();
        } else {
            this.roleCodes = new HashSet<>(roleCodes);
        }
    }

    public long getUserId() {
        return userId;
    }

    public long getOrgId() {
        return orgId;
    }

    public String getUsername() {
        return username;
    }

    public Set<String> getRoleCodes() {
        return roleCodes;
    }

    public boolean hasRole(String roleCode) {
        return roleCodes.contains(roleCode);
    }

    /** 是不是平台管理员 */
    public boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }
}
