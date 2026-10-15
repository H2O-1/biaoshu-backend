package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 审查应用服务：创建异步对照任务、查询报告。
 */
@Service
public class ReviewAppService {

    public static final String PACKAGE_TYPE_BID = "BID";

    private final ReviewPort reviewPort;
    private final RequirementChecklistPort checklistPort;
    private final ProjectLookupPort projectLookupPort;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;
    private final JobPort jobPort;
    private final ObjectMapper objectMapper;
    private final int recallTopK;
    private final int rerankTopN;
    private final String promptVersion;

    public ReviewAppService(
            ReviewPort reviewPort,
            RequirementChecklistPort checklistPort,
            ProjectLookupPort projectLookupPort,
            PackageLookupPort packageLookupPort,
            DocumentAccessPort documentAccessPort,
            JobPort jobPort,
            ObjectMapper objectMapper,
            @Value("${bidding.review.recall-top-k:30}") int recallTopK,
            @Value("${bidding.review.rerank-top-n:${bidding.rerank.bid-top-n:15}}") int rerankTopN,
            @Value("${bidding.llm.review-prompt-version:review-v2}") String promptVersion) {
        this.reviewPort = reviewPort;
        this.checklistPort = checklistPort;
        this.projectLookupPort = projectLookupPort;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
        this.jobPort = jobPort;
        this.objectMapper = objectMapper;
        this.recallTopK = recallTopK < 1 ? 30 : recallTopK;
        this.rerankTopN = rerankTopN < 1 ? 6 : rerankTopN;
        this.promptVersion = promptVersion == null || promptVersion.isBlank()
                ? "review-v2" : promptVersion.trim();
    }

