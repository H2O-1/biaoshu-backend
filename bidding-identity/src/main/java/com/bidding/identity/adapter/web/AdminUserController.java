package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.identity.application.AdminUserAppService;
import com.bidding.identity.domain.model.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理接口（只有平台管理员能调用）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminUserController {

    private final AdminUserAppService adminUserAppService;

    public AdminUserController(AdminUserAppService adminUserAppService) {
        this.adminUserAppService = adminUserAppService;
    }

    @GetMapping("/roles")
    public ApiResponse<List<Role>> listRoles() {
        return ApiResponse.ok(adminUserAppService.listRoles());
    }

    @GetMapping("/users")
    public ApiResponse<PageResult<AdminUserAppService.UserSummary>> listUsers(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminUserAppService.listUsers(keyword, page, size));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<AdminUserAppService.UserSummary> getUser(@PathVariable("userId") long userId) {
        return ApiResponse.ok(adminUserAppService.getUser(userId));
    }

    @PostMapping("/users")
    public ApiResponse<AdminUserAppService.UserSummary> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(adminUserAppService.createUser(
                request.getOrgId(),
                request.getUsername(),
                request.getPassword(),
                request.getDisplayName(),
                request.getRoleCodes()
        ));
    }

    @PutMapping("/users/{userId}/roles")
    public ApiResponse<AdminUserAppService.UserSummary> replaceRoles(
            @PathVariable("userId") long userId,
            @Valid @RequestBody ReplaceRolesRequest request) {
        return ApiResponse.ok(adminUserAppService.replaceRoles(userId, request.getRoleCodes()));
    }

    /**
     * 重置密码：返回临时密码，仅本次可见。
     */
    @PostMapping("/users/{userId}/reset-password")
    public ApiResponse<AdminUserAppService.ResetPasswordResult> resetPassword(
            @PathVariable("userId") long userId) {
        return ApiResponse.ok(adminUserAppService.resetPassword(userId));
    }

    /** 启用 / 停用账号 */
    @PutMapping("/users/{userId}/status")
    public ApiResponse<AdminUserAppService.UserSummary> updateStatus(
            @PathVariable("userId") long userId,
            @Valid @RequestBody UpdateStatusRequest request) {
        return ApiResponse.ok(adminUserAppService.updateStatus(userId, request.getStatus()));
    }

    /** 逻辑删除账号 */
    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable("userId") long userId) {
        adminUserAppService.deleteUser(userId);
        return ApiResponse.ok(null);
    }

    public static class CreateUserRequest {
        @NotNull
        @Positive
        private Long orgId;

        @NotBlank
        @Size(min = 3, max = 64)
        private String username;

        @NotBlank
        @Size(min = 8, max = 72)
        private String password;

        @NotBlank
        @Size(max = 128)
        private String displayName;

        @NotEmpty
        private List<String> roleCodes;

        public Long getOrgId() {
            return orgId;
        }

        public void setOrgId(Long orgId) {
            this.orgId = orgId;
        }

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

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public List<String> getRoleCodes() {
            return roleCodes;
        }

        public void setRoleCodes(List<String> roleCodes) {
            this.roleCodes = roleCodes;
        }
    }

    public static class ReplaceRolesRequest {
        @NotEmpty
        private List<String> roleCodes;

        public List<String> getRoleCodes() {
            return roleCodes;
        }

        public void setRoleCodes(List<String> roleCodes) {
            this.roleCodes = roleCodes;
        }
    }

    public static class UpdateStatusRequest {
        @NotBlank
        private String status;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
