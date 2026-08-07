package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.bidding.review.infrastructure.logging.AiPipelineLog;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 一键 AI 审查：入队总任务 AI_REVIEW_PIPELINE（INDEX → 建清单 → REVIEW）。
 */
@Service
public class AiReviewPipelineAppService {

    public static final String PACKAGE_TYPE_TENDER = "TENDER";
    public static final String PACKAGE_TYPE_BID = "BID";
    /** V31 部分唯一索引名：同项目最多一条活跃一键审查任务。 */
    private static final String ACTIVE_PIPELINE_UNIQUE_INDEX = "uk_biz_job_active_ai_review_pipeline";

    private final JobPort jobPort;
    private final ProjectLookupPort projectLookupPort;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;

    public AiReviewPipelineAppService(JobPort jobPort,
                                      ProjectLookupPort projectLookupPort,
                                      PackageLookupPort packageLookupPort,
                                      DocumentAccessPort documentAccessPort) {
        this.jobPort = jobPort;
        this.projectLookupPort = projectLookupPort;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
    }

    /**
     * 发起一键审查。同项目已有进行中的总任务 → 409。
     */
    @Transactional
    public StartResult start(long projectId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可发起一键审查");
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());

        if (jobPort.existsActiveByProjectIdAndType(projectId, JobPort.TYPE_AI_REVIEW_PIPELINE)) {
            throw new BizException(ErrorCode.CONFLICT, "该项目已有进行中的一键审查任务，请勿重复提交");
        }

        PackageLookupPort.PackageRef tenderPkg =
                packageLookupPort.findActiveByProjectIdAndType(projectId, PACKAGE_TYPE_TENDER);
        if (tenderPkg == null || tenderPkg.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目未绑定招标包，无法发起一键审查");
        }
        List<PackageLookupPort.PackageRef> bidPkgs =
                packageLookupPort.listActiveByProjectIdAndType(projectId, PACKAGE_TYPE_BID);
        if (bidPkgs == null || bidPkgs.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目下无投标包，无法发起一键审查");
        }

        int tenderDocCount = countActiveDocs(tenderPkg.getId(), operator.getOrgId(), projectId);
        if (tenderDocCount < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "招标包下没有文件，请先上传招标文件");
        }
        int bidDocCount = 0;
        for (int i = 0; i < bidPkgs.size(); i++) {
            PackageLookupPort.PackageRef bid = bidPkgs.get(i);
            if (bid.getOrgId() != operator.getOrgId()) {
                continue;
            }
            bidDocCount += countActiveDocs(bid.getId(), operator.getOrgId(), projectId);
        }
        if (bidDocCount < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "投标包下没有文件，请先上传投标文件");
        }

        String payload = "{\"projectId\":" + projectId
                + ",\"phase\":\"INDEX\""
                + ",\"createdBy\":" + operator.getUserId()
                + "}";
        long jobId;
        try {
            jobId = jobPort.insert(
                    operator.getOrgId(),
                    projectId,
                    JobPort.TYPE_AI_REVIEW_PIPELINE,
                    JobPort.STATUS_PENDING,
                    payload,
                    operator.getUserId()
            );
        } catch (DuplicateKeyException ex) {
            // 并发双开被 V31 部分唯一索引拦截。只识别本约束的冲突，
            // 不把其它数据完整性异常误判成重复任务；命中则与前置检查同一 409 语义
            if (ex.getMessage() != null && ex.getMessage().contains(ACTIVE_PIPELINE_UNIQUE_INDEX)) {
                throw new BizException(ErrorCode.CONFLICT, "该项目已有进行中的一键审查任务，请勿重复提交");
            }
            throw ex;
        }

        AiPipelineLog.info(projectId, jobId, "START",
                "一键审查已入队 tenderDocs=" + tenderDocCount + " bidDocs=" + bidDocCount
                        + " bidPackages=" + bidPkgs.size());

        StartResult result = new StartResult();
        result.setJobId(jobId);
        return result;
    }

    public Optional<JobPort.JobRef> claimNext(String workerId) {
        return jobPort.claimNextPending(JobPort.TYPE_AI_REVIEW_PIPELINE, workerId);
    }

    private int countActiveDocs(long packageId, long orgId, long projectId) {
        List<DocumentAccessPort.DocumentRef> docs =
                documentAccessPort.listActiveByPackageId(packageId);
        int count = 0;
        if (docs == null) {
            return 0;
        }
        for (int i = 0; i < docs.size(); i++) {
            DocumentAccessPort.DocumentRef doc = docs.get(i);
            if (doc.getOrgId() != orgId) {
                continue;
            }
            if (doc.getProjectId() != null && doc.getProjectId() != projectId) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** 发起结果。 */
    public static class StartResult {
        private long jobId;

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
        }
    }
}
