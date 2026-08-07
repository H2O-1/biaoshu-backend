package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.review.application.JobAppService;
import com.bidding.review.application.JobSseService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 异步任务 HTTP：发起文档 INDEX、查询任务状态、SSE 进度订阅。
 */
@RestController
public class JobController {

    private final JobAppService jobAppService;
    private final JobSseService jobSseService;

    public JobController(JobAppService jobAppService, JobSseService jobSseService) {
        this.jobAppService = jobAppService;
        this.jobSseService = jobSseService;
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
     * <p>
     * <b>短轮询接口（保留）</b>：创建任务后前端可定时 GET 本接口查看
     * {@code status}/{@code progress}/{@code message}，直至 SUCCESS/FAILED。
     * 也可用 {@link #streamJob(long)} SSE 收推送；断线或刷新页面仍应用本接口拉快照兜底。
     */
    @GetMapping("/api/jobs/{jobId}")
    public ApiResponse<JobAppService.JobSummary> getJob(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(jobAppService.getJob(jobId));
    }

    /**
     * 任务进度 SSE（推荐新前端使用；不替代短轮询）。
     * <p>
     * 鉴权：与其它接口相同，请求头 {@code Authorization: Bearer &lt;accessToken&gt;}。
     * 原生 EventSource 无法自定义 Header，请用 fetch + ReadableStream 解析，或后续 ticket 方案。
     * 响应为 {@code text/event-stream}，事件体为 JobSummary JSON（非 ApiResponse 包装）。
     */
    @GetMapping(path = "/api/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable("jobId") long jobId) {
        return jobSseService.openStream(jobId);
    }
}
