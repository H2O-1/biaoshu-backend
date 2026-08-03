package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ReviewPort;
import com.bidding.review.application.ReviewAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能审查 API（对照异步；查询同步）。
 */
@RestController
public class ReviewController {

    private final ReviewAppService reviewAppService;
    private final PackageLookupPort packageLookupPort;
    private final ObjectMapper objectMapper;

    public ReviewController(
            ReviewAppService reviewAppService,
            PackageLookupPort packageLookupPort,
            ObjectMapper objectMapper) {
        this.reviewAppService = reviewAppService;
        this.packageLookupPort = packageLookupPort;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/projects/{projectId}/reviews")
    public ApiResponse<CreateResponse> create(
            @PathVariable("projectId") long projectId,
            @RequestBody(required = false) CreateRequest request) {
        Long checklistId = null;
        List<Long> packageIds = null;
        if (request != null) {
            checklistId = request.getChecklistId();
            packageIds = request.getPackageIds();
        }
        ReviewAppService.CreateResult created =
                reviewAppService.create(projectId, checklistId, packageIds);
        CreateResponse resp = new CreateResponse();
        resp.setReviewId(created.getReviewId());
        resp.setJobId(created.getJobId());
        resp.setVersion(created.getVersion());
        return ApiResponse.ok(resp);
    }

    @GetMapping("/api/projects/{projectId}/reviews")
    public ApiResponse<List<ReviewSummaryDto>> list(@PathVariable("projectId") long projectId) {
        List<ReviewPort.ReviewSummary> rows = reviewAppService.list(projectId);
        List<ReviewSummaryDto> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            result.add(toSummaryDto(rows.get(i)));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/api/projects/{projectId}/reviews/{reviewId}")
    public ApiResponse<ReviewDetailDto> get(
            @PathVariable("projectId") long projectId,
            @PathVariable("reviewId") long reviewId) {
        return ApiResponse.ok(toDetailDto(reviewAppService.get(projectId, reviewId)));
    }

    private ReviewSummaryDto toSummaryDto(ReviewPort.ReviewSummary s) {
        ReviewSummaryDto dto = new ReviewSummaryDto();
        dto.setId(s.getId());
        dto.setOrgId(s.getOrgId());
        dto.setProjectId(s.getProjectId());
        dto.setChecklistId(s.getChecklistId());
        dto.setVersion(s.getVersion());
        dto.setStatus(s.getStatus());
        dto.setPackageIdsJson(s.getPackageIdsJson());
        dto.setOverallVerdict(s.getOverallVerdict());
        dto.setNeedHumanReview(s.isNeedHumanReview());
        dto.setPromptVersion(s.getPromptVersion());
        dto.setRecallTopK(s.getRecallTopK());
        dto.setRerankTopN(s.getRerankTopN());
        dto.setErrorMessage(s.getErrorMessage());
        dto.setCreatedBy(s.getCreatedBy());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }

    private ReviewDetailDto toDetailDto(ReviewPort.ReviewDetail detail) {
        ReviewDetailDto dto = new ReviewDetailDto();
        ReviewSummaryDto base = toSummaryDto(detail);
        dto.setId(base.getId());
        dto.setOrgId(base.getOrgId());
        dto.setProjectId(base.getProjectId());
        dto.setChecklistId(base.getChecklistId());
        dto.setVersion(base.getVersion());
        dto.setStatus(base.getStatus());
        dto.setPackageIdsJson(base.getPackageIdsJson());
        dto.setOverallVerdict(base.getOverallVerdict());
        dto.setNeedHumanReview(base.isNeedHumanReview());
        dto.setPromptVersion(base.getPromptVersion());
        dto.setRecallTopK(base.getRecallTopK());
        dto.setRerankTopN(base.getRerankTopN());
        dto.setErrorMessage(base.getErrorMessage());
        dto.setCreatedBy(base.getCreatedBy());
        dto.setCreatedAt(base.getCreatedAt());
        dto.setUpdatedAt(base.getUpdatedAt());

        Map<Long, SupplierReviewDto> byPackage = new LinkedHashMap<>();
        List<ReviewPort.ItemView> src = detail.getItems();
        if (src != null) {
            for (int i = 0; i < src.size(); i++) {
                ReviewPort.ItemView v = src.get(i);
                Long packageId = v.getPackageId();
                if (packageId == null) {
                    continue;
                }
                SupplierReviewDto supplierDto = byPackage.get(packageId);
                if (supplierDto == null) {
                    supplierDto = new SupplierReviewDto();
                    supplierDto.setPackageId(packageId);
                    PackageLookupPort.PackageRef pkg = packageLookupPort.findActiveById(packageId);
                    supplierDto.setSupplier(pkg == null || pkg.getName() == null
                            ? ("包" + packageId) : pkg.getName());
                    byPackage.put(packageId, supplierDto);
                }
                if (ReviewPort.MAIN_SCORING.equals(v.getMainType())) {
                    supplierDto.getScoringResponses().add(toScoringDto(v));
                } else {
                    supplierDto.getQualificationResponses().add(toQualificationDto(v));
                }
            }
        }
        List<SupplierReviewDto> packages = new ArrayList<>();
        for (Map.Entry<Long, SupplierReviewDto> e : byPackage.entrySet()) {
            SupplierReviewDto pkg = e.getValue();
            fillPackageScoreTotals(pkg);
            packages.add(pkg);
        }
        dto.setPackages(packages);
        return dto;
    }

    /**
     * 包级建议分/满分：对该包 scoringResponses 求和（仅累加非 null 项）。
     * 前端卡片「建议分 x/y」用这两个字段，不要拿单条冒充整包。
     */
    static void fillPackageScoreTotals(SupplierReviewDto pkg) {
        if (pkg == null) {
            return;
        }
        List<ScoringResponseDto> scoring = pkg.getScoringResponses();
        if (scoring == null || scoring.isEmpty()) {
            pkg.setSuggestedScoreTotal(null);
            pkg.setFullScoreTotal(null);
            return;
        }
        BigDecimal suggestedSum = null;
        BigDecimal fullSum = null;
        for (int i = 0; i < scoring.size(); i++) {
            ScoringResponseDto item = scoring.get(i);
            if (item.getSuggestedScore() != null) {
                if (suggestedSum == null) {
                    suggestedSum = BigDecimal.ZERO;
                }
                suggestedSum = suggestedSum.add(item.getSuggestedScore());
            }
            if (item.getFullScore() != null) {
                if (fullSum == null) {
                    fullSum = BigDecimal.ZERO;
                }
                fullSum = fullSum.add(item.getFullScore());
            }
        }
        pkg.setSuggestedScoreTotal(suggestedSum);
        pkg.setFullScoreTotal(fullSum);
    }

    private QualificationResponseDto toQualificationDto(ReviewPort.ItemView v) {
        QualificationResponseDto dto = new QualificationResponseDto();
        dto.setId(v.getId());
        dto.setRequirementItemId(v.getRequirementItemId());
        dto.setRequirementId(v.getRequirementCode());
        dto.setTitle(v.getTitle());
        dto.setContent(v.getRequirementText());
        dto.setMainType(ReviewPort.MAIN_QUALIFICATION);
        dto.setMainTypeLabel(toMainTypeLabel(ReviewPort.MAIN_QUALIFICATION));
        dto.setSubType(toSubTypeLabel(v.getSubType(), false));
        dto.setReviewType(v.getReviewType());
        dto.setStatus(v.getStatus());
        dto.setVetoRisk(v.getVetoRisk());
        dto.setJudgement(v.getJudgement());
        dto.setBidEvidence(parseEvidence(v.getBidEvidenceJson()));
        dto.setNote(v.getNote());
        dto.setLlmRawJson(v.getLlmRawJson());
        return dto;
    }

    private ScoringResponseDto toScoringDto(ReviewPort.ItemView v) {
        ScoringResponseDto dto = new ScoringResponseDto();
        dto.setId(v.getId());
        dto.setRequirementItemId(v.getRequirementItemId());
        dto.setRequirementId(v.getRequirementCode());
        dto.setTitle(v.getTitle());
        dto.setContent(v.getRequirementText());
        dto.setMainType(ReviewPort.MAIN_SCORING);
        dto.setMainTypeLabel(toMainTypeLabel(ReviewPort.MAIN_SCORING));
        dto.setSubType(toSubTypeLabel(v.getSubType(), true));
        dto.setReviewType(v.getReviewType());
        dto.setVetoRisk(v.getVetoRisk());
        dto.setJudgement(v.getJudgement());
        dto.setSuggestedScore(v.getSuggestedScore());
        dto.setFullScore(v.getFullScore());
        dto.setDeductionReason(v.getDeductionReason());
        dto.setBidEvidence(parseEvidence(v.getBidEvidenceJson()));
        dto.setNote(v.getNote());
        dto.setLlmRawJson(v.getLlmRawJson());
        return dto;
    }

    /** 主类 code → 产品中文（资格要求 / 评分项）。 */
    static String toMainTypeLabel(String mainType) {
        if (ReviewPort.MAIN_SCORING.equals(mainType)) {
            return "评分项";
        }
        return "资格要求";
    }

    /**
     * 子类 code 转产品中文标签，供左栏小字展示。
     */
    static String toSubTypeLabel(String subType, boolean scoring) {
        if (subType == null || subType.isBlank()) {
            return scoring ? "评分项" : "资格要求";
        }
        String code = subType.trim().toUpperCase().replace('-', '_');
        if ("ELIGIBILITY".equals(code)) {
            return "资格要求";
        }
        if ("CERTIFICATE".equals(code)) {
            return "资质证书";
        }
        if ("PERSONNEL".equals(code)) {
            return "人员要求";
        }
        if ("REJECT".equals(code)) {
            return "废标否决";
        }
        if ("BUSINESS".equals(code) || "COMMERCIAL".equals(code)) {
            return scoring ? "商务评分" : "商务条款";
        }
        if ("TECH".equals(code)) {
            return "技术要求";
        }
        if ("PRICE".equals(code) || "QUOTE".equals(code)) {
            return "报价要求";
        }
        if ("FORMAT".equals(code)) {
            return "格式封装";
        }
        if ("EVIDENCE".equals(code)) {
            return "证明材料";
        }
        if ("E_BID".equals(code) || "EBID".equals(code)) {
            return "电子标流程";
        }
        if ("COMPLIANCE".equals(code)) {
            return "合规风险";
        }
        if ("TECH_SCORE".equals(code)) {
            return "技术评分";
        }
        if ("BUSINESS_SCORE".equals(code)) {
            return "商务评分";
        }
        if ("PRICE_SCORE".equals(code)) {
            return "价格评分";
        }
        if ("SERVICE_SCORE".equals(code)) {
            return "售后服务评分";
        }
        if ("PERFORMANCE_SCORE".equals(code)) {
            return "业绩评分";
        }
        if ("OTHER".equals(code)) {
            return scoring ? "评分项" : "资格要求";
        }
        // 已是中文或其他原样返回
        return subType.trim();
    }

    private List<BidEvidenceDto> parseEvidence(String json) {
        List<BidEvidenceDto> list = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return list;
        }
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (arr == null || !arr.isArray()) {
                return list;
            }
            for (int i = 0; i < arr.size(); i++) {
                JsonNode el = arr.get(i);
                if (el == null || !el.isObject()) {
                    continue;
                }
                BidEvidenceDto dto = new BidEvidenceDto();
                if (el.has("fragmentId") && !el.get("fragmentId").isNull()) {
                    dto.setFragmentId(el.get("fragmentId").asLong());
                }
                if (el.has("page") && !el.get("page").isNull()) {
                    dto.setPage(el.get("page").asInt());
                }
                if (el.has("quote") && !el.get("quote").isNull()) {
                    dto.setQuote(el.get("quote").asText());
                }
                list.add(dto);
            }
        } catch (Exception ignored) {
            // 非法 JSON 返回空
        }
        return list;
    }

