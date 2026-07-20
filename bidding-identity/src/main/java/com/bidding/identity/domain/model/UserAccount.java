package com.bidding.identity.domain.model;

import java.time.Instant;
import java.util.Set;

public record UserAccount(
        long id,
        long orgId,
        String username,
        String passwordHash,
        String displayName,
        String status,
        Instant createdAt,
        Set<String> roleCodes
) {
    public boolean enabled() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
