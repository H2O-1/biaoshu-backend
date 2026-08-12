package com.bidding.common.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 招标解析持久化端口。
 * <p>
 * 用于独立上传招标文件后，保存基础信息、资格要求、评分标准等解析结果。
 */
public interface TenderAnalysisPort {

    String STATUS_BUILDING = "BUILDING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    String STATUS_OBSOLETE = "OBSOLETE";

    String ITEM_QUALIFICATION = "QUALIFICATION";
    String ITEM_SCORING = "SCORING";

    /**
     * 创建解析中主记录。
     *
     * @return analysisId
     */
    long createBuilding(long orgId,
                        long packageId,
                        String name,
                        String documentIdsJson,
                        long createdBy);

    /**
     * 绑定对应异步任务 ID。
     */
    void bindJob(long analysisId, long jobId);

    /**
     * 插入资格要求 / 评分标准条目。
     */
    void insertItems(long analysisId, List<ItemDraft> items);

    /**
     * 标记解析成功，并写入基础信息与 LLM 原始输出。
     */
    void markSuccess(long analysisId, BasicInfoDraft basicInfo, String checklistRawJson);

    /**
     * 标记解析失败。
     */
    void markFailed(long analysisId, String errorMessage);

    /**
     * 逻辑删除解析记录；只作废主记录，不删除源文件、内部包和 chunk/vector。
     *
     * @return 更新行数
     */
    int markObsolete(long orgId, long analysisId);

    /**
     * 按组织分页查询历史记录。
     */
    List<AnalysisSummary> listByOrg(long orgId, String keyword, int offset, int limit);

    /**
     * 按组织统计历史记录数量。
     */
    long countByOrg(long orgId, String keyword);

    /**
     * 按组织查询主记录；不存在或不属于该组织返回 null。
     */
    AnalysisSummary findById(long orgId, long analysisId);

    /**
     * 按组织查询详情，含条目；不存在或不属于该组织返回 null。
     */
    AnalysisDetail findDetail(long orgId, long analysisId);

    /** 基础信息草稿。 */
    class BasicInfoDraft {
        private String tenderer;
        private String agency;
        private String budgetAmountText;
        private String bidDeadlineText;
        private Instant bidDeadlineAt;
        private String projectIntro;
        private String projectBackground;
        private String projectOverview;
        private String evidenceJson;
        private String rawJson;

        public String getTenderer() {
            return tenderer;
        }

