package com.bidding.common.port;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 审查（REVIEW）持久化：基于清单对投标包对照结果。
 */
public interface ReviewPort {

    String STATUS_BUILDING = "BUILDING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    /** 文件/标包变更后作废，不再作为最新有效审查（workflow 视为待重审）。 */
    String STATUS_OBSOLETE = "OBSOLETE";

    /** 派生落库：符合→PASS、不符合→FAIL、无法判断→UNCLEAR */
    String VERDICT_PASS = "PASS";
    String VERDICT_FAIL = "FAIL";
    String VERDICT_UNCLEAR = "UNCLEAR";

    String STATUS_MATCH = "符合";
    String STATUS_MISMATCH = "不符合";
    String STATUS_UNKNOWN = "无法判断";
    String MAIN_QUALIFICATION = "QUALIFICATION";
    String MAIN_SCORING = "SCORING";

    int nextVersion(long orgId, long projectId);

    long createBuilding(long orgId,
                        long projectId,
                        long checklistId,
                        int version,
                        String packageIdsJson,
                        String promptVersion,
                        int recallTopK,
                        int rerankTopN,
                        long createdBy);

    void insertItems(long reviewId, List<ItemDraft> items);

    void markSuccess(long reviewId, String overallVerdict);

    void markFailed(long reviewId, String errorMessage);

    List<ReviewSummary> listByProject(long orgId, long projectId);

    ReviewDetail findDetail(long orgId, long projectId, long reviewId);

    /** 写入条目草稿。 */
    class ItemDraft {
        private long requirementItemId;
        private String dimensionCode;
        private String requirementText;
        private boolean must;
        private String verdict;
        private String reason;
        private String bidExcerpt;
        private Integer pageNo;
        private Long chunkId;
        private Long documentId;
        private String fileName;
        private Long packageId;
        private int sortOrder;
        /** 该条对照 LLM 原始输出；未调 LLM 时为空。 */
        private String llmRawJson;
        private String requirementCode;
        private String title;
        private String mainType;
        /** 子类 code 快照，如 CERTIFICATE / TECH_SCORE。 */
        private String subType;
        private String reviewType;
        private String status;
        private Boolean vetoRisk;
        private String judgement;
        private String bidEvidenceJson;
        private String note;
        private java.math.BigDecimal suggestedScore;
        private java.math.BigDecimal fullScore;
        private String deductionReason;

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
        }

        public String getDimensionCode() {
            return dimensionCode;
        }

        public void setDimensionCode(String dimensionCode) {
            this.dimensionCode = dimensionCode;
        }

        public String getRequirementText() {
            return requirementText;
        }

        public void setRequirementText(String requirementText) {
            this.requirementText = requirementText;
        }

        public boolean isMust() {
            return must;
        }

        public void setMust(boolean must) {
            this.must = must;
        }

        public String getVerdict() {
            return verdict;
        }

