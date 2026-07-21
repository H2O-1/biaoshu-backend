package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.identity.application.AdminOrgAppService;
import com.bidding.identity.application.AdminUserAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * 公司名称创建后不可修改；本期不提供删除。
 */
@RestController
@RequestMapping("/api/admin/orgs")
public class AdminOrgController {

    private final AdminOrgAppService adminOrgAppService;

    public AdminOrgController(AdminOrgAppService adminOrgAppService) {
        this.adminOrgAppService = adminOrgAppService;
    }

    @GetMapping
    public ApiResponse<PageResult<AdminOrgAppService.OrgSummary>> listOrgs(
            @RequestParam(required = false, defaultValue = "") String name,
            @RequestParam(required = false, defaultValue = "") String city,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(adminOrgAppService.listOrgs(name, city, status, page, size));
    }

    @PostMapping
    public ApiResponse<AdminOrgAppService.OrgSummary> createOrg(
            @Valid @RequestBody CreateOrgRequest request) {
        return ApiResponse.ok(adminOrgAppService.createOrg(request.getName(), request.getCity()));
    }

    @GetMapping("/{orgId}")
    public ApiResponse<AdminOrgAppService.OrgSummary> getOrg(@PathVariable("orgId") long orgId) {
        return ApiResponse.ok(adminOrgAppService.getOrg(orgId));
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

    @PutMapping("/{orgId}/status")
    public ApiResponse<AdminOrgAppService.OrgSummary> updateStatus(
            @PathVariable("orgId") long orgId,
            @Valid @RequestBody UpdateOrgStatusRequest request) {
        return ApiResponse.ok(adminOrgAppService.updateStatus(orgId, request.getStatus()));
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
