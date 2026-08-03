package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.JobAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务 HTTP：发起文档 INDEX、查询任务状态。
 */
@RestController
public class JobController {

    private final JobAppService jobAppService;

    public JobController(JobAppService jobAppService) {
        this.jobAppService = jobAppService;
    }

    /**
     * 对标包下某文件发起索引任务（异步，立即返回 job）。
     */
    @PostMapping("/api/packages/{packageId}/files/{documentId}/index")
    public ApiResponse<JobAppService.JobSummary> startIndex(
            @PathVariable("packageId") long packageId,
            @PathVariable("documentId") long documentId) {
        return ApiResponse.ok(jobAppService.startDocumentIndex(packageId, documentId));
    }

    /**
     * 查询任务详情（本组织招标方）。
     */
    @GetMapping("/api/jobs/{jobId}")
    public ApiResponse<JobAppService.JobSummary> getJob(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(jobAppService.getJob(jobId));
    }
}
