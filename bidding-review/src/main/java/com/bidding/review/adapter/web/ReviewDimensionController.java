package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.ReviewDimensionAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审查维度只读（登录用户）：建清单勾选 / 联调查看 query。
 */
@RestController
public class ReviewDimensionController {

    private final ReviewDimensionAppService reviewDimensionAppService;

    public ReviewDimensionController(ReviewDimensionAppService reviewDimensionAppService) {
        this.reviewDimensionAppService = reviewDimensionAppService;
    }

    /**
     * 返回启用中的全局维度及检索 query。
     */
    @GetMapping("/api/review-dimensions")
    public ApiResponse<List<ReviewDimensionAppService.DimensionSummary>> listEnabled() {
        return ApiResponse.ok(reviewDimensionAppService.listEnabled());
    }
}