        public void setTenderer(String tenderer) {
            this.tenderer = tenderer;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getBudgetAmountText() {
            return budgetAmountText;
        }

        public void setBudgetAmountText(String budgetAmountText) {
            this.budgetAmountText = budgetAmountText;
        }

        public String getBidDeadlineText() {
            return bidDeadlineText;
        }

        public void setBidDeadlineText(String bidDeadlineText) {
            this.bidDeadlineText = bidDeadlineText;
        }

        public Instant getBidDeadlineAt() {
            return bidDeadlineAt;
        }

        public void setBidDeadlineAt(Instant bidDeadlineAt) {
            this.bidDeadlineAt = bidDeadlineAt;
        }

        public String getProjectIntro() {
            return projectIntro;
        }

        public void setProjectIntro(String projectIntro) {
            this.projectIntro = projectIntro;
        }

        public String getProjectBackground() {
            return projectBackground;
        }

        public void setProjectBackground(String projectBackground) {
            this.projectBackground = projectBackground;
        }

        public String getProjectOverview() {
            return projectOverview;
        }

        public void setProjectOverview(String projectOverview) {
            this.projectOverview = projectOverview;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public String getRawJson() {
            return rawJson;
        }

        public void setRawJson(String rawJson) {
            this.rawJson = rawJson;
        }
    }

    /** 条目草稿。 */
    class ItemDraft {
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String subType;
        private String sourceExcerpt;
        private Integer pageNo;
        private String chunkIdsJson;
        private String evidenceJson;
        private BigDecimal scoreTotal;
        private String scorePartsJson;
        private Boolean scoreSumMatches;
        private int sortOrder;

        public String getItemType() {
            return itemType;
        }

        public void setItemType(String itemType) {
            this.itemType = itemType;
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

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getSubType() {
            return subType;
        }

        public void setSubType(String subType) {
            this.subType = subType;
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

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        public BigDecimal getScoreTotal() {
            return scoreTotal;
        }

        public void setScoreTotal(BigDecimal scoreTotal) {
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

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    /** 解析记录摘要。 */
    class AnalysisSummary {
        private long id;
        private long orgId;
        private long packageId;
        private String name;
        private String status;
        private Long jobId;
        private String documentIdsJson;
        private String tenderer;
        private String agency;
        private String budgetAmountText;
        private String bidDeadlineText;
        private Instant bidDeadlineAt;
        private String projectIntro;
        private String projectBackground;
        private String projectOverview;
        private String evidenceJson;
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

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Long getJobId() {
            return jobId;
        }

        public void setJobId(Long jobId) {
            this.jobId = jobId;
        }

        public String getDocumentIdsJson() {
            return documentIdsJson;
        }

        public void setDocumentIdsJson(String documentIdsJson) {
            this.documentIdsJson = documentIdsJson;
        }

        public String getTenderer() {
            return tenderer;
        }

        public void setTenderer(String tenderer) {
            this.tenderer = tenderer;
        }

        public String getAgency() {
            return agency;
        }

        public void setAgency(String agency) {
            this.agency = agency;
        }

        public String getBudgetAmountText() {
            return budgetAmountText;
        }

        public void setBudgetAmountText(String budgetAmountText) {
            this.budgetAmountText = budgetAmountText;
        }

        public String getBidDeadlineText() {
            return bidDeadlineText;
        }

        public void setBidDeadlineText(String bidDeadlineText) {
            this.bidDeadlineText = bidDeadlineText;
        }

        public Instant getBidDeadlineAt() {
            return bidDeadlineAt;
        }

        public void setBidDeadlineAt(Instant bidDeadlineAt) {
            this.bidDeadlineAt = bidDeadlineAt;
        }

        public String getProjectIntro() {
            return projectIntro;
        }

        public void setProjectIntro(String projectIntro) {
            this.projectIntro = projectIntro;
        }

        public String getProjectBackground() {
            return projectBackground;
        }

        public void setProjectBackground(String projectBackground) {
            this.projectBackground = projectBackground;
        }

        public String getProjectOverview() {
            return projectOverview;
        }

        public void setProjectOverview(String projectOverview) {
            this.projectOverview = projectOverview;
        }

        public String getEvidenceJson() {
            return evidenceJson;
        }

        public void setEvidenceJson(String evidenceJson) {
            this.evidenceJson = evidenceJson;
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

    /** 解析详情。 */
    class AnalysisDetail extends AnalysisSummary {
        private String basicInfoRawJson;
        private String checklistRawJson;
        private List<ItemView> items = new ArrayList<>();

        public String getBasicInfoRawJson() {
            return basicInfoRawJson;
        }

        public void setBasicInfoRawJson(String basicInfoRawJson) {
            this.basicInfoRawJson = basicInfoRawJson;
        }

        public String getChecklistRawJson() {
            return checklistRawJson;
        }

        public void setChecklistRawJson(String checklistRawJson) {
            this.checklistRawJson = checklistRawJson;
        }

        public List<ItemView> getItems() {
            return items;
        }

        public void setItems(List<ItemView> items) {
            this.items = items == null ? new ArrayList<>() : items;
        }
    }

    /** 条目视图。 */
    class ItemView extends ItemDraft {
        private long id;
        private long analysisId;
        private Instant createdAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getAnalysisId() {
            return analysisId;
        }

        public void setAnalysisId(long analysisId) {
            this.analysisId = analysisId;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }
}
