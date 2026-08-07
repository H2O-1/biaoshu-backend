package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.ChecklistRecallDebugAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发期建清单召回调试接口。
 * 由 bidding.recall.debug-enabled 控制；发版关闭后本 Controller 不注册。
 */
@RestController
@ConditionalOnProperty(prefix = "bidding.recall", name = "debug-enabled", havingValue = "true")
public class RecallDebugController {

    private final ChecklistRecallDebugAppService checklistRecallDebugAppService;

    public RecallDebugController(ChecklistRecallDebugAppService checklistRecallDebugAppService) {
        this.checklistRecallDebugAppService = checklistRecallDebugAppService;
    }

    /**
     * 调试：按正式建清单链路，从数据库维度检索词自动召回项目招标文件片段。
     */
    @PostMapping("/api/projects/{projectId}/checklist-recall-debug")
    public ApiResponse<ChecklistRecallDebugAppService.DebugResult> checklistRecallDebug(
            @PathVariable("projectId") long projectId) {
        return ApiResponse.ok(checklistRecallDebugAppService.debugTenderRecall(projectId));
    }
}
