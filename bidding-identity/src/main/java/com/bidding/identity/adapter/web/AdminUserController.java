package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.identity.application.AdminUserAppService;
import com.bidding.identity.domain.model.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ApiResponse<AdminUserAppService.UserSummary> getUser(@PathVariable long userId) {
        return ApiResponse.ok(adminUserAppService.getUser(userId));
    }

    @PutMapping("/users/{userId}/roles")
    public ApiResponse<AdminUserAppService.UserSummary> replaceRoles(
            @PathVariable long userId,
            @Valid @RequestBody ReplaceRolesRequest request) {
        return ApiResponse.ok(adminUserAppService.replaceRoles(userId, request.roleCodes()));
    }

    public record ReplaceRolesRequest(@NotEmpty List<String> roleCodes) {
    }
}
