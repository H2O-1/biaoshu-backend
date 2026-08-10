package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.identity.application.AdminOrgAppService;
import com.bidding.identity.application.AdminUserAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/**
 * 组织管理接口（平台管理员）。
 * 编码创建后不可修改；逻辑删除仅超管。
 */
@RestController
@RequestMapping("/api/admin/orgs")
public class AdminOrgController {

    private final AdminOrgAppService adminOrgAppService;
    private final AdminUserAppService adminUserAppService;

    public AdminOrgController(AdminOrgAppService adminOrgAppService,
                              AdminUserAppService adminUserAppService) {
        this.adminOrgAppService = adminOrgAppService;
        this.adminUserAppService = adminUserAppService;
    }

    @GetMapping
    public ApiResponse<AdminOrgAppService.OrgPage> listOrgs(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "") String name,
            @RequestParam(required = false, defaultValue = "") String city,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        String effectiveKeyword = keyword == null || keyword.trim().isEmpty() ? name : keyword;
        return ApiResponse.ok(adminOrgAppService.listOrgs(effectiveKeyword, city, status, page, size));
    }

    @PostMapping
    public ApiResponse<AdminOrgAppService.OrgSummary> createOrg(
            @Valid @RequestBody CreateOrgRequest request) {
        return ApiResponse.ok(adminOrgAppService.createOrg(request.getName(), request.getCity()));
    }

    @GetMapping("/{orgId}")
    public ApiResponse<AdminOrgAppService.OrgDetail> getOrg(@PathVariable("orgId") long orgId) {
        return ApiResponse.ok(adminOrgAppService.getOrg(orgId));
    }

    /**
     * 编辑组织：公司名称、所在城市。
     */
    @PutMapping("/{orgId}")
    public ApiResponse<AdminOrgAppService.OrgSummary> updateOrg(
            @PathVariable("orgId") long orgId,
            @Valid @RequestBody UpdateOrgRequest request) {
        return ApiResponse.ok(adminOrgAppService.updateOrg(
                orgId, request.getName(), request.getCity()));
    }

    /**
     * 二级列表：组织下用户（排除已删除）。
     */
    @GetMapping("/{orgId}/users")
    public ApiResponse<PageResult<AdminUserAppService.UserSummary>> listOrgUsers(
            @PathVariable("orgId") long orgId,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminOrgAppService.listOrgUsers(orgId, keyword, page, size));
    }

    /**
     * 修改指定组织下招标方账号的负责人姓名、手机号、邮箱。
     */
    @PutMapping("/{orgId}/users/{userId}/profile")
    public ApiResponse<AdminUserAppService.UserSummary> updateTendererProfile(
            @PathVariable("orgId") long orgId,
            @PathVariable("userId") long userId,
            @Valid @RequestBody UpdateTendererProfileRequest request) {
        return ApiResponse.ok(adminUserAppService.updateTendererProfile(
                orgId,
                userId,
                request.getDisplayName(),
                request.getPhone(),
                request.getEmail()
        ));
    }

    @PutMapping("/{orgId}/status")
    public ApiResponse<AdminOrgAppService.OrgSummary> updateStatus(
            @PathVariable("orgId") long orgId,
            @Valid @RequestBody UpdateOrgStatusRequest request) {
        return ApiResponse.ok(adminOrgAppService.updateStatus(orgId, request.getStatus()));
    }

    /**
     * 逻辑删除组织（仅平台超级管理员）。
     */
    @DeleteMapping("/{orgId}")
    public ApiResponse<Void> deleteOrg(@PathVariable("orgId") long orgId) {
        adminOrgAppService.deleteOrg(orgId);
        return ApiResponse.ok();
    }

    public static class CreateOrgRequest {
        @NotBlank
        @Size(max = 128)
        private String name;

        @NotBlank
        @Size(max = 64)
        private String city;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    public static class UpdateOrgRequest {
        @NotBlank
        @Size(max = 128)
        private String name;

        @NotBlank
        @Size(max = 64)
        private String city;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    public static class UpdateTendererProfileRequest {
        @NotBlank
        @Size(max = 128)
        private String displayName;

        @NotBlank
        @Size(min = 11, max = 11)
        private String phone;

        @NotBlank
        @Size(max = 128)
        private String email;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    public static class UpdateOrgStatusRequest {
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
