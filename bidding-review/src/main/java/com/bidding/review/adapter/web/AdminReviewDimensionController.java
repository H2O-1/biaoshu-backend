package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.ReviewDimensionAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理员维护全局审查维度与检索 query。
 * 路径在 /api/admin/**，由 Security 校验 PLATFORM_ADMIN。
 */
@RestController
@RequestMapping("/api/admin/review-dimensions")
public class AdminReviewDimensionController {

    private final ReviewDimensionAppService reviewDimensionAppService;

    public AdminReviewDimensionController(ReviewDimensionAppService reviewDimensionAppService) {
        this.reviewDimensionAppService = reviewDimensionAppService;
    }

    @GetMapping
    public ApiResponse<List<ReviewDimensionAppService.DimensionSummary>> listAll() {
        return ApiResponse.ok(reviewDimensionAppService.listAllForAdmin());
    }

    /**
     * 按 id 更新维度；queries 全量覆盖（传空数组表示清空检索词）。
     */
    @PutMapping("/{id}")
    public ApiResponse<ReviewDimensionAppService.DimensionSummary> update(
            @PathVariable("id") long id,
            @Valid @RequestBody UpdateDimensionRequest request) {
        return ApiResponse.ok(reviewDimensionAppService.update(
                id,
                request.getName(),
                request.getDefaultSelected(),
                request.getEnabled(),
                request.getSortOrder(),
                request.getQueries()
        ));
    }

    public static class UpdateDimensionRequest {
        @NotBlank(message = "name 不能为空")
        @Size(max = 128)
        private String name;
        private Boolean defaultSelected;
        private Boolean enabled;
        private Integer sortOrder;
        @NotNull(message = "queries 不能为 null")
        private List<@Size(max = 512) String> queries;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Boolean getDefaultSelected() {
            return defaultSelected;
        }

        public void setDefaultSelected(Boolean defaultSelected) {
            this.defaultSelected = defaultSelected;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(Integer sortOrder) {
            this.sortOrder = sortOrder;
        }

        public List<String> getQueries() {
            return queries;
        }

        public void setQueries(List<String> queries) {
            this.queries = queries;
        }
    }
}
