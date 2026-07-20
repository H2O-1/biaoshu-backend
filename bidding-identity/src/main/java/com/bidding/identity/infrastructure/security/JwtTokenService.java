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
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * 负责生成和解析 JWT（登录令牌）。
 */
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

    /** 登录成功后生成 token */
    public String createToken(CurrentUser user) {
        Instant now = Instant.now();

        // 把多个角色用逗号拼成一个字符串存进 token
        StringBuilder rolesBuilder = new StringBuilder();
        boolean first = true;
        for (String role : user.getRoleCodes()) {
            if (!first) {
                rolesBuilder.append(",");
            }
            rolesBuilder.append(role);
            first = false;
        }

        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("orgId", user.getOrgId())
                .claim("username", user.getUsername())
                .claim("roles", rolesBuilder.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(secretKey)
                .compact();
    }

    /** 解析 token，得到当前用户信息 */
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

        Set<String> roles = new HashSet<>();
        if (rolesRaw != null && !rolesRaw.trim().isEmpty()) {
            String[] parts = rolesRaw.split(",");
            for (String part : parts) {
                String role = part.trim();
                if (!role.isEmpty()) {
                    roles.add(role);
                }
            }
        }

        return new CurrentUser(userId, orgId, username, roles);
    }
}
