package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.RecallAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开发期向量召回调试接口。
 * 由 bidding.recall.debug-enabled 控制；发版关闭后本 Controller 不注册。
 */
@RestController
@ConditionalOnProperty(prefix = "bidding.recall", name = "debug-enabled", havingValue = "true")
public class RecallDebugController {

    private final RecallAppService recallAppService;

    public RecallDebugController(RecallAppService recallAppService) {
        this.recallAppService = recallAppService;
    }

    /**
     * 调试：项目下向量召回（不入库）。
     */
    @PostMapping("/api/projects/{projectId}/recall-debug")
    public ApiResponse<List<RecallAppService.HitSummary>> recallDebug(
            @PathVariable("projectId") long projectId,
            @Valid @RequestBody RecallDebugRequest request) {
        int topK = request.getTopK() == null ? 0 : request.getTopK();
        List<RecallAppService.HitSummary> hits = recallAppService.recall(
                projectId,
                request.getQuery(),
                topK,
                request.getPackageIds(),
                request.getDocumentIds()
        );
        return ApiResponse.ok(hits);
    }

    public static class RecallDebugRequest {
        @NotBlank(message = "query 不能为空")
        private String query;
        private Integer topK;
        private List<Long> packageIds;
        private List<Long> documentIds;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Integer getTopK() {
            return topK;
        }

        public void setTopK(Integer topK) {
            this.topK = topK;
        }

        public List<Long> getPackageIds() {
            return packageIds;
        }

        public void setPackageIds(List<Long> packageIds) {
            this.packageIds = packageIds;
        }

        public List<Long> getDocumentIds() {
            return documentIds;
        }

        public void setDocumentIds(List<Long> documentIds) {
            this.documentIds = documentIds;
        }
    }
}