    public static class CreateRequest {
        private Long checklistId;
        private List<Long> packageIds;

        public Long getChecklistId() {
            return checklistId;
        }

        public void setChecklistId(Long checklistId) {
            this.checklistId = checklistId;
        }

        public List<Long> getPackageIds() {
            return packageIds;
        }

        public void setPackageIds(List<Long> packageIds) {
            this.packageIds = packageIds;
        }
    }

    public static class CreateResponse {
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

    public static class ReviewSummaryDto {
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
        private java.time.Instant createdAt;
        private java.time.Instant updatedAt;

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

        public java.time.Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(java.time.Instant createdAt) {
            this.createdAt = createdAt;
        }

        public java.time.Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(java.time.Instant updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    public static class ReviewDetailDto extends ReviewSummaryDto {
        /** 按投标包嵌套的产品向结果。 */
        private List<SupplierReviewDto> packages = new ArrayList<>();

        public List<SupplierReviewDto> getPackages() {
            return packages;
        }

        public void setPackages(List<SupplierReviewDto> packages) {
            if (packages == null) {
                this.packages = new ArrayList<>();
            } else {
                this.packages = packages;
            }
        }
    }

    /** 单个投标单位（包）的审查结果。 */
    public static class SupplierReviewDto {
        private Long packageId;
        private String supplier;
        /** 该包评分项建议分合计（各条 suggestedScore 之和；全无则 null）。 */
        private BigDecimal suggestedScoreTotal;
        /** 该包评分项满分合计（各条 fullScore 之和；全无则 null）。 */
        private BigDecimal fullScoreTotal;
        private List<QualificationResponseDto> qualificationResponses = new ArrayList<>();
        private List<ScoringResponseDto> scoringResponses = new ArrayList<>();

        public Long getPackageId() {
            return packageId;
        }

        public void setPackageId(Long packageId) {
            this.packageId = packageId;
        }

        public String getSupplier() {
            return supplier;
        }

        public void setSupplier(String supplier) {
            this.supplier = supplier;
        }

        public BigDecimal getSuggestedScoreTotal() {
            return suggestedScoreTotal;
        }

        public void setSuggestedScoreTotal(BigDecimal suggestedScoreTotal) {
            this.suggestedScoreTotal = suggestedScoreTotal;
        }

        public BigDecimal getFullScoreTotal() {
            return fullScoreTotal;
        }

        public void setFullScoreTotal(BigDecimal fullScoreTotal) {
            this.fullScoreTotal = fullScoreTotal;
        }

        public List<QualificationResponseDto> getQualificationResponses() {
            return qualificationResponses;
        }

        public void setQualificationResponses(List<QualificationResponseDto> qualificationResponses) {
            if (qualificationResponses == null) {
                this.qualificationResponses = new ArrayList<>();
            } else {
                this.qualificationResponses = qualificationResponses;
            }
        }

        public List<ScoringResponseDto> getScoringResponses() {
            return scoringResponses;
        }

        public void setScoringResponses(List<ScoringResponseDto> scoringResponses) {
            if (scoringResponses == null) {
                this.scoringResponses = new ArrayList<>();
            } else {
                this.scoringResponses = scoringResponses;
            }
        }
    }

    public static class QualificationResponseDto {
        private long id;
        private long requirementItemId;
        private String requirementId;
        private String title;
        /** 招标要求正文（清单 content/text）。 */
        private String content;
        /** 主类 code：QUALIFICATION。 */
        private String mainType;
        /** 主类中文：资格要求。 */
        private String mainTypeLabel;
        /** 子类中文标签，左栏小字。 */
        private String subType;
        private String reviewType;
        private String status;
        private Boolean vetoRisk;
        private String judgement;
        private List<BidEvidenceDto> bidEvidence = new ArrayList<>();
        private String note;
        private String llmRawJson;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
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

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getMainType() {
            return mainType;
        }

        public void setMainType(String mainType) {
            this.mainType = mainType;
        }

        public String getMainTypeLabel() {
            return mainTypeLabel;
        }

        public void setMainTypeLabel(String mainTypeLabel) {
            this.mainTypeLabel = mainTypeLabel;
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

        public List<BidEvidenceDto> getBidEvidence() {
            return bidEvidence;
        }

        public void setBidEvidence(List<BidEvidenceDto> bidEvidence) {
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

        public String getLlmRawJson() {
            return llmRawJson;
        }

        public void setLlmRawJson(String llmRawJson) {
            this.llmRawJson = llmRawJson;
        }
    }

    public static class ScoringResponseDto {
        private long id;
        private long requirementItemId;
        private String requirementId;
        private String title;
        /** 招标要求正文（清单 content/text）。 */
        private String content;
        /** 主类 code：SCORING。 */
        private String mainType;
        /** 主类中文：评分项。 */
        private String mainTypeLabel;
        /** 子类中文标签，左栏小字。 */
        private String subType;
        private String reviewType;
        private Boolean vetoRisk;
        private String judgement;
        private BigDecimal suggestedScore;
        private BigDecimal fullScore;
        private String deductionReason;
        private List<BidEvidenceDto> bidEvidence = new ArrayList<>();
        private String note;
        private String llmRawJson;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getRequirementItemId() {
            return requirementItemId;
        }

        public void setRequirementItemId(long requirementItemId) {
            this.requirementItemId = requirementItemId;
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

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getMainType() {
            return mainType;
        }

        public void setMainType(String mainType) {
            this.mainType = mainType;
        }

        public String getMainTypeLabel() {
            return mainTypeLabel;
        }

        public void setMainTypeLabel(String mainTypeLabel) {
            this.mainTypeLabel = mainTypeLabel;
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

        public String getDeductionReason() {
            return deductionReason;
        }

        public void setDeductionReason(String deductionReason) {
            this.deductionReason = deductionReason;
        }

        public List<BidEvidenceDto> getBidEvidence() {
            return bidEvidence;
        }

        public void setBidEvidence(List<BidEvidenceDto> bidEvidence) {
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

        public String getLlmRawJson() {
            return llmRawJson;
        }

        public void setLlmRawJson(String llmRawJson) {
            this.llmRawJson = llmRawJson;
        }
    }

    public static class BidEvidenceDto {
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
}
