package com.bidding.common.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 投标模拟审核持久化端口。
 * <p>
 * 用于独立上传一个招标包和一个投标包后，保存资格响应、评分响应、逻辑问题和错别字识别结果。
 */
public interface BidSimulationAuditPort {

    String STATUS_BUILDING = "BUILDING";
    String STATUS_SUCCESS = "SUCCESS";
    String STATUS_FAILED = "FAILED";
    String STATUS_OBSOLETE = "OBSOLETE";

    String ITEM_QUALIFICATION = "QUALIFICATION";
    String ITEM_SCORING = "SCORING";
    String ITEM_LOGIC_ISSUE = "LOGIC_ISSUE";
    String ITEM_TYPO = "TYPO";

    String RESULT_PASS = "PASS";
    String RESULT_FAIL = "FAIL";
    String RESULT_MANUAL = "MANUAL";

    /**
     * 创建审核中主记录。
     *
     * @return auditId
     */
    long createBuilding(long orgId,
                        String name,
                        long tenderPackageId,
                        long bidPackageId,
                        String tenderDocumentIdsJson,
                        String bidDocumentIdsJson,
                        long createdBy);

    /**
     * 绑定对应异步任务 ID。
     */
    void bindJob(long auditId, long jobId);

    /**
     * 插入资格响应 / 评分响应 / 逻辑问题 / 错别字条目。
     */
    void insertItems(long auditId, List<ItemDraft> items);

    /**
     * 标记模拟审核成功，并写入汇总指标。
     */
    void markSuccess(long auditId, SummaryDraft summary, TenderAnalysisPort.BasicInfoDraft basicInfo);

    /**
     * 标记模拟审核失败。
     */
    void markFailed(long auditId, String errorMessage);

    /**
     * 逻辑删除模拟审核记录；只作废主记录，不删除源文件、内部包和 chunk/vector。
     *
     * @return 更新行数
     */
    int markObsolete(long orgId, long auditId);

    /**
     * 按组织分页查询历史记录。
     */
    List<AuditSummary> listByOrg(long orgId, String keyword, String status, int offset, int limit);

    /**
     * 按组织统计历史记录数量。
     */
    long countByOrg(long orgId, String keyword, String status);

    /**
     * 按组织查询主记录；不存在或不属于该组织返回 null。
     */
    AuditSummary findById(long orgId, long auditId);

    /**
     * 按组织查询详情，含条目；不存在或不属于该组织返回 null。
     */
    AuditDetail findDetail(long orgId, long auditId);

    /** 汇总指标草稿。 */
    class SummaryDraft {
        private int qualificationTotal;
        private int qualificationPassCount;
        private int qualificationFailCount;
        private int qualificationManualCount;
        private BigDecimal qualificationPassRate = BigDecimal.ZERO;
        private BigDecimal scoreSuggested = BigDecimal.ZERO;
        private BigDecimal scoreTotal = BigDecimal.ZERO;
        private int logicIssueCount;
        private int typoCount;

        public int getQualificationTotal() {
            return qualificationTotal;
        }

        public void setQualificationTotal(int qualificationTotal) {
            this.qualificationTotal = qualificationTotal;
        }

        public int getQualificationPassCount() {
            return qualificationPassCount;
        }

        public void setQualificationPassCount(int qualificationPassCount) {
            this.qualificationPassCount = qualificationPassCount;
        }

        public int getQualificationFailCount() {
            return qualificationFailCount;
        }

        public void setQualificationFailCount(int qualificationFailCount) {
            this.qualificationFailCount = qualificationFailCount;
        }

        public int getQualificationManualCount() {
            return qualificationManualCount;
        }

        public void setQualificationManualCount(int qualificationManualCount) {
            this.qualificationManualCount = qualificationManualCount;
        }

        public BigDecimal getQualificationPassRate() {
            return qualificationPassRate;
        }

        public void setQualificationPassRate(BigDecimal qualificationPassRate) {
            this.qualificationPassRate = qualificationPassRate;
        }

