package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.RerankAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 开发期：召回 → 重排调试接口（不入库）。
 * 由 bidding.rerank.debug-enabled 控制。
 */
@RestController
@ConditionalOnProperty(prefix = "bidding.rerank", name = "debug-enabled", havingValue = "true")
public class RerankDebugController {

    private final RerankAppService rerankAppService;

    public RerankDebugController(RerankAppService rerankAppService) {
        this.rerankAppService = rerankAppService;
    }

    @PostMapping("/api/projects/{projectId}/rerank-debug")
    public ApiResponse<List<RerankAppService.RerankedHit>> rerankDebug(
            @PathVariable("projectId") long projectId,
            @Valid @RequestBody RerankDebugRequest request) {
        int recallTopK = request.getRecallTopK() == null ? 0 : request.getRecallTopK();
        int rerankTopN = request.getRerankTopN() == null ? 0 : request.getRerankTopN();
        List<RerankAppService.RerankedHit> hits = rerankAppService.recallThenRerank(
                projectId,
                request.getQuery(),
                recallTopK,
                rerankTopN,
                request.getSide(),
                request.getPackageIds(),
                request.getDocumentIds()
        );
        return ApiResponse.ok(hits);
    }

    public static class RerankDebugRequest {
        @NotBlank(message = "query 不能为空")
        private String query;
        private Integer recallTopK;
        private Integer rerankTopN;
        /** TENDER（默认）或 BID */
        private String side;
        private List<Long> packageIds;
        private List<Long> documentIds;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public Integer getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(Integer recallTopK) {
            this.recallTopK = recallTopK;
        }

        public Integer getRerankTopN() {
            return rerankTopN;
        }

        public void setRerankTopN(Integer rerankTopN) {
            this.rerankTopN = rerankTopN;
        }

        public String getSide() {
            return side;
        }

        public void setSide(String side) {
            this.side = side;
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
