package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.ReviewDimensionPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 建清单召回调试：自动复用真实建清单的维度检索词与招标文件范围。
 */
@Service
public class ChecklistRecallDebugAppService {

    private final ProjectLookupPort projectLookupPort;
    private final PackageLookupPort packageLookupPort;
    private final DocumentAccessPort documentAccessPort;
    private final ReviewDimensionPort reviewDimensionPort;
    private final ChecklistBuildPipeline pipeline;
    private final int recallTopK;
    private final int rerankTopN;

    public ChecklistRecallDebugAppService(
            ProjectLookupPort projectLookupPort,
            PackageLookupPort packageLookupPort,
            DocumentAccessPort documentAccessPort,
            ReviewDimensionPort reviewDimensionPort,
            ChecklistBuildPipeline pipeline,
            @Value("${bidding.checklist.recall-top-k:${bidding.recall.default-top-k:40}}") int recallTopK,
            @Value("${bidding.checklist.rerank-top-n:${bidding.rerank.tender-top-n:15}}") int rerankTopN) {
        this.projectLookupPort = projectLookupPort;
        this.packageLookupPort = packageLookupPort;
        this.documentAccessPort = documentAccessPort;
        this.reviewDimensionPort = reviewDimensionPort;
        this.pipeline = pipeline;
        this.recallTopK = recallTopK < 1 ? 40 : recallTopK;
        this.rerankTopN = rerankTopN < 1 ? 15 : rerankTopN;
    }

    @Transactional(readOnly = true)
    public DebugResult debugTenderRecall(long projectId) {
        TenderDebugContext context = loadTenderDebugContext(projectId, "召回");
        ChecklistBuildPipeline.RecallDebugResult recall = pipeline.debugRecall(
                context.orgId,
                projectId,
                context.dimensions,
                context.packageIds,
                context.documentIds,
                recallTopK);

        DebugResult result = new DebugResult();
        result.setProjectId(projectId);
        result.setTenderPackageId(context.tenderPackage.getId());
        result.setTenderPackageName(context.tenderPackage.getName());
        result.setDocuments(context.documentSummaries);
        result.setDimensionCount(context.dimensions.size());
        result.setQueryTexts(recall.getQueryTexts());
        result.setTopK(recall.getTopK());
        result.setHits(recall.getHits());
        return result;
    }

    @Transactional(readOnly = true)
    public RerankDebugResult debugTenderRerank(long projectId) {
        TenderDebugContext context = loadTenderDebugContext(projectId, "重排");
        ChecklistBuildPipeline.RerankDebugResult rerank = pipeline.debugRerank(
                context.orgId,
                projectId,
                context.dimensions,
                context.packageIds,
                context.documentIds,
                recallTopK,
                rerankTopN);

        RerankDebugResult result = new RerankDebugResult();
        result.setProjectId(projectId);
        result.setTenderPackageId(context.tenderPackage.getId());
        result.setTenderPackageName(context.tenderPackage.getName());
        result.setDocuments(context.documentSummaries);
        result.setDimensionCount(context.dimensions.size());
        result.setQueryTexts(rerank.getQueryTexts());
        result.setRerankQuery(rerank.getRerankQuery());
        result.setQualificationRerankQuery(rerank.getQualificationRerankQuery());
        result.setScoringRerankQuery(rerank.getScoringRerankQuery());
        result.setRecallTopK(rerank.getRecallTopK());
        result.setRecallHitCount(rerank.getRecallHitCount());
        result.setRerankTopN(rerank.getRerankTopN());
        result.setQualificationHits(rerank.getQualificationHits());
        result.setScoringHits(rerank.getScoringHits());
        result.setHits(rerank.getHits());
        return result;
    }

