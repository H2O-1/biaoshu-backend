package com.bidding.review.application;

import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewDimensionPort;
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
 * BUILD_CHECKLIST 任务执行：管线抽取 → 落库 → 回写状态。
 */
@Service
public class BuildChecklistJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(BuildChecklistJobExecutor.class);

    private final ChecklistAppService checklistAppService;
    private final JobPort jobPort;
    private final RequirementChecklistPort checklistPort;
    private final ReviewDimensionPort reviewDimensionPort;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;
    private final ChecklistBuildPipeline pipeline;
    private final ObjectMapper objectMapper;

    public BuildChecklistJobExecutor(
            ChecklistAppService checklistAppService,
            JobPort jobPort,
            RequirementChecklistPort checklistPort,
            ReviewDimensionPort reviewDimensionPort,
            PackageLookupPort packageLookupPort,
            DocumentAccessPort documentAccessPort,
            ChecklistBuildPipeline pipeline,
            ObjectMapper objectMapper) {
        this.checklistAppService = checklistAppService;
        this.jobPort = jobPort;
        this.checklistPort = checklistPort;
        this.reviewDimensionPort = reviewDimensionPort;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void execute(JobPort.JobRef job) {
        long jobId = job.getId();
        Long checklistId = null;
        long tAll = System.currentTimeMillis();
        try {
            Payload payload = parsePayload(job.getPayloadJson());
            if (payload.checklistId == null || payload.projectId == null) {
                jobPort.markFailed(jobId, "BUILD_CHECKLIST payload 缺少 checklistId/projectId");
                return;
            }
            checklistId = payload.checklistId;
            long orgId = job.getOrgId();
            long projectId = payload.projectId;

            jobPort.markRunningProgress(jobId, 5, "加载清单与维度");
            RequirementChecklistPort.ChecklistDetail head =
                    checklistPort.findDetail(orgId, projectId, checklistId);
            if (head == null) {
                jobPort.markFailed(jobId, "清单不存在: " + checklistId);
                return;
            }

            List<String> codes = parseStringArray(head.getDimensionCodesJson());
            List<Long> documentIds = parseLongArray(head.getTenderDocumentIdsJson());
            List<ReviewDimensionPort.Dimension> dims = loadDimensions(codes);
            if (dims.isEmpty()) {
                failAll(jobId, checklistId, "无有效审查维度");
                return;
            }

            // 再校验文档仍为 INDEX SUCCESS
            for (int i = 0; i < documentIds.size(); i++) {
                DocumentAccessPort.DocumentRef doc = documentAccessPort.findActiveById(documentIds.get(i));
                if (doc == null || doc.getOrgId() != orgId) {
                    failAll(jobId, checklistId, "文档不存在: " + documentIds.get(i));
                    return;
                }
                if (!DocumentAccessPort.INDEX_SUCCESS.equals(doc.getIndexStatus())) {
                    failAll(jobId, checklistId, "文档索引状态无效: " + documentIds.get(i));
                    return;
                }
            }

            long packageId;
            if (payload.packageId != null) {
                packageId = payload.packageId;
            } else {
                PackageLookupPort.PackageRef tender =
                        packageLookupPort.findActiveByProjectIdAndType(projectId, ChecklistAppService.PACKAGE_TYPE_TENDER);
                if (tender == null) {
                    failAll(jobId, checklistId, "项目无招标包");
                    return;
                }
                packageId = tender.getId();
            }
            List<Long> packageIds = new ArrayList<>();
            packageIds.add(packageId);

            final long cid = checklistId;
            ChecklistBuildPipeline.PipelineResult pipelineResult = pipeline.build(
                    orgId,
                    projectId,
                    dims,
                    packageIds,
                    documentIds,
                    head.getRecallTopK(),
                    head.getRerankTopN(),
                    (progress, message) -> jobPort.markRunningProgress(jobId, progress, message)
            );
            List<RequirementChecklistPort.ItemDraft> items = pipelineResult.getItems();

            checklistPort.insertItems(cid, items);
            checklistPort.markSuccess(cid, pipelineResult.getLlmRawJson());
            jobPort.markSuccess(jobId, "建清单完成 items=" + items.size());
            log.info("BUILD_CHECKLIST 成功 jobId={} checklistId={} items={} costMs={}",
                    jobId, cid, items.size(), System.currentTimeMillis() - tAll);
        } catch (Exception ex) {
            log.error("BUILD_CHECKLIST 失败 jobId={}", jobId, ex);
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (checklistId != null) {
                checklistPort.markFailed(checklistId, msg);
            }
            jobPort.markFailed(jobId, msg);
        }
    }

    public boolean claimAndRunOnce(String workerId) {
        Optional<JobPort.JobRef> optional = checklistAppService.claimNextBuildChecklist(workerId);
        if (optional.isEmpty()) {
            return false;
        }
        execute(optional.get());
        return true;
    }

    private void failAll(long jobId, long checklistId, String message) {
        checklistPort.markFailed(checklistId, message);
        jobPort.markFailed(jobId, message);
    }

    private List<ReviewDimensionPort.Dimension> loadDimensions(List<String> codes) {
        List<ReviewDimensionPort.Dimension> all = reviewDimensionPort.listAll(true);
        List<ReviewDimensionPort.Dimension> result = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            for (int j = 0; j < all.size(); j++) {
                if (code.equals(all.get(j).getCode())) {
                    result.add(all.get(j));
                    break;
                }
            }
        }
        return result;
    }

    private Payload parsePayload(String payloadJson) throws Exception {
        Payload p = new Payload();
        if (payloadJson == null || payloadJson.isBlank()) {
            return p;
        }
        JsonNode root = objectMapper.readTree(payloadJson);
        if (root.has("checklistId") && !root.get("checklistId").isNull()) {
            p.checklistId = root.get("checklistId").asLong();
        }
        if (root.has("projectId") && !root.get("projectId").isNull()) {
            p.projectId = root.get("projectId").asLong();
        }
        if (root.has("packageId") && !root.get("packageId").isNull()) {
            p.packageId = root.get("packageId").asLong();
        }
        return p;
    }

    private List<String> parseStringArray(String json) throws Exception {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        JsonNode node = objectMapper.readTree(json);
        if (!node.isArray()) {
            return result;
        }
        for (int i = 0; i < node.size(); i++) {
            String v = node.get(i).asText();
            if (v != null && !v.isBlank()) {
                result.add(v.trim());
            }
        }
        return result;
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
        private Long checklistId;
        private Long projectId;
        private Long packageId;
    }
}
