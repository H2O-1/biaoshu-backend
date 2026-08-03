package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.identity.application.AdminDashboardAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理员首页汇总。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardAppService adminDashboardAppService;

    public AdminDashboardController(AdminDashboardAppService adminDashboardAppService) {
        this.adminDashboardAppService = adminDashboardAppService;
    }

    @GetMapping
    public ApiResponse<AdminDashboardAppService.DashboardSummary> getDashboard() {
        return ApiResponse.ok(adminDashboardAppService.getDashboard());
    }
}
