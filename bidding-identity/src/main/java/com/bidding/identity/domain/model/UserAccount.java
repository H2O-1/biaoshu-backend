package com.bidding.identity.domain.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** 用户账号 */
public class UserAccount {

    private final long id;
    private final long orgId;
    private final String username;
    private final String passwordHash;
    private final String displayName;
    private final String phone;
    private final String status;
    private final boolean mustChangePassword;
    private final int tokenVersion;
    private final Instant createdAt;
    private final Set<String> roleCodes;

    public UserAccount(long id,
                       long orgId,
                       String username,
                       String passwordHash,
                       String displayName,
                       String phone,
                       String status,
                       boolean mustChangePassword,
                       int tokenVersion,
                       Instant createdAt,
                       Set<String> roleCodes) {
        this.id = id;
        this.orgId = orgId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.phone = phone;
        this.status = status;
        this.mustChangePassword = mustChangePassword;
        this.tokenVersion = tokenVersion;
        this.createdAt = createdAt;
        if (roleCodes == null) {
            this.roleCodes = new HashSet<>();
        } else {
            this.roleCodes = new HashSet<>(roleCodes);
        }
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

    public String getPasswordHash() {
        return passwordHash;
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

    public int getTokenVersion() {
        return tokenVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<String> getRoleCodes() {
        return roleCodes;
    }

    /** 是否允许登录（仅 ACTIVE） */
    public boolean isEnabled() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
