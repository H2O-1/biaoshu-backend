package com.bidding.identity.infrastructure.security;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.UserAccount;
import com.bidding.identity.domain.port.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 解析 JWT，并用数据库校验 token_version / 停用状态。
 * 若 must_change_password=true，仅允许改密与退出。
 * 仅 API（Servlet Web）装配；Worker 无 HTTP 请求，不加载。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService,
                                   UserRepository userRepository,
                                   ObjectMapper objectMapper) {
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
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
                        CurrentUser tokenUser = jwtTokenService.parse(token);
                        Optional<UserAccount> optionalAccount = userRepository.findById(tokenUser.getUserId());
                        if (optionalAccount.isEmpty()) {
                            // 账号已删除等：当作未登录
                        } else {
                            UserAccount account = optionalAccount.get();
                            if (!account.isEnabled()) {
                                // 停用：旧 Token 立即失效
                            } else if (account.getTokenVersion() != tokenUser.getTokenVersion()) {
                                // 密码重置 / 改密 / 改角色等：版本不一致则失效
                            } else {
                                CurrentUser user = new CurrentUser(
                                        account.getId(),
                                        account.getOrgId(),
                                        account.getUsername(),
                                        account.getRoleCodes(),
                                        account.getTokenVersion(),
                                        account.isMustChangePassword()
                                );

                                if (user.isMustChangePassword() && !isAllowedWhenMustChangePassword(request)) {
                                    writeJson(response, 403, ErrorCode.FORBIDDEN, "请先修改密码");
                                    return;
                                }

                                UserContext.set(user);
                                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                                for (String roleCode : user.getRoleCodes()) {
                                    authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
                                }
                                UsernamePasswordAuthenticationToken authentication =
                                        new UsernamePasswordAuthenticationToken(user, null, authorities);
                                SecurityContextHolder.getContext().setAuthentication(authentication);
                            }
                        }
                    } catch (Exception ignored) {
                        // token 坏了就当没登录
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isAllowedWhenMustChangePassword(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        // 强制改密期间：允许看自己是谁、改密、退出
        if ("GET".equalsIgnoreCase(method) && "/api/auth/me".equals(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/auth/change-password".equals(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/auth/logout".equals(path)) {
            return true;
        }
        return false;
    }

    private void writeJson(HttpServletResponse res,
                           int httpStatus,
                           ErrorCode errorCode,
                           String message) throws IOException {
        res.setStatus(httpStatus);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(res.getOutputStream(), ApiResponse.fail(errorCode.code(), message));
    }
}
