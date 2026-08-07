package com.bidding.review.application;

import com.bidding.common.port.JobPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * REVIEW 任务执行：管线对照 → 落库 → 回写状态。
 */
@Service
public class ReviewJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobExecutor.class);

    private final ReviewAppService reviewAppService;
    private final JobPort jobPort;
    private final ReviewPort reviewPort;
    private final RequirementChecklistPort checklistPort;
    private final ReviewComparePipeline pipeline;
    private final ObjectMapper objectMapper;

    public ReviewJobExecutor(
            ReviewAppService reviewAppService,
            JobPort jobPort,
            ReviewPort reviewPort,
            RequirementChecklistPort checklistPort,
            ReviewComparePipeline pipeline,
            ObjectMapper objectMapper) {
        this.reviewAppService = reviewAppService;
        this.jobPort = jobPort;
        this.reviewPort = reviewPort;
        this.checklistPort = checklistPort;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void execute(JobPort.JobRef job) {
        long jobId = job.getId();
        Long reviewId = null;
        long tAll = System.currentTimeMillis();
        try {
            Payload payload = parsePayload(job.getPayloadJson());
            if (payload.reviewId == null || payload.projectId == null) {
                jobPort.markFailed(jobId, "REVIEW payload 缺少 reviewId/projectId");
                return;
            }
            reviewId = payload.reviewId;
            long orgId = job.getOrgId();
            long projectId = payload.projectId;

            jobPort.markRunningProgress(jobId, 5, "加载审查与清单");
            ReviewPort.ReviewDetail review = reviewPort.findDetail(orgId, projectId, reviewId);
            if (review == null) {
                jobPort.markFailed(jobId, "审查不存在: " + reviewId);
                return;
            }
            if (ReviewPort.STATUS_SUCCESS.equals(review.getStatus())
                    || ReviewPort.STATUS_FAILED.equals(review.getStatus())) {
                log.info("审查已终态，跳过 reviewId={} status={}", reviewId, review.getStatus());
                return;
            }

            long checklistId = review.getChecklistId();
            RequirementChecklistPort.ChecklistDetail checklist =
                    checklistPort.findDetail(orgId, projectId, checklistId);
            if (checklist == null) {
                failAll(jobId, reviewId, "清单不存在: " + checklistId);
                return;
            }
            List<RequirementChecklistPort.ItemView> items = checklist.getItems();
            if (items == null || items.isEmpty()) {
                failAll(jobId, reviewId, "清单无条目");
                return;
            }

            List<Long> bidPackageIds = parseLongArray(review.getPackageIdsJson());
            if (bidPackageIds.isEmpty()) {
                failAll(jobId, reviewId, "投标包范围为空");
                return;
            }

            final long rid = reviewId;
            ReviewComparePipeline.PipelineResult result = pipeline.run(
                    orgId,
                    projectId,
                    items,
                    bidPackageIds,
                    review.getRecallTopK(),
                    review.getRerankTopN(),
                    (progress, message) -> jobPort.markRunningProgress(jobId, progress, message)
            );

            jobPort.markRunningProgress(jobId, 92, "写入审查结果");
            reviewPort.insertItems(rid, result.getItems());
            // 项目级不再写通过/不通过；overall_verdict 置空
            reviewPort.markSuccess(rid, null);
            jobPort.markSuccess(jobId, "审查完成 items=" + result.getItems().size());
            log.info("REVIEW 成功 jobId={} reviewId={} items={} costMs={}",
                    jobId, rid, result.getItems().size(), System.currentTimeMillis() - tAll);
        } catch (Exception ex) {
            log.error("REVIEW 失败 jobId={}", jobId, ex);
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (reviewId != null) {
                reviewPort.markFailed(reviewId, msg);
            }
            jobPort.markFailed(jobId, msg);
        }
    }

    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional = reviewAppService.claimNextReview(workerId);
        if (optional.isEmpty()) {
            return false;
        }
        execute(optional.get());
        return true;
    }

    private void failAll(long jobId, long reviewId, String message) {
        reviewPort.markFailed(reviewId, message);
        jobPort.markFailed(jobId, message);
    }

    private Payload parsePayload(String payloadJson) throws Exception {
        Payload p = new Payload();
        if (payloadJson == null || payloadJson.isBlank()) {
            return p;
        }
        JsonNode root = objectMapper.readTree(payloadJson);
        if (root.has("reviewId") && !root.get("reviewId").isNull()) {
            p.reviewId = root.get("reviewId").asLong();
        }
        if (root.has("projectId") && !root.get("projectId").isNull()) {
            p.projectId = root.get("projectId").asLong();
        }
        if (root.has("checklistId") && !root.get("checklistId").isNull()) {
            p.checklistId = root.get("checklistId").asLong();
        }
        return p;
    }

    private List<Long> parseLongArray(String json) throws Exception {
        List<Long> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode node = objectMapper.readTree(json);
        if (!node.isArray()) {
            return result;
        }
        for (int i = 0; i < node.size(); i++) {
            result.add(node.get(i).asLong());
        }
        return result;
    }

    private static class Payload {
        private Long reviewId;
        private Long projectId;
        private Long checklistId;
    }
}