    /**
     * 创建审查：写 BUILDING 审查 + PENDING REVIEW Job。
     */
    @Transactional
    public CreateResult create(long projectId, Long checklistId, List<Long> packageIds) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());
        return createInternal(
                operator.getOrgId(), projectId, operator.getUserId(), checklistId, packageIds);
    }

    /**
     * 无鉴权创建（供一键审查 Worker 调用；调用方须已校验组织与项目）。
     */
    @Transactional
    public CreateResult createInternal(long orgId,
                                       long projectId,
                                       long createdBy,
                                       Long checklistId,
                                       List<Long> packageIds) {
        RequirementChecklistPort.ChecklistDetail checklist =
                resolveChecklist(orgId, projectId, checklistId);
        if (checklist.getItems() == null || checklist.getItems().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "审查清单无条目，无法发起审查");
        }

        List<Long> bidPackageIds = resolveBidPackageIds(orgId, projectId, packageIds);
        ensureBidPackagesHaveIndexedDocs(orgId, projectId, bidPackageIds);

        int version = reviewPort.nextVersion(orgId, projectId);
        String packageIdsJson = toJsonLongArray(bidPackageIds);
        long reviewId = reviewPort.createBuilding(
                orgId,
                projectId,
                checklist.getId(),
                version,
                packageIdsJson,
                promptVersion,
                recallTopK,
                rerankTopN,
                createdBy
        );

        String payload = "{\"reviewId\":" + reviewId
                + ",\"projectId\":" + projectId
                + ",\"checklistId\":" + checklist.getId()
                + "}";
        long jobId = jobPort.insert(
                orgId,
                projectId,
                JobPort.TYPE_REVIEW,
                JobPort.STATUS_PENDING,
                payload,
                createdBy
        );

        CreateResult result = new CreateResult();
        result.setReviewId(reviewId);
        result.setJobId(jobId);
        result.setVersion(version);
        return result;
    }

    @Transactional(readOnly = true)
    public List<ReviewPort.ReviewSummary> list(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());
        return reviewPort.listByProject(operator.getOrgId(), projectId);
    }

    @Transactional(readOnly = true)
    public ReviewPort.ReviewDetail get(long projectId, long reviewId) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());
        ReviewPort.ReviewDetail detail =
                reviewPort.findDetail(operator.getOrgId(), projectId, reviewId);
        if (detail == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "审查不存在");
        }
        return detail;
    }

    public Optional<JobPort.JobRef> claimNextReview(String workerId) {
        return jobPort.claimNextRunnable(JobPort.TYPE_REVIEW, workerId);
    }

    private RequirementChecklistPort.ChecklistDetail resolveChecklist(
            long orgId, long projectId, Long checklistId) {
        if (checklistId != null) {
            RequirementChecklistPort.ChecklistDetail detail =
                    checklistPort.findDetail(orgId, projectId, checklistId);
            if (detail == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "审查清单不存在");
            }
            if (!RequirementChecklistPort.STATUS_SUCCESS.equals(detail.getStatus())) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "审查清单尚未成功，当前状态: " + detail.getStatus());
            }
            return detail;
        }
        RequirementChecklistPort.ChecklistDetail latest =
                checklistPort.findLatestSuccess(orgId, projectId);
        if (latest == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "项目尚无成功的审查清单，请先生成清单");
        }
        return latest;
    }

    private List<Long> resolveBidPackageIds(long orgId, long projectId, List<Long> requested) {
        List<PackageLookupPort.PackageRef> bidPkgs =
                packageLookupPort.listActiveByProjectIdAndType(projectId, PACKAGE_TYPE_BID);
        Set<Long> allowed = new HashSet<>();
        for (int i = 0; i < bidPkgs.size(); i++) {
            PackageLookupPort.PackageRef pkg = bidPkgs.get(i);
            if (pkg.getOrgId() != orgId) {
                continue;
            }
            allowed.add(pkg.getId());
        }
        if (requested == null || requested.isEmpty()) {
            List<Long> all = new ArrayList<>();
            for (int i = 0; i < bidPkgs.size(); i++) {
                PackageLookupPort.PackageRef pkg = bidPkgs.get(i);
                if (pkg.getOrgId() == orgId && hasActiveDocument(orgId, projectId, pkg.getId())) {
                    all.add(pkg.getId());
                }
            }
            if (all.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "投标包下没有文件，请先上传投标文件");
            }
            return all;
        }
        List<Long> resolved = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            Long id = requested.get(i);
            if (id == null) {
                continue;
            }
            if (!allowed.contains(id)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "packageId=" + id + " 不是本项目的有效投标文件包");
            }
            if (!hasActiveDocument(orgId, projectId, id)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "packageId=" + id + " 下没有文件，请先上传投标文件");
            }
            if (!resolved.contains(id)) {
                resolved.add(id);
            }
        }
        if (resolved.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未指定有效投标文件包");
        }
        return resolved;
    }

    private void ensureBidPackagesHaveIndexedDocs(long orgId, long projectId, List<Long> bidPackageIds) {
        for (int i = 0; i < bidPackageIds.size(); i++) {
            long packageId = bidPackageIds.get(i);
            if (!hasIndexedDocument(orgId, projectId, packageId)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "选定投标包下没有已索引成功的文档，请先完成 INDEX");
            }
        }
    }

    private boolean hasActiveDocument(long orgId, long projectId, long packageId) {
        List<DocumentAccessPort.DocumentRef> docs =
                documentAccessPort.listActiveByPackageId(packageId);
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        for (int i = 0; i < docs.size(); i++) {
            DocumentAccessPort.DocumentRef doc = docs.get(i);
            if (isSameProjectDocument(doc, orgId, projectId)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIndexedDocument(long orgId, long projectId, long packageId) {
        List<DocumentAccessPort.DocumentRef> docs =
                documentAccessPort.listActiveByPackageId(packageId);
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        for (int i = 0; i < docs.size(); i++) {
            DocumentAccessPort.DocumentRef doc = docs.get(i);
            if (isSameProjectDocument(doc, orgId, projectId)
                    && DocumentAccessPort.INDEX_SUCCESS.equals(doc.getIndexStatus())) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameProjectDocument(DocumentAccessPort.DocumentRef doc,
                                          long orgId,
                                          long projectId) {
        if (doc == null || doc.getOrgId() != orgId) {
            return false;
        }
        if (doc.getProjectId() != null && doc.getProjectId() != projectId) {
            return false;
        }
        return true;
    }

    private String toJsonLongArray(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 packageIds 失败", ex);
        }
    }

    /** 创建结果。 */
    public static class CreateResult {
        private long reviewId;
        private long jobId;
        private int version;

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
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
