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
import java.util.List;

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
                        List<SimpleGrantedAuthority> authorities = user.roleCodes().stream()
                                .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                                .toList();
                        var authentication = new UsernamePasswordAuthenticationToken(
                                user, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } catch (Exception ignored) {
                        // 无效 token 按未登录处理，由后续鉴权返回 401
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
