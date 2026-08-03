package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.EmbeddingPort;
import com.bidding.common.port.ProjectLookupPort;
import com.bidding.common.port.VectorRecallPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量召回用例：编码 query → 项目范围召回 → 打日志（列表不入库）。
 */
@Service
public class RecallAppService {

    private static final Logger log = LoggerFactory.getLogger(RecallAppService.class);

    private final EmbeddingPort embeddingPort;
    private final VectorRecallPort vectorRecallPort;
    private final ProjectLookupPort projectLookupPort;
    private final int defaultTopK;

    public RecallAppService(EmbeddingPort embeddingPort,
                            VectorRecallPort vectorRecallPort,
                            ProjectLookupPort projectLookupPort,
                            @Value("${bidding.recall.default-top-k:40}") int defaultTopK) {
        this.embeddingPort = embeddingPort;
        this.vectorRecallPort = vectorRecallPort;
        this.projectLookupPort = projectLookupPort;
        if (defaultTopK < 1) {
            this.defaultTopK = 40;
        } else {
            this.defaultTopK = defaultTopK;
        }
    }

    /**
     * 项目下向量召回（招标方；本组织项目）。
     *
     * @param packageIds  可选，按标包过滤
     * @param documentIds 可选，按文档过滤
     * @param topK        ≤0 时用配置默认值
     */
    @Transactional(readOnly = true)
    public List<HitSummary> recall(long projectId,
                                   String query,
                                   int topK,
                                   List<Long> packageIds,
                                   List<Long> documentIds) {
        CurrentUser operator = TendererAuth.requireTenderer();
        TendererAuth.requireOwnedProject(projectLookupPort, projectId, operator.getOrgId());

        String safeQuery = query == null ? "" : query.trim();
        if (safeQuery.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "query 不能为空");
        }

        int safeTopK = topK;
        if (safeTopK < 1) {
            safeTopK = defaultTopK;
        }

        List<String> texts = new ArrayList<>();
        texts.add(safeQuery);
        List<float[]> vectors = embeddingPort.embed(texts);
        if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
            throw new BizException(ErrorCode.INTERNAL, "query 向量化失败");
        }

        VectorRecallPort.RecallQuery recallQuery = new VectorRecallPort.RecallQuery();
        recallQuery.setOrgId(operator.getOrgId());
        recallQuery.setProjectId(projectId);
        recallQuery.setQueryEmbedding(vectors.get(0));
        recallQuery.setTopK(safeTopK);
        recallQuery.setPackageIds(packageIds);
        recallQuery.setDocumentIds(documentIds);

        List<VectorRecallPort.RecallHit> hits = vectorRecallPort.recall(recallQuery);
        logRecall(projectId, safeQuery, safeTopK, hits);

        List<HitSummary> summaries = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            summaries.add(toSummary(hits.get(i)));
        }
        return summaries;
    }

    private void logRecall(long projectId, String query, int topK, List<VectorRecallPort.RecallHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("向量召回 projectId=").append(projectId)
                .append(" topK=").append(topK)
                .append(" hitCount=").append(hits.size())
                .append(" queryLen=").append(query.length())
                .append(" hits=[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            VectorRecallPort.RecallHit hit = hits.get(i);
            sb.append("chunkId=").append(hit.getChunkId())
                    .append(",docId=").append(hit.getDocumentId())
                    .append(",pkgId=").append(hit.getPackageId())
                    .append(",pkgType=").append(hit.getPackageType())
                    .append(",score=").append(hit.getScore());
        }
        sb.append(']');
        log.info(sb.toString());
    }

    private HitSummary toSummary(VectorRecallPort.RecallHit hit) {
        HitSummary summary = new HitSummary();
        summary.setChunkId(hit.getChunkId());
        summary.setChunkIndex(hit.getChunkIndex());
        summary.setPageNo(hit.getPageNo());
        summary.setContent(hit.getContent());
        summary.setDocumentId(hit.getDocumentId());
        summary.setFileName(hit.getFileName());
        summary.setPackageId(hit.getPackageId());
        summary.setPackageType(hit.getPackageType());
        summary.setPackageName(hit.getPackageName());
        summary.setScore(hit.getScore());
        return summary;
    }

    /** 召回结果摘要（给调试接口 / 后续建清单复用）。 */
    public static class HitSummary {
        private long chunkId;
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private long documentId;
        private String fileName;
        private long packageId;
        private String packageType;
        private String packageName;
        private float score;

        public long getChunkId() {
            return chunkId;
        }

        public void setChunkId(long chunkId) {
            this.chunkId = chunkId;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public Integer getPageNo() {
            return pageNo;
        }

        public void setPageNo(Integer pageNo) {
            this.pageNo = pageNo;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

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

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public String getPackageType() {
            return packageType;
        }

        public void setPackageType(String packageType) {
            this.packageType = packageType;
        }

        public String getPackageName() {
            return packageName;
        }

        public void setPackageName(String packageName) {
            this.packageName = packageName;
        }

        public float getScore() {
            return score;
        }

        public void setScore(float score) {
            this.score = score;
        }
    }
}
