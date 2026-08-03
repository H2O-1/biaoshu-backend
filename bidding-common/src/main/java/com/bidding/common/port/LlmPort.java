package com.bidding.common.port;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话大模型（实现可在 ai 模块：百炼 chat completions）。
 * 建清单抽取等须强制 JSON 输出。
 */
public interface LlmPort {

    /**
     * 从候选原文归纳资格要求与评分项（提示词 extract-v2）。
     * 不按单一维度抽取；主类由模型判定。
     *
     * @param chunksText 已格式化的候选原文（须含 chunkId）
     * @return 解析后的条目 + 模型原始 content
     */
    ExtractChecklistResult extractChecklist(String chunksText);

    /** 建清单抽取结果（含 LLM 原文）。 */
    class ExtractChecklistResult {
        private List<ExtractedRequirement> items = new ArrayList<>();
        /** 模型 message.content 原文。 */
        private String rawContent;

        public List<ExtractedRequirement> getItems() {
            return items;
        }

        public void setItems(List<ExtractedRequirement> items) {
            if (items == null) {
                this.items = new ArrayList<>();
            } else {
                this.items = items;
            }
        }

        public String getRawContent() {
            return rawContent;
        }

        public void setRawContent(String rawContent) {
            this.rawContent = rawContent;
        }
    }

    /**
     * 对照单条招标要求与投标候选原文（提示词 review-v2，产品向输出）。
     */
    CompareResult compareRequirement(CompareRequest request);

    /** 单条对照请求。 */
    class CompareRequest {
        private String supplier;
        private String requirementId;
        private String title;
        /** QUALIFICATION / SCORING */
        private String mainType;
        private String content;
        private String tenderQuote;
        private java.math.BigDecimal fullScore;
        private String bidChunksText;

        public String getSupplier() {
            return supplier;
        }

        public void setSupplier(String supplier) {
            this.supplier = supplier;
        }

        public String getRequirementId() {
            return requirementId;
        }

        public void setRequirementId(String requirementId) {
            this.requirementId = requirementId;
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

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getTenderQuote() {
            return tenderQuote;
        }

        public void setTenderQuote(String tenderQuote) {
            this.tenderQuote = tenderQuote;
        }

        public java.math.BigDecimal getFullScore() {
            return fullScore;
        }

        public void setFullScore(java.math.BigDecimal fullScore) {
            this.fullScore = fullScore;
        }

        public String getBidChunksText() {
            return bidChunksText;
        }

        public void setBidChunksText(String bidChunksText) {
            this.bidChunksText = bidChunksText;
        }
    }

    /** 投标证据片段。 */
    class BidEvidence {
        private Long fragmentId;
        private Integer page;
        private String quote;

        public Long getFragmentId() {
            return fragmentId;
        }

        public void setFragmentId(Long fragmentId) {
            this.fragmentId = fragmentId;
        }

        public Integer getPage() {
            return page;
        }

        public void setPage(Integer page) {
            this.page = page;
        }

        public String getQuote() {
            return quote;
        }

        public void setQuote(String quote) {
            this.quote = quote;
        }
    }

    /** 单条抽取结果。 */
    class ExtractedRequirement {
        private String text;
        private String sourceExcerpt;
        private Integer pageNo;
        private List<Long> chunkIds = new ArrayList<>();
        private String category;
        private boolean must = true;
        private String title;
        private String mainType;
        private String subType;
        private List<String> bidSearch = new ArrayList<>();
        private String evidenceJson;
        private java.math.BigDecimal scoreTotal;
        private String scorePartsJson;
        private Boolean scoreSumMatches;

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

        public List<Long> getChunkIds() {
            return chunkIds;
        }

        public void setChunkIds(List<Long> chunkIds) {
            if (chunkIds == null) {
                this.chunkIds = new ArrayList<>();
            } else {
                this.chunkIds = chunkIds;
            }
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public boolean isMust() {
            return must;
        }

        public void setMust(boolean must) {
            this.must = must;
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

        public List<String> getBidSearch() {
            return bidSearch;
        }

        public void setBidSearch(List<String> bidSearch) {
            if (bidSearch == null) {
                this.bidSearch = new ArrayList<>();
            } else {
                this.bidSearch = bidSearch;
            }
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
    }

    /** 产品向对照结果（单条）。 */
    class CompareResult {
        private String requirementId;
        private String title;
        private String reviewType;
        /** 资格：符合/不符合/无法判断；评分项为 null */
        private String status;
        private Boolean vetoRisk;
        private String judgement;
        private List<BidEvidence> bidEvidence = new ArrayList<>();
        private String note;
        private java.math.BigDecimal suggestedScore;
        private java.math.BigDecimal fullScore;
        private String deductionReason;
        /** 模型 message.content 原文。 */
        private String rawContent;
        /** 派生字段：符合→PASS、不符合→FAIL、无法判断→UNCLEAR（落库用） */
        private String verdict;
        private String reason;
        private String bidExcerpt;
        private Integer pageNo;
        private Long chunkId;

        public String getRequirementId() {
            return requirementId;
        }

        public void setRequirementId(String requirementId) {
            this.requirementId = requirementId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
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

        public List<BidEvidence> getBidEvidence() {
            return bidEvidence;
        }

        public void setBidEvidence(List<BidEvidence> bidEvidence) {
            if (bidEvidence == null) {
                this.bidEvidence = new ArrayList<>();
            } else {
                this.bidEvidence = bidEvidence;
            }
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

        public String getRawContent() {
            return rawContent;
        }

        public void setRawContent(String rawContent) {
            this.rawContent = rawContent;
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
    }
}
