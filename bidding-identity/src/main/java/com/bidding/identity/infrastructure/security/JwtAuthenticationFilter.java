package com.bidding.identity.infrastructure.security;

import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 每个请求都会经过这里。
 * 如果带了 Bearer Token，就解析出当前用户。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                if (!token.isEmpty()) {
                    try {
                        CurrentUser user = jwtTokenService.parse(token);
                        UserContext.set(user);

                        // Spring Security 要求权限名是 ROLE_xxx
                        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                        for (String roleCode : user.getRoleCodes()) {
                            authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
                        }

                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(user, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } catch (Exception ignored) {
                        // token 坏了就当没登录
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束一定要清掉，防止线程复用时串号
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
