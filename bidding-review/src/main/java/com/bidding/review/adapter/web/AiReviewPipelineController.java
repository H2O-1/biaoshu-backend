package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.AiReviewPipelineAppService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一键 AI 审查（INDEX → 建清单 → REVIEW）。
 */
@RestController
public class AiReviewPipelineController {

    private final AiReviewPipelineAppService aiReviewPipelineAppService;

    public AiReviewPipelineController(AiReviewPipelineAppService aiReviewPipelineAppService) {
        this.aiReviewPipelineAppService = aiReviewPipelineAppService;
    }

    /**
     * 发起一键审查。返回总 jobId，前端轮询 GET /api/jobs/{jobId}。
     * 成功后 message / payloadJson 含 reviewId、checklistId。
     */
    @PostMapping("/api/projects/{projectId}/ai-reviews/run")
    public ApiResponse<RunResponse> run(@PathVariable("projectId") long projectId) {
        AiReviewPipelineAppService.StartResult started = aiReviewPipelineAppService.start(projectId);
        RunResponse resp = new RunResponse();
        resp.setJobId(started.getJobId());
        return ApiResponse.ok(resp);
    }

    public static class RunResponse {
        private long jobId;

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
        }
    }
}