        public BigDecimal getScoreSuggested() {
            return scoreSuggested;
        }

        public void setScoreSuggested(BigDecimal scoreSuggested) {
            this.scoreSuggested = scoreSuggested;
        }

        public BigDecimal getScoreTotal() {
            return scoreTotal;
        }

        public void setScoreTotal(BigDecimal scoreTotal) {
            this.scoreTotal = scoreTotal;
        }

        public int getLogicIssueCount() {
            return logicIssueCount;
        }

        public void setLogicIssueCount(int logicIssueCount) {
            this.logicIssueCount = logicIssueCount;
        }

        public int getTypoCount() {
            return typoCount;
        }

        public void setTypoCount(int typoCount) {
            this.typoCount = typoCount;
        }
    }

    /** 条目草稿。 */
    class ItemDraft {
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String result;
        private String reason;
        private BigDecimal suggestedScore;
        private BigDecimal fullScore;
        private String subType;
        private String sourceExcerpt;
        private Integer pageNo;
        private String chunkIdsJson;
        private String evidenceJson;
        private String rawJson;
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

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public BigDecimal getSuggestedScore() {
            return suggestedScore;
        }

        public void setSuggestedScore(BigDecimal suggestedScore) {
            this.suggestedScore = suggestedScore;
        }

        public BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(BigDecimal fullScore) {
            this.fullScore = fullScore;
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

        public String getRawJson() {
            return rawJson;
        }

        public void setRawJson(String rawJson) {
            this.rawJson = rawJson;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }
    }

    /** 模拟审核记录摘要。 */
    class AuditSummary extends SummaryDraft {
        private long id;
        private long orgId;
        private String name;
        private String status;
        private Long jobId;
        private long tenderPackageId;
        private long bidPackageId;
        private String tenderDocumentIdsJson;
        private String bidDocumentIdsJson;
        private String tenderNo;
        private String tenderer;
        private String agency;
        private String budgetAmountText;
        private String bidDeadlineText;
        private Instant bidDeadlineAt;
        private String basicInfoEvidenceJson;
        private String basicInfoRawJson;
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

        public long getTenderPackageId() {
            return tenderPackageId;
        }

        public void setTenderPackageId(long tenderPackageId) {
            this.tenderPackageId = tenderPackageId;
        }

        public long getBidPackageId() {
            return bidPackageId;
        }

        public void setBidPackageId(long bidPackageId) {
            this.bidPackageId = bidPackageId;
        }

        public String getTenderDocumentIdsJson() {
            return tenderDocumentIdsJson;
        }

        public void setTenderDocumentIdsJson(String tenderDocumentIdsJson) {
            this.tenderDocumentIdsJson = tenderDocumentIdsJson;
        }

        public String getBidDocumentIdsJson() {
            return bidDocumentIdsJson;
        }

        public void setBidDocumentIdsJson(String bidDocumentIdsJson) {
            this.bidDocumentIdsJson = bidDocumentIdsJson;
        }

        public String getTenderNo() {
            return tenderNo;
        }

        public void setTenderNo(String tenderNo) {
            this.tenderNo = tenderNo;
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

        public String getBasicInfoEvidenceJson() {
            return basicInfoEvidenceJson;
        }

        public void setBasicInfoEvidenceJson(String basicInfoEvidenceJson) {
            this.basicInfoEvidenceJson = basicInfoEvidenceJson;
        }

        public String getBasicInfoRawJson() {
            return basicInfoRawJson;
        }

        public void setBasicInfoRawJson(String basicInfoRawJson) {
            this.basicInfoRawJson = basicInfoRawJson;
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

    /** 模拟审核详情。 */
    class AuditDetail extends AuditSummary {
        private List<ItemView> items = new ArrayList<>();

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
        private long auditId;
        private Instant createdAt;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getAuditId() {
            return auditId;
        }

        public void setAuditId(long auditId) {
            this.auditId = auditId;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }
}