        public void setVerdict(String verdict) {
            this.verdict = verdict;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getBidExcerpt() {
            return bidExcerpt;
        }

        public void setBidExcerpt(String bidExcerpt) {
            this.bidExcerpt = bidExcerpt;
        }

        public Integer getPageNo() {
            return pageNo;
        }

        public void setPageNo(Integer pageNo) {
            this.pageNo = pageNo;
        }

        public Long getChunkId() {
            return chunkId;
        }

        public void setChunkId(Long chunkId) {
            this.chunkId = chunkId;
        }

        public Long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(Long documentId) {
            this.documentId = documentId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public Long getPackageId() {
            return packageId;
        }

        public void setPackageId(Long packageId) {
            this.packageId = packageId;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public String getLlmRawJson() {
            return llmRawJson;
        }

        public void setLlmRawJson(String llmRawJson) {
            this.llmRawJson = llmRawJson;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMainType() {
            return mainType;
        }

        public void setMainType(String mainType) {
            this.mainType = mainType;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
        }

        public String getReviewType() {
            return reviewType;
        }

        public void setReviewType(String reviewType) {
            this.reviewType = reviewType;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Boolean getVetoRisk() {
            return vetoRisk;
        }

        public void setVetoRisk(Boolean vetoRisk) {
            this.vetoRisk = vetoRisk;
        }

        public String getJudgement() {
            return judgement;
        }

        public void setJudgement(String judgement) {
            this.judgement = judgement;
        }

        public String getBidEvidenceJson() {
            return bidEvidenceJson;
        }

        public void setBidEvidenceJson(String bidEvidenceJson) {
            this.bidEvidenceJson = bidEvidenceJson;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public java.math.BigDecimal getSuggestedScore() {
            return suggestedScore;
        }

        public void setSuggestedScore(java.math.BigDecimal suggestedScore) {
            this.suggestedScore = suggestedScore;
        }

        public java.math.BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(java.math.BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public String getDeductionReason() {
            return deductionReason;
        }

        public void setDeductionReason(String deductionReason) {
            this.deductionReason = deductionReason;
        }
    }

    /** 审查摘要。 */
    class ReviewSummary {
        private long id;
        private long orgId;
        private long projectId;
        private long checklistId;
        private int version;
        private String status;
        private String packageIdsJson;
        private String overallVerdict;
        private boolean needHumanReview;
        private String promptVersion;
        private int recallTopK;
        private int rerankTopN;
        private String errorMessage;
        private long createdBy;
        private Instant createdAt;
        private Instant updatedAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public long getProjectId() {
            return projectId;
        }

        public void setProjectId(long projectId) {
            this.projectId = projectId;
        }

        public long getChecklistId() {
            return checklistId;
        }

        public void setChecklistId(long checklistId) {
            this.checklistId = checklistId;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getPackageIdsJson() {
            return packageIdsJson;
        }

        public void setPackageIdsJson(String packageIdsJson) {
            this.packageIdsJson = packageIdsJson;
        }

        public String getOverallVerdict() {
            return overallVerdict;
        }

        public void setOverallVerdict(String overallVerdict) {
            this.overallVerdict = overallVerdict;
        }

        public boolean isNeedHumanReview() {
            return needHumanReview;
        }

        public void setNeedHumanReview(boolean needHumanReview) {
            this.needHumanReview = needHumanReview;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
        }

        public int getRecallTopK() {
            return recallTopK;
        }

        public void setRecallTopK(int recallTopK) {
            this.recallTopK = recallTopK;
        }

        public int getRerankTopN() {
            return rerankTopN;
        }

        public void setRerankTopN(int rerankTopN) {
            this.rerankTopN = rerankTopN;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    /** 审查详情。 */
    class ReviewDetail extends ReviewSummary {
        private List<ItemView> items = new ArrayList<>();

        public List<ItemView> getItems() {
            return items;
        }

        public void setItems(List<ItemView> items) {
            if (items == null) {
                this.items = new ArrayList<>();
            } else {
                this.items = items;
            }
        }
    }

    /** 条目视图。 */
    class ItemView {
        private long id;
        private long reviewId;
        private long requirementItemId;
        private String dimensionCode;
        private String requirementText;
        private boolean must;
        private String verdict;
        private String reason;
        private String bidExcerpt;
        private Integer pageNo;
        private Long chunkId;
        private Long documentId;
        private String fileName;
        private Long packageId;
        private int sortOrder;
        /** 该条对照 LLM 原始输出。 */
        private String llmRawJson;
        private String requirementCode;
        private String title;
        private String mainType;
        /** 子类 code 快照，如 CERTIFICATE / TECH_SCORE。 */
        private String subType;
        private String reviewType;
        private String status;
        private Boolean vetoRisk;
        private String judgement;
        private String bidEvidenceJson;
        private String note;
        private java.math.BigDecimal suggestedScore;
        private java.math.BigDecimal fullScore;
        private String deductionReason;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getReviewId() {
            return reviewId;
        }

        public void setReviewId(long reviewId) {
            this.reviewId = reviewId;
        }

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
        }

        public String getDimensionCode() {
            return dimensionCode;
        }

        public void setDimensionCode(String dimensionCode) {
            this.dimensionCode = dimensionCode;
        }

        public String getRequirementText() {
            return requirementText;
        }

        public void setRequirementText(String requirementText) {
            this.requirementText = requirementText;
        }

        public boolean isMust() {
            return must;
        }

        public void setMust(boolean must) {
            this.must = must;
        }

        public String getVerdict() {
            return verdict;
        }

        public void setVerdict(String verdict) {
            this.verdict = verdict;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getBidExcerpt() {
            return bidExcerpt;
        }

        public void setBidExcerpt(String bidExcerpt) {
            this.bidExcerpt = bidExcerpt;
        }

        public Integer getPageNo() {
            return pageNo;
        }

        public void setPageNo(Integer pageNo) {
            this.pageNo = pageNo;
        }

        public Long getChunkId() {
            return chunkId;
        }

        public void setChunkId(Long chunkId) {
            this.chunkId = chunkId;
        }

        public Long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(Long documentId) {
            this.documentId = documentId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public Long getPackageId() {
            return packageId;
        }

        public void setPackageId(Long packageId) {
            this.packageId = packageId;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public String getLlmRawJson() {
            return llmRawJson;
        }

        public void setLlmRawJson(String llmRawJson) {
            this.llmRawJson = llmRawJson;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMainType() {
            return mainType;
        }

        public void setMainType(String mainType) {
            this.mainType = mainType;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
        }

        public String getReviewType() {
            return reviewType;
        }

        public void setReviewType(String reviewType) {
            this.reviewType = reviewType;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Boolean getVetoRisk() {
            return vetoRisk;
        }

        public void setVetoRisk(Boolean vetoRisk) {
            this.vetoRisk = vetoRisk;
        }

        public String getJudgement() {
            return judgement;
        }

        public void setJudgement(String judgement) {
            this.judgement = judgement;
        }

        public String getBidEvidenceJson() {
            return bidEvidenceJson;
        }

        public void setBidEvidenceJson(String bidEvidenceJson) {
            this.bidEvidenceJson = bidEvidenceJson;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public java.math.BigDecimal getSuggestedScore() {
            return suggestedScore;
        }

        public void setSuggestedScore(java.math.BigDecimal suggestedScore) {
            this.suggestedScore = suggestedScore;
        }

        public java.math.BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(java.math.BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public String getDeductionReason() {
            return deductionReason;
        }

        public void setDeductionReason(String deductionReason) {
            this.deductionReason = deductionReason;
        }
    }
}
