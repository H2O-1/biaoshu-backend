package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.ChecklistRecallDebugAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发期建清单重排调试接口（不入库）。
 * 由 bidding.rerank.debug-enabled 控制。
 */
@RestController
@ConditionalOnProperty(prefix = "bidding.rerank", name = "debug-enabled", havingValue = "true")
public class RerankDebugController {

    private final ChecklistRecallDebugAppService checklistRecallDebugAppService;

    public RerankDebugController(ChecklistRecallDebugAppService checklistRecallDebugAppService) {
        this.checklistRecallDebugAppService = checklistRecallDebugAppService;
    }

    /**
     * 调试：按正式建清单链路，从数据库维度检索词自动召回并重排项目招标文件片段。
     */
    @PostMapping("/api/projects/{projectId}/checklist-rerank-debug")
    public ApiResponse<ChecklistRecallDebugAppService.RerankDebugResult> checklistRerankDebug(
            @PathVariable("projectId") long projectId) {
        return ApiResponse.ok(checklistRecallDebugAppService.debugTenderRerank(projectId));
    }
}
