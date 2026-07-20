package com.bidding.identity.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 平台级角色（挂在账号上，与项目成员角色分离）。
 */
public enum RoleCode {
    PLATFORM_ADMIN("平台管理员"),
    TENDERER("招标方"),
    BIDDER("投标方"),
    EXPERT("专家");

    private final String displayName;

    RoleCode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<RoleCode> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(r -> r.name().equals(normalized))
                .findFirst();
    }

    public static RoleCode parse(String raw) {
        return tryParse(raw)
                .orElseThrow(() -> new IllegalArgumentException("未知角色: " + raw));
    }
}
