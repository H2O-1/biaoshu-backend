package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 建清单：创建异步任务、查询清单。
 */
@Service
public class ChecklistAppService {

    public static final String PACKAGE_TYPE_TENDER = "TENDER";

    private final RequirementChecklistPort checklistPort;
    private final ReviewDimensionPort reviewDimensionPort;
    private final ProjectLookupPort projectLookupPort;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;
    private final JobPort jobPort;
    private final ObjectMapper objectMapper;
    private final int recallTopK;
    private final int rerankTopN;
    private final String promptVersion;

    public ChecklistAppService(
            RequirementChecklistPort checklistPort,
            ReviewDimensionPort reviewDimensionPort,
            ProjectLookupPort projectLookupPort,
            PackageLookupPort packageLookupPort,
            DocumentAccessPort documentAccessPort,
            JobPort jobPort,
            ObjectMapper objectMapper,
            @Value("${bidding.checklist.recall-top-k:${bidding.recall.default-top-k:40}}") int recallTopK,
            @Value("${bidding.checklist.rerank-top-n:${bidding.rerank.tender-top-n:8}}") int rerankTopN,
            @Value("${bidding.llm.prompt-version:extract-v2}") String promptVersion) {
        this.checklistPort = checklistPort;
        this.reviewDimensionPort = reviewDimensionPort;
        this.projectLookupPort = projectLookupPort;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
        this.jobPort = jobPort;
        this.objectMapper = objectMapper;
        this.recallTopK = recallTopK < 1 ? 40 : recallTopK;
        this.rerankTopN = rerankTopN < 1 ? 8 : rerankTopN;
        this.promptVersion = promptVersion == null || promptVersion.isBlank() ? "extract-v2" : promptVersion.trim();
    }

    /**
     * 创建建清单任务：写 BUILDING 清单 + PENDING job。
     */
    @Transactional
    public CreateResult create(long projectId, List<String> dimensionCodes, List<Long> documentIds) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());
        return createInternal(
                operator.getOrgId(), projectId, operator.getUserId(), dimensionCodes, documentIds);
    }

    /**
     * 无鉴权创建（供一键审查 Worker 调用；调用方须已校验组织与项目）。
     */
    @Transactional
    public CreateResult createInternal(long orgId,
                                       long projectId,
                                       long createdBy,
                                       List<String> dimensionCodes,
                                       List<Long> documentIds) {
        PackageLookupPort.PackageRef tenderPkg =
                packageLookupPort.findActiveByProjectIdAndType(projectId, PACKAGE_TYPE_TENDER);
        if (tenderPkg == null || tenderPkg.getOrgId() != orgId) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目未绑定招标包，无法建清单");
        }

        List<Long> resolvedDocIds = resolveTenderDocumentIds(
                orgId, projectId, tenderPkg.getId(), documentIds);
        if (resolvedDocIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有已索引成功的招标文档，请先完成 INDEX");
        }

        List<String> resolvedCodes = resolveDimensionCodes(dimensionCodes);
        if (resolvedCodes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未选择有效审查维度");
        }

        int version = checklistPort.nextVersion(orgId, projectId);
        String dimJson = toJsonStringArray(resolvedCodes);
        String docJson = toJsonLongArray(resolvedDocIds);
        long checklistId = checklistPort.createBuilding(
                orgId,
                projectId,
                version,
                dimJson,
                docJson,
                promptVersion,
                recallTopK,
                rerankTopN,
                createdBy
        );

        String payload = "{\"checklistId\":" + checklistId
                + ",\"projectId\":" + projectId
                + ",\"packageId\":" + tenderPkg.getId()
                + "}";
        long jobId = jobPort.insert(
                orgId,
                projectId,
                JobPort.TYPE_BUILD_CHECKLIST,
                JobPort.STATUS_PENDING,
                payload,
                createdBy
        );

        CreateResult result = new CreateResult();
        result.setChecklistId(checklistId);
        result.setJobId(jobId);
        result.setVersion(version);
        return result;
    }

    @Transactional(readOnly = true)
    public List<RequirementChecklistPort.ChecklistSummary> list(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());
        return checklistPort.listByProject(operator.getOrgId(), projectId);
    }

    @Transactional(readOnly = true)
    public RequirementChecklistPort.ChecklistDetail get(long projectId, long checklistId) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());
        RequirementChecklistPort.ChecklistDetail detail =
                checklistPort.findDetail(operator.getOrgId(), projectId, checklistId);
        if (detail == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "清单不存在");
        }
        return detail;
    }

    public Optional<JobPort.JobRef> claimNextBuildChecklist(String workerId) {
        return jobPort.claimNextPending(JobPort.TYPE_BUILD_CHECKLIST, workerId);
    }

    private List<Long> resolveTenderDocumentIds(long orgId,
                                                long projectId,
                                                long tenderPackageId,
                                                List<Long> documentIds) {
        List<DocumentAccessPort.DocumentRef> inPackage =
                documentAccessPort.listActiveByPackageId(tenderPackageId);
        Set<Long> successIds = new LinkedHashSet<>();
        for (int i = 0; i < inPackage.size(); i++) {
            DocumentAccessPort.DocumentRef doc = inPackage.get(i);
            if (doc.getOrgId() != orgId) {
                continue;
            }
            if (doc.getProjectId() != null && doc.getProjectId() != projectId) {
                continue;
            }
            if (DocumentAccessPort.INDEX_SUCCESS.equals(doc.getIndexStatus())) {
                successIds.add(doc.getId());
            }
        }

        if (documentIds == null || documentIds.isEmpty()) {
            return new ArrayList<>(successIds);
        }

        List<Long> result = new ArrayList<>();
        for (int i = 0; i < documentIds.size(); i++) {
            Long id = documentIds.get(i);
            if (id == null) {
                continue;
            }
            if (!successIds.contains(id)) {
                DocumentAccessPort.DocumentRef doc = documentAccessPort.findActiveById(id);
                if (doc == null || doc.getOrgId() != orgId) {
                    throw new BizException(ErrorCode.NOT_FOUND, "文档不存在: " + id);
                }
                if (doc.getPackageId() != tenderPackageId) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "文档不属于本项目招标包: " + id);
                }
                if (!DocumentAccessPort.INDEX_SUCCESS.equals(doc.getIndexStatus())) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "文档尚未索引成功: " + id);
                }
            }
            if (!result.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * 解析本次建清单使用的维度：产品固定用全部启用维度，前端不提供勾选。
     * 请求里的 dimensionCodes 忽略（兼容旧客户端传参）。
     */
    private List<String> resolveDimensionCodes(List<String> requested) {
        List<ReviewDimensionPort.Dimension> all = reviewDimensionPort.listAll(true);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            ReviewDimensionPort.Dimension d = all.get(i);
            if (d.getCode() == null || d.getCode().isBlank()) {
                continue;
            }
            result.add(d.getCode());
        }
        if (result.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有可用的审查维度，请先在管理端配置");
        }
        return result;
    }

    private String toJsonStringArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "序列化维度失败");
        }
    }

    private String toJsonLongArray(List<Long> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "序列化文档 id 失败");
        }
    }

    /** 创建结果。 */
    public static class CreateResult {
        private long checklistId;
        private long jobId;
        private int version;

        public long getChecklistId() {
            return checklistId;
        }

        public void setChecklistId(long checklistId) {
            this.checklistId = checklistId;
        }

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }
    }
}
