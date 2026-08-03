package com.bidding.identity.domain.model;

/**
 * 平台角色：挂在账号上，如平台管理员、招标方、投标方、专家。
 */
public enum RoleCode {
    PLATFORM_ADMIN("平台管理员"),
    PLATFORM_SUPER_ADMIN("平台超级管理员"),
    TENDERER("招标方"),
    BIDDER("投标方"),
    EXPERT("专家");

    private final String displayName;

    RoleCode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 把字符串转成角色。转不了返回 null。
     */
    public static RoleCode tryParse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String name = raw.trim().toUpperCase();
        for (RoleCode role : RoleCode.values()) {
            if (role.name().equals(name)) {
                return role;
            }
        }
        return null;
    }
}
