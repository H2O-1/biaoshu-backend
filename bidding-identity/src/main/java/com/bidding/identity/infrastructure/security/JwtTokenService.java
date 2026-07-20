package com.bidding.identity.infrastructure.security;

import com.bidding.common.security.CurrentUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long expireSeconds;

    public JwtTokenService(
            @Value("${bidding.jwt.secret}") String secret,
            @Value("${bidding.jwt.expire-seconds:28800}") long expireSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("bidding.jwt.secret 至少 32 字节，请通过环境变量 JWT_SECRET 注入");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    public String createToken(CurrentUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .claim("orgId", user.orgId())
                .claim("username", user.username())
                .claim("roles", String.join(",", user.roleCodes()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(secretKey)
                .compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        long userId = Long.parseLong(claims.getSubject());
        long orgId = ((Number) claims.get("orgId")).longValue();
        String username = claims.get("username", String.class);
        String rolesRaw = claims.get("roles", String.class);
        Set<String> roles = rolesRaw == null || rolesRaw.isBlank()
                ? Set.of()
                : Arrays.stream(rolesRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new CurrentUser(userId, orgId, username, roles);
    }
}
