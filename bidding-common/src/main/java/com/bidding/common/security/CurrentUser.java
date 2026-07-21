package com.bidding.common.security;

import java.util.HashSet;
import java.util.Set;

/**
 * 当前登录用户（从 JWT 解析，并可能用库中的最新状态补齐）。
 */
public class CurrentUser {

    private final long userId;
    private final long orgId;
    private final String username;
    private final Set<String> roleCodes;
    private final int tokenVersion;
    private final boolean mustChangePassword;

    public CurrentUser(long userId,
                       long orgId,
                       String username,
                       Set<String> roleCodes,
                       int tokenVersion,
                       boolean mustChangePassword) {
        this.userId = userId;
        this.orgId = orgId;
        this.username = username;
        this.tokenVersion = tokenVersion;
        this.mustChangePassword = mustChangePassword;
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

    public int getTokenVersion() {
        return tokenVersion;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public boolean hasRole(String roleCode) {
        return roleCodes.contains(roleCode);
    }

    public boolean isPlatformAdmin() {
        return hasRole("PLATFORM_ADMIN");
    }

    /** 是否招标方（项目由招标方创建与管理） */
    public boolean isTenderer() {
        return hasRole("TENDERER");
    }
}
