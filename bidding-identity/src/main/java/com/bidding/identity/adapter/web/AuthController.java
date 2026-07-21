package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.identity.application.AuthAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录、当前用户、改密、退出。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthAppService authAppService;

    public AuthController(AuthAppService authAppService) {
        this.authAppService = authAppService;
    }

    @PostMapping("/login")
    public ApiResponse<AuthAppService.LoginResult> login(@Valid @RequestBody LoginRequest request) {
        AuthAppService.LoginResult result = authAppService.login(request.getUsername(), request.getPassword());
        return ApiResponse.ok(result);
    }

    /**
     * 当前登录用户（含组织名称）。
     * 刷新页面后可重新拉取，不必只靠本地缓存。
     */
    @GetMapping("/me")
    public ApiResponse<AuthAppService.MeResult> me() {
        return ApiResponse.ok(authAppService.currentUser());
    }

    /**
     * 修改密码（需登录）。
     * 两次输入一致由前端校验；成功后旧 Token 立即失效，需重新登录。
     */
    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authAppService.changePassword(request.getNewPassword());
        return ApiResponse.ok(null);
    }

    /** 退出登录：服务端吊销当前会话（token_version +1） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authAppService.logout();
        return ApiResponse.ok(null);
    }

    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class ChangePasswordRequest {
        @NotBlank
        @Size(min = 8, max = 72)
        private String newPassword;

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }
}