    private TenderDebugContext loadTenderDebugContext(long projectId, String actionName) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());

        PackageLookupPort.PackageRef tenderPackage =
                packageLookupPort.findActiveByProjectIdAndType(projectId, ChecklistAppService.PACKAGE_TYPE_TENDER);
        if (tenderPackage == null || tenderPackage.getOrgId() != operator.getOrgId()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "项目未绑定招标包，无法调试建清单" + actionName);
        }

        List<DocumentAccessPort.DocumentRef> documents =
                documentAccessPort.listActiveByPackageId(tenderPackage.getId());
        List<Long> documentIds = new ArrayList<>();
        List<DocumentSummary> documentSummaries = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            DocumentAccessPort.DocumentRef doc = documents.get(i);
            if (doc.getOrgId() != operator.getOrgId()) {
                continue;
            }
            if (doc.getProjectId() != null && doc.getProjectId() != projectId) {
                continue;
            }
            if (!DocumentAccessPort.INDEX_SUCCESS.equals(doc.getIndexStatus())) {
                continue;
            }
            documentIds.add(doc.getId());
            documentSummaries.add(toDocumentSummary(doc));
        }
        if (documentIds.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有已索引成功的招标文档，请先完成 INDEX");
        }

        List<ReviewDimensionPort.Dimension> dimensions = reviewDimensionPort.listAll(true);
        if (dimensions.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "没有可用的审查维度，请先在管理端配置");
        }

        TenderDebugContext context = new TenderDebugContext();
        context.orgId = operator.getOrgId();
        context.tenderPackage = tenderPackage;
        context.documentIds = documentIds;
        context.documentSummaries = documentSummaries;
        context.dimensions = dimensions;
        context.packageIds = new ArrayList<>();
        context.packageIds.add(tenderPackage.getId());
        return context;
    }

    private static DocumentSummary toDocumentSummary(DocumentAccessPort.DocumentRef doc) {
        DocumentSummary summary = new DocumentSummary();
        summary.setDocumentId(doc.getId());
        summary.setFileName(doc.getFileName());
        summary.setFileExt(doc.getFileExt());
        summary.setIndexStatus(doc.getIndexStatus());
        return summary;
    }

    public static class DebugResult {
        private long projectId;
        private long tenderPackageId;
        private String tenderPackageName;
        private List<DocumentSummary> documents = new ArrayList<>();
        private int dimensionCount;
        private List<String> queryTexts = new ArrayList<>();
        private int topK;
        private List<ChecklistBuildPipeline.RecallHitView> hits = new ArrayList<>();

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getTenderPackageId() {
            return tenderPackageId;
        }

        public void setTenderPackageId(long tenderPackageId) {
            this.tenderPackageId = tenderPackageId;
        }

        public String getTenderPackageName() {
            return tenderPackageName;
        }

        public void setTenderPackageName(String tenderPackageName) {
            this.tenderPackageName = tenderPackageName;
        }

        public List<DocumentSummary> getDocuments() {
            return documents;
        }

        public void setDocuments(List<DocumentSummary> documents) {
            this.documents = documents == null ? new ArrayList<>() : documents;
        }

        public int getDimensionCount() {
            return dimensionCount;
        }

        public void setDimensionCount(int dimensionCount) {
            this.dimensionCount = dimensionCount;
        }

        public List<String> getQueryTexts() {
            return queryTexts;
        }

        public void setQueryTexts(List<String> queryTexts) {
            this.queryTexts = queryTexts == null ? new ArrayList<>() : queryTexts;
        }

        public int getQueryCount() {
            return queryTexts.size();
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public List<ChecklistBuildPipeline.RecallHitView> getHits() {
            return hits;
        }

        public void setHits(List<ChecklistBuildPipeline.RecallHitView> hits) {
            this.hits = hits == null ? new ArrayList<>() : hits;
        }

        public int getHitCount() {
            return hits.size();
        }
    }

    public static class RerankDebugResult {
        private long projectId;
        private long tenderPackageId;
        private String tenderPackageName;
        private List<DocumentSummary> documents = new ArrayList<>();
        private int dimensionCount;
        private List<String> queryTexts = new ArrayList<>();
        private String rerankQuery;
        private String qualificationRerankQuery;
        private String scoringRerankQuery;
        private int recallTopK;
        private int recallHitCount;
        private int rerankTopN;
        private List<ChecklistBuildPipeline.RerankHitView> hits = new ArrayList<>();
        private List<ChecklistBuildPipeline.RerankHitView> qualificationHits = new ArrayList<>();
        private List<ChecklistBuildPipeline.RerankHitView> scoringHits = new ArrayList<>();

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getTenderPackageId() {
            return tenderPackageId;
        }

        public void setTenderPackageId(long tenderPackageId) {
            this.tenderPackageId = tenderPackageId;
        }

        public String getTenderPackageName() {
            return tenderPackageName;
        }

        public void setTenderPackageName(String tenderPackageName) {
            this.tenderPackageName = tenderPackageName;
        }

        public List<DocumentSummary> getDocuments() {
            return documents;
        }

        public void setDocuments(List<DocumentSummary> documents) {
            this.documents = documents == null ? new ArrayList<>() : documents;
        }

        public int getDimensionCount() {
            return dimensionCount;
        }

        public void setDimensionCount(int dimensionCount) {
            this.dimensionCount = dimensionCount;
        }

        public List<String> getQueryTexts() {
            return queryTexts;
        }

        public void setQueryTexts(List<String> queryTexts) {
            this.queryTexts = queryTexts == null ? new ArrayList<>() : queryTexts;
        }

        public int getQueryCount() {
            return queryTexts.size();
        }

        public String getRerankQuery() {
            return rerankQuery;
        }

        public void setRerankQuery(String rerankQuery) {
            this.rerankQuery = rerankQuery;
        }

        public String getQualificationRerankQuery() {
            return qualificationRerankQuery;
        }

        public void setQualificationRerankQuery(String qualificationRerankQuery) {
            this.qualificationRerankQuery = qualificationRerankQuery;
        }

        public String getScoringRerankQuery() {
            return scoringRerankQuery;
        }

        public void setScoringRerankQuery(String scoringRerankQuery) {
            this.scoringRerankQuery = scoringRerankQuery;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public int getRecallHitCount() {
            return recallHitCount;
        }

        public void setRecallHitCount(int recallHitCount) {
            this.recallHitCount = recallHitCount;
        }

        public int getRerankTopN() {
            return rerankTopN;
        }

        public void setRerankTopN(int rerankTopN) {
            this.rerankTopN = rerankTopN;
        }

        public List<ChecklistBuildPipeline.RerankHitView> getHits() {
            return hits;
        }

        public void setHits(List<ChecklistBuildPipeline.RerankHitView> hits) {
            this.hits = hits == null ? new ArrayList<>() : hits;
        }

        public int getRerankHitCount() {
            return hits.size();
        }

        public List<ChecklistBuildPipeline.RerankHitView> getQualificationHits() {
            return qualificationHits;
        }

        public void setQualificationHits(List<ChecklistBuildPipeline.RerankHitView> qualificationHits) {
            this.qualificationHits = qualificationHits == null ? new ArrayList<>() : qualificationHits;
        }

        public int getQualificationHitCount() {
            return qualificationHits.size();
        }

        public List<ChecklistBuildPipeline.RerankHitView> getScoringHits() {
            return scoringHits;
        }

        public void setScoringHits(List<ChecklistBuildPipeline.RerankHitView> scoringHits) {
            this.scoringHits = scoringHits == null ? new ArrayList<>() : scoringHits;
        }

        public int getScoringHitCount() {
            return scoringHits.size();
        }
    }

    public static class DocumentSummary {
        private long documentId;
        private String fileName;
        private String fileExt;
        private String indexStatus;

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileExt() {
            return fileExt;
        }

        public void setFileExt(String fileExt) {
            this.fileExt = fileExt;
        }

        public String getIndexStatus() {
            return indexStatus;
        }

        public void setIndexStatus(String indexStatus) {
            this.indexStatus = indexStatus;
        }
    }

    private static class TenderDebugContext {
        private long orgId;
        private PackageLookupPort.PackageRef tenderPackage;
        private List<Long> packageIds = new ArrayList<>();
        private List<Long> documentIds = new ArrayList<>();
        private List<DocumentSummary> documentSummaries = new ArrayList<>();
        private List<ReviewDimensionPort.Dimension> dimensions = new ArrayList<>();
    }
}
