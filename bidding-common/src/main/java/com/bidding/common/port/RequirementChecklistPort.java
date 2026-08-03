package com.bidding.common.port;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 招标要求清单持久化（实现在 review 模块）。
 */
public interface RequirementChecklistPort {

    String STATUS_BUILDING = "BUILDING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    /** 招标文件变更后作废，需重新建清单。 */
    String STATUS_OBSOLETE = "OBSOLETE";

    /**
     * 项目下下一版本号（当前最大 version + 1；无记录则 1）。
     */
    int nextVersion(long orgId, long projectId);

    /**
     * 插入 BUILDING 清单主表行。
     *
     * @return checklistId
     */
    long createBuilding(long orgId,
                        long projectId,
                        int version,
                        String dimensionCodesJson,
                        String tenderDocumentIdsJson,
                        String promptVersion,
                        int recallTopK,
                        int rerankTopN,
                        long createdBy);

    void insertItems(long checklistId, List<ItemDraft> items);

    /**
     * 标记成功；可同时写入建清单 LLM 原始输出。
     *
     * @param llmRawJson 模型 message.content，可空
     */
    void markSuccess(long checklistId, String llmRawJson);

    void markFailed(long checklistId, String errorMessage);

    /**
     * 列表（不含 items）；跨组织或不存在返回空列表（调用方按 org 过滤）。
     */
    List<ChecklistSummary> listByProject(long orgId, long projectId);

    /**
     * 详情含 items；不存在或不属该 org/project 返回 null。
     */
    ChecklistDetail findDetail(long orgId, long projectId, long checklistId);

    /**
     * 项目下最新 SUCCESS 清单；没有则返回 null。
     */
    ChecklistDetail findLatestSuccess(long orgId, long projectId);

    /** 写入条目草稿。 */
    class ItemDraft {
        private String dimensionCode;
        private String text;
        private String sourceExcerpt;
        private Integer pageNo;
        private String chunkIdsJson;
        private boolean must;
        private int sortOrder;
        private String title;
        private String mainType;
        private String subType;
        private String bidSearchJson;
        private String evidenceJson;
        private java.math.BigDecimal scoreTotal;
        private String scorePartsJson;
        private Boolean scoreSumMatches;
        /** 产品编号，如 Q1 / S1。 */
        private String requirementCode;

        public String getDimensionCode() {
            return dimensionCode;
        }

        public void setDimensionCode(String dimensionCode) {
            this.dimensionCode = dimensionCode;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getSourceExcerpt() {
            return sourceExcerpt;
        }

        public void setSourceExcerpt(String sourceExcerpt) {
            this.sourceExcerpt = sourceExcerpt;
        }

        public Integer getPageNo() {
            return pageNo;
        }

        public void setPageNo(Integer pageNo) {
            this.pageNo = pageNo;
        }

        public String getChunkIdsJson() {
            return chunkIdsJson;
        }

        public void setChunkIdsJson(String chunkIdsJson) {
            this.chunkIdsJson = chunkIdsJson;
        }

        public boolean isMust() {
            return must;
        }

        public void setMust(boolean must) {
            this.must = must;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
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

        public String getBidSearchJson() {
            return bidSearchJson;
        }

        public void setBidSearchJson(String bidSearchJson) {
            this.bidSearchJson = bidSearchJson;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public java.math.BigDecimal getScoreTotal() {
            return scoreTotal;
        }

        public void setScoreTotal(java.math.BigDecimal scoreTotal) {
            this.scoreTotal = scoreTotal;
        }

        public String getScorePartsJson() {
            return scorePartsJson;
        }

        public void setScorePartsJson(String scorePartsJson) {
            this.scorePartsJson = scorePartsJson;
        }

        public Boolean getScoreSumMatches() {
            return scoreSumMatches;
        }

        public void setScoreSumMatches(Boolean scoreSumMatches) {
            this.scoreSumMatches = scoreSumMatches;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }
    }

    /** 清单摘要（列表用）。 */
    class ChecklistSummary {
        private long id;
        private long orgId;
        private long projectId;
        private int version;
        private String status;
        private String dimensionCodesJson;
        private String tenderDocumentIdsJson;
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

        public String getDimensionCodesJson() {
            return dimensionCodesJson;
        }

        public void setDimensionCodesJson(String dimensionCodesJson) {
            this.dimensionCodesJson = dimensionCodesJson;
        }

        public String getTenderDocumentIdsJson() {
            return tenderDocumentIdsJson;
        }

        public void setTenderDocumentIdsJson(String tenderDocumentIdsJson) {
            this.tenderDocumentIdsJson = tenderDocumentIdsJson;
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

    /** 清单详情（含条目）。 */
    class ChecklistDetail extends ChecklistSummary {
        private List<ItemView> items = new ArrayList<>();
        /** 建清单 LLM 原始输出（仅详情）。 */
        private String llmRawJson;

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

        public String getLlmRawJson() {
            return llmRawJson;
        }

        public void setLlmRawJson(String llmRawJson) {
            this.llmRawJson = llmRawJson;
        }
    }

    /** 条目视图。 */
    class ItemView {
        private long id;
        private long checklistId;
        private String dimensionCode;
        private String text;
        private String sourceExcerpt;
        private Integer pageNo;
        private String chunkIdsJson;
        private boolean must;
        private int sortOrder;
        private String title;
        private String mainType;
        private String subType;
        private String bidSearchJson;
        private String evidenceJson;
        private java.math.BigDecimal scoreTotal;
        private String scorePartsJson;
        private Boolean scoreSumMatches;
        /** 产品编号，如 Q1 / S1。 */
        private String requirementCode;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getChecklistId() {
            return checklistId;
        }

        public void setChecklistId(long checklistId) {
            this.checklistId = checklistId;
        }

        public String getDimensionCode() {
            return dimensionCode;
        }

        public void setDimensionCode(String dimensionCode) {
            this.dimensionCode = dimensionCode;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getSourceExcerpt() {
            return sourceExcerpt;
        }

        public void setSourceExcerpt(String sourceExcerpt) {
            this.sourceExcerpt = sourceExcerpt;
        }

        public Integer getPageNo() {
            return pageNo;
        }

        public void setPageNo(Integer pageNo) {
            this.pageNo = pageNo;
        }

        public String getChunkIdsJson() {
            return chunkIdsJson;
        }

        public void setChunkIdsJson(String chunkIdsJson) {
            this.chunkIdsJson = chunkIdsJson;
        }

        public boolean isMust() {
            return must;
        }

        public void setMust(boolean must) {
            this.must = must;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
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

        public String getBidSearchJson() {
            return bidSearchJson;
        }

        public void setBidSearchJson(String bidSearchJson) {
            this.bidSearchJson = bidSearchJson;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public java.math.BigDecimal getScoreTotal() {
            return scoreTotal;
        }

        public void setScoreTotal(java.math.BigDecimal scoreTotal) {
            this.scoreTotal = scoreTotal;
        }

        public String getScorePartsJson() {
            return scorePartsJson;
        }

        public void setScorePartsJson(String scorePartsJson) {
            this.scorePartsJson = scorePartsJson;
        }

        public Boolean getScoreSumMatches() {
            return scoreSumMatches;
        }

        public void setScoreSumMatches(Boolean scoreSumMatches) {
            this.scoreSumMatches = scoreSumMatches;
        }

        public String getRequirementCode() {
            return requirementCode;
        }

        public void setRequirementCode(String requirementCode) {
            this.requirementCode = requirementCode;
        }
    }
}
