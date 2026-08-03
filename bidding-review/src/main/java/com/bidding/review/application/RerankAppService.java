package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.RerankPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 召回后重排编排：先向量召回，再 qwen3-rerank；结果不入库，打日志。
 */
@Service
public class RerankAppService {

    private static final Logger log = LoggerFactory.getLogger(RerankAppService.class);

    private final RecallAppService recallAppService;
    private final RerankPort rerankPort;
    private final int defaultRecallTopK;
    private final int tenderTopN;
    private final int bidTopN;
    private final String tenderInstruct;
    private final String bidInstruct;

    public RerankAppService(
            RecallAppService recallAppService,
            RerankPort rerankPort,
            @Value("${bidding.recall.default-top-k:40}") int defaultRecallTopK,
            @Value("${bidding.rerank.tender-top-n:8}") int tenderTopN,
            @Value("${bidding.rerank.bid-top-n:6}") int bidTopN,
            @Value("${bidding.rerank.tender-instruct:给定招标要求查询，从招标文件中检索陈述相关强制性要求、资格条件或废标否决条款的段落。}")
            String tenderInstruct,
            @Value("${bidding.rerank.bid-instruct:给定一条招标要求，从投标文件中检索对该要求作出响应、举证或说明的相关段落。}")
            String bidInstruct) {
        this.recallAppService = recallAppService;
        this.rerankPort = rerankPort;
        this.defaultRecallTopK = defaultRecallTopK < 1 ? 40 : defaultRecallTopK;
        this.tenderTopN = tenderTopN < 1 ? 8 : tenderTopN;
        this.bidTopN = bidTopN < 1 ? 6 : bidTopN;
        this.tenderInstruct = tenderInstruct;
        this.bidInstruct = bidInstruct;
    }

    /**
     * 项目下：召回 → 重排（招标方）。
     *
     * @param side TENDER 用招标 instruct/topN；BID 用投标侧；其它默认 TENDER
     */
    @Transactional(readOnly = true)
    public List<RerankedHit> recallThenRerank(long projectId,
                                              String query,
                                              int recallTopK,
                                              int rerankTopN,
                                              String side,
                                              List<Long> packageIds,
                                              List<Long> documentIds) {
        int safeRecallTopK = recallTopK < 1 ? defaultRecallTopK : recallTopK;
        boolean bidSide = side != null && "BID".equalsIgnoreCase(side.trim());
        int defaultTopN = bidSide ? bidTopN : tenderTopN;
        String instruct = bidSide ? bidInstruct : tenderInstruct;
        int safeRerankTopN = rerankTopN < 1 ? defaultTopN : rerankTopN;

        List<RecallAppService.HitSummary> recalled = recallAppService.recall(
                projectId, query, safeRecallTopK, packageIds, documentIds);

        if (recalled.isEmpty()) {
            log.info("重排跳过：召回为空 projectId={} queryLen={}", projectId,
                    query == null ? 0 : query.trim().length());
            return new ArrayList<>();
        }

        List<String> documents = new ArrayList<>();
        for (int i = 0; i < recalled.size(); i++) {
            documents.add(recalled.get(i).getContent());
        }

        List<RerankPort.RerankHit> ranked = rerankPort.rerank(query, documents, safeRerankTopN, instruct);
        List<RerankedHit> result = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            RerankPort.RerankHit rh = ranked.get(i);
            int idx = rh.getIndex();
            if (idx < 0 || idx >= recalled.size()) {
                throw new BizException(ErrorCode.INTERNAL, "重排 index 越界: " + idx);
            }
            RecallAppService.HitSummary src = recalled.get(idx);
            RerankedHit hit = new RerankedHit();
            hit.setChunkId(src.getChunkId());
            hit.setChunkIndex(src.getChunkIndex());
            hit.setPageNo(src.getPageNo());
            hit.setContent(src.getContent());
            hit.setDocumentId(src.getDocumentId());
            hit.setFileName(src.getFileName());
            hit.setPackageId(src.getPackageId());
            hit.setPackageType(src.getPackageType());
            hit.setPackageName(src.getPackageName());
            hit.setRecallScore(src.getScore());
            hit.setRerankScore(rh.getScore());
            result.add(hit);
        }

        logRerank(projectId, query, safeRecallTopK, safeRerankTopN, bidSide, recalled.size(), result);
        return result;
    }

    private void logRerank(long projectId,
                           String query,
                           int recallTopK,
                           int rerankTopN,
                           boolean bidSide,
                           int recallCount,
                           List<RerankedHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("向量重排 projectId=").append(projectId)
                .append(" side=").append(bidSide ? "BID" : "TENDER")
                .append(" recallTopK=").append(recallTopK)
                .append(" recallCount=").append(recallCount)
                .append(" rerankTopN=").append(rerankTopN)
                .append(" hitCount=").append(hits.size())
                .append(" queryLen=").append(query == null ? 0 : query.trim().length())
                .append(" hits=[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            RerankedHit hit = hits.get(i);
            sb.append("chunkId=").append(hit.getChunkId())
                    .append(",docId=").append(hit.getDocumentId())
                    .append(",pkgType=").append(hit.getPackageType())
                    .append(",recall=").append(hit.getRecallScore())
                    .append(",rerank=").append(hit.getRerankScore());
        }
        sb.append(']');
        log.info(sb.toString());
    }

    /** 重排后命中（含召回分与重排分；不入库）。 */
    public static class RerankedHit {
        private long chunkId;
        private int chunkIndex;
        private Integer pageNo;
        private String content;
        private long documentId;
        private String fileName;
        private long packageId;
        private String packageType;
        private String packageName;
        private float recallScore;
        private float rerankScore;

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

        public float getRecallScore() {
            return recallScore;
        }

        public void setRecallScore(float recallScore) {
            this.recallScore = recallScore;
        }

        public float getRerankScore() {
            return rerankScore;
        }

        public void setRerankScore(float rerankScore) {
            this.rerankScore = rerankScore;
        }
    }
}
