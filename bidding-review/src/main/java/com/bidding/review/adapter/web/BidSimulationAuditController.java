package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.review.application.BidSimulationAuditAppService;
import com.bidding.review.application.BidSimulationAuditExportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 投标模拟审核 API。
 */
@RestController
@RequestMapping("/api/bid-simulation-audits")
public class BidSimulationAuditController {

    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BidSimulationAuditAppService bidSimulationAuditAppService;
    private final BidSimulationAuditExportService bidSimulationAuditExportService;
    private final DocumentAccessPort documentAccessPort;
    private final ObjectMapper objectMapper;

    public BidSimulationAuditController(BidSimulationAuditAppService bidSimulationAuditAppService,
                                        BidSimulationAuditExportService bidSimulationAuditExportService,
                                        DocumentAccessPort documentAccessPort,
                                        ObjectMapper objectMapper) {
        this.bidSimulationAuditAppService = bidSimulationAuditAppService;
        this.bidSimulationAuditExportService = bidSimulationAuditExportService;
        this.documentAccessPort = documentAccessPort;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CreateResponse> create(
            @RequestParam(required = false, defaultValue = "") String name,
            @RequestParam("tenderFileTypes") List<String> tenderFileTypes,
            @RequestPart("tenderFiles") List<MultipartFile> tenderFiles,
            @RequestParam("bidFileTypes") List<String> bidFileTypes,
            @RequestPart("bidFiles") List<MultipartFile> bidFiles) {
        BidSimulationAuditAppService.CreateResult created =
                bidSimulationAuditAppService.create(name, tenderFiles, tenderFileTypes, bidFiles, bidFileTypes);
        return ApiResponse.ok(toCreateResponse(created));
    }

    @PostMapping("/{auditId}/rerun")
    public ApiResponse<CreateResponse> rerun(@PathVariable("auditId") long auditId) {
        BidSimulationAuditAppService.CreateResult rerun = bidSimulationAuditAppService.rerun(auditId);
        return ApiResponse.ok(toCreateResponse(rerun));
    }

    private CreateResponse toCreateResponse(BidSimulationAuditAppService.CreateResult created) {
        CreateResponse response = new CreateResponse();
        response.setAuditId(created.getAuditId());
        response.setJobId(created.getJobId());
        response.setTenderPackageId(created.getTenderPackageId());
        response.setBidPackageId(created.getBidPackageId());
        response.setTenderDocumentIds(created.getTenderDocumentIds());
        response.setBidDocumentIds(created.getBidDocumentIds());
        response.setIndexJobIds(created.getIndexJobIds());
        response.setTenderFiles(created.getTenderFiles());
        response.setBidFiles(created.getBidFiles());
        return response;
    }

    @GetMapping
    public ApiResponse<PageResult<AuditSummaryDto>> list(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        PageResult<BidSimulationAuditPort.AuditSummary> result =
                bidSimulationAuditAppService.list(keyword, status, page, size);
        List<AuditSummaryDto> items = new ArrayList<>();
        for (BidSimulationAuditPort.AuditSummary item : result.getItems()) {
            items.add(toSummaryDto(item));
        }
        return ApiResponse.ok(new PageResult<>(
                items, result.getTotal(), result.getPage(), result.getSize()));
    }

    @GetMapping("/{auditId}")
    public ApiResponse<AuditDetailDto> get(@PathVariable("auditId") long auditId) {
        return ApiResponse.ok(toDetailDto(bidSimulationAuditAppService.get(auditId)));
    }

    @GetMapping("/{auditId}/export")
    public ResponseEntity<byte[]> export(@PathVariable("auditId") long auditId) {
        BidSimulationAuditExportService.ExportFile file = bidSimulationAuditExportService.exportAudit(auditId);
        String encodedFileName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(file.getContent());
    }

    @DeleteMapping("/{auditId}")
    public ApiResponse<Void> delete(@PathVariable("auditId") long auditId) {
        bidSimulationAuditAppService.delete(auditId);
        return ApiResponse.ok(null);
    }

    private AuditSummaryDto toSummaryDto(BidSimulationAuditPort.AuditSummary s) {
        AuditSummaryDto dto = new AuditSummaryDto();
        dto.setId(s.getId());
        dto.setOrgId(s.getOrgId());
        dto.setName(s.getName());
        dto.setStatus(s.getStatus());
        dto.setJobId(s.getJobId());
        dto.setTenderPackageId(s.getTenderPackageId());
        dto.setBidPackageId(s.getBidPackageId());
        dto.setTenderDocumentIds(parseLongArray(s.getTenderDocumentIdsJson()));
        dto.setBidDocumentIds(parseLongArray(s.getBidDocumentIdsJson()));
        dto.setTenderFileCount(dto.getTenderDocumentIds().size());
        dto.setBidFileCount(dto.getBidDocumentIds().size());
        dto.setQualificationTotal(s.getQualificationTotal());
        dto.setQualificationPassCount(s.getQualificationPassCount());
        dto.setQualificationFailCount(s.getQualificationFailCount());
        dto.setQualificationManualCount(s.getQualificationManualCount());
        dto.setQualificationPassRate(formatPercent(s.getQualificationPassRate()));
        dto.setScoreSuggested(s.getScoreSuggested());
        dto.setScoreTotal(s.getScoreTotal());
        dto.setLogicIssueCount(s.getLogicIssueCount());
        dto.setTypoCount(s.getTypoCount());
        dto.setErrorMessage(s.getErrorMessage());
        dto.setCreatedBy(s.getCreatedBy());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }

    private AuditDetailDto toDetailDto(BidSimulationAuditPort.AuditDetail detail) {
        AuditSummaryDto base = toSummaryDto(detail);
        AuditDetailDto dto = new AuditDetailDto();
        dto.setId(base.getId());
        dto.setOrgId(base.getOrgId());
        dto.setName(base.getName());
        dto.setStatus(base.getStatus());
        dto.setJobId(base.getJobId());
        dto.setTenderPackageId(base.getTenderPackageId());
        dto.setBidPackageId(base.getBidPackageId());
        dto.setTenderDocumentIds(base.getTenderDocumentIds());
        dto.setBidDocumentIds(base.getBidDocumentIds());
        dto.setTenderFileCount(base.getTenderFileCount());
        dto.setBidFileCount(base.getBidFileCount());
        dto.setQualificationTotal(base.getQualificationTotal());
        dto.setQualificationPassCount(base.getQualificationPassCount());
        dto.setQualificationFailCount(base.getQualificationFailCount());
        dto.setQualificationManualCount(base.getQualificationManualCount());
        dto.setQualificationPassRate(base.getQualificationPassRate());
        dto.setScoreSuggested(base.getScoreSuggested());
        dto.setScoreTotal(base.getScoreTotal());
        dto.setLogicIssueCount(base.getLogicIssueCount());
        dto.setTypoCount(base.getTypoCount());
        dto.setErrorMessage(base.getErrorMessage());
        dto.setCreatedBy(base.getCreatedBy());
        dto.setCreatedAt(base.getCreatedAt());
        dto.setUpdatedAt(base.getUpdatedAt());

        SummaryDto summary = new SummaryDto();
        summary.setQualificationTotal(base.getQualificationTotal());
        summary.setQualificationPassCount(base.getQualificationPassCount());
        summary.setQualificationFailCount(base.getQualificationFailCount());
        summary.setQualificationManualCount(base.getQualificationManualCount());
        summary.setQualificationPassRate(base.getQualificationPassRate());
        summary.setScoreSuggested(base.getScoreSuggested());
        summary.setScoreTotal(base.getScoreTotal());
        summary.setLogicIssueCount(base.getLogicIssueCount());
        summary.setTypoCount(base.getTypoCount());
        dto.setSummary(summary);
        BasicInfoDto basicInfo = new BasicInfoDto();
        basicInfo.setTenderer(detail.getTenderer());
        basicInfo.setAgency(detail.getAgency());
        basicInfo.setBudgetAmountText(detail.getBudgetAmountText());
        basicInfo.setBidDeadlineText(detail.getBidDeadlineText());
        basicInfo.setBidDeadlineAt(detail.getBidDeadlineAt());
        basicInfo.setEvidence(parseJsonArray(detail.getBasicInfoEvidenceJson()));
        dto.setBasicInfo(basicInfo);
        dto.setTenderDocuments(toDocumentDtos(base.getOrgId(), base.getTenderPackageId()));
        dto.setBidDocuments(toDocumentDtos(base.getOrgId(), base.getBidPackageId()));

        for (BidSimulationAuditPort.ItemView item : detail.getItems()) {
            AuditItemDto itemDto = toItemDto(item);
            if (BidSimulationAuditPort.ITEM_SCORING.equals(item.getItemType())) {
                dto.getScoringItems().add(itemDto);
            } else if (BidSimulationAuditPort.ITEM_LOGIC_ISSUE.equals(item.getItemType())) {
                dto.getLogicIssueItems().add(itemDto);
            } else if (BidSimulationAuditPort.ITEM_TYPO.equals(item.getItemType())) {
                dto.getTypoItems().add(itemDto);
            } else {
                dto.getQualificationItems().add(itemDto);
            }
        }
        return dto;
    }

    private List<DocumentDto> toDocumentDtos(long orgId, long packageId) {
        List<DocumentDto> result = new ArrayList<>();
        List<DocumentAccessPort.DocumentRef> docs = documentAccessPort.listActiveByPackageId(packageId);
        if (docs == null) {
            return result;
        }
        for (DocumentAccessPort.DocumentRef doc : docs) {
            if (doc == null || doc.getOrgId() != orgId) {
                continue;
            }
            DocumentDto dto = new DocumentDto();
            dto.setDocumentId(doc.getId());
            dto.setPackageId(doc.getPackageId());
            dto.setFileName(doc.getFileName());
            dto.setFileExt(doc.getFileExt());
            dto.setIndexStatus(doc.getIndexStatus());
            result.add(dto);
        }
        return result;
    }

    private AuditItemDto toItemDto(BidSimulationAuditPort.ItemView item) {
        AuditItemDto dto = new AuditItemDto();
        dto.setId(item.getId());
        dto.setAuditId(item.getAuditId());
        dto.setItemType(item.getItemType());
        dto.setRequirementCode(item.getRequirementCode());
        dto.setTitle(item.getTitle());
        dto.setContent(item.getContent());
        dto.setResult(item.getResult());
        dto.setResultLabel(resultLabel(item.getResult()));
        dto.setReason(item.getReason());
        dto.setSuggestedScore(item.getSuggestedScore());
        dto.setFullScore(item.getFullScore());
        dto.setSubType(item.getSubType());
        dto.setSubTypeLabel(toSubTypeLabel(item.getSubType(),
                BidSimulationAuditPort.ITEM_SCORING.equals(item.getItemType())));
        dto.setSourceExcerpt(item.getSourceExcerpt());
        dto.setPageNo(item.getPageNo());
        dto.setChunkIds(parseLongArray(item.getChunkIdsJson()));
        JsonNode evidence = parseJsonArray(item.getEvidenceJson());
        dto.setEvidence(evidence);
        applyDisplayFields(dto, evidence);
        dto.setRawJson(item.getRawJson());
        dto.setSortOrder(item.getSortOrder());
        dto.setCreatedAt(item.getCreatedAt());
        return dto;
    }

    private void applyDisplayFields(AuditItemDto dto, JsonNode evidence) {
        if (dto == null) {
            return;
        }
        JsonNode first = firstEvidence(evidence);
        if (BidSimulationAuditPort.ITEM_LOGIC_ISSUE.equals(dto.getItemType())) {
            String severity = upperText(textOf(first, "severity"));
            dto.setSeverity(severity);
            dto.setSeverityLabel(severityLabel(severity));
            String issueType = upperText(firstNonBlank(textOf(first, "issueType"), dto.getSubType()));
            dto.setIssueType(issueType);
            dto.setIssueTypeLabel(issueTypeLabel(issueType, dto.getItemType()));
            dto.setSuggestion(textOf(first, "suggestion"));
        } else if (BidSimulationAuditPort.ITEM_TYPO.equals(dto.getItemType())) {
            String severity = upperText(firstNonBlank(textOf(first, "severity"), "LOW"));
            dto.setSeverity(severity);
            dto.setSeverityLabel(severityLabel(severity));
            dto.setIssueType(BidSimulationAuditPort.ITEM_TYPO);
            dto.setIssueTypeLabel("错别字");
            dto.setWrongText(textOf(first, "wrongText"));
            dto.setSuggestedText(textOf(first, "suggestedText"));
            String confidence = upperText(textOf(first, "confidence"));
            dto.setConfidence(confidence);
            dto.setConfidenceLabel(confidenceLabel(confidence));
            dto.setSuggestion(textOf(first, "suggestion"));
        }
    }

    private String formatPercent(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String resultLabel(String result) {
        if (BidSimulationAuditPort.RESULT_PASS.equals(result)) {
            return "符合";
        }
        if (BidSimulationAuditPort.RESULT_FAIL.equals(result)) {
            return "不符合";
        }
        if (BidSimulationAuditPort.RESULT_MANUAL.equals(result)) {
            return "待人工确认";
        }
        return null;
    }

    private JsonNode firstEvidence(JsonNode evidence) {
        if (evidence == null || !evidence.isArray() || evidence.isEmpty()) {
            return null;
        }
        JsonNode first = evidence.get(0);
        return first != null && first.isObject() ? first : null;
    }

    private String textOf(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text.trim();
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.trim().isEmpty() ? second : first;
    }

    private String upperText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String severityLabel(String severity) {
        if ("HIGH".equals(severity)) {
            return "高风险";
        }
        if ("MEDIUM".equals(severity)) {
            return "中风险";
        }
        if ("LOW".equals(severity)) {
            return "低风险";
        }
        return null;
    }

    private String confidenceLabel(String confidence) {
        if ("HIGH".equals(confidence)) {
            return "高置信";
        }
        if ("MEDIUM".equals(confidence)) {
            return "中置信";
        }
        if ("LOW".equals(confidence)) {
            return "低置信";
        }
        return null;
    }

    private String issueTypeLabel(String issueType, String itemType) {
        if (BidSimulationAuditPort.ITEM_TYPO.equals(itemType)) {
            return "错别字";
        }
        if ("AMOUNT_INCONSISTENCY".equals(issueType)) {
            return "金额逻辑";
        }
        if ("TIME_CONFLICT".equals(issueType)) {
            return "时间冲突";
        }
        if ("SCORE_SUM_MISMATCH".equals(issueType)) {
            return "评分分值逻辑";
        }
        if ("TERM_INCONSISTENCY".equals(issueType)) {
            return "期限逻辑";
        }
        if ("THRESHOLD_INCONSISTENCY".equals(issueType)) {
            return "门槛条件逻辑";
        }
        if ("QUALIFICATION_REJECT_LOGIC".equals(issueType)) {
            return "资格与废标逻辑";
        }
        if ("OTHER".equals(issueType)) {
            return "逻辑问题";
        }
        return issueType == null ? null : "逻辑问题";
    }

    private String toSubTypeLabel(String subType, boolean scoring) {
        if (subType == null || subType.isBlank()) {
            return scoring ? "评分项" : "资格要求";
        }
        String code = subType.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
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
        return subType.trim();
    }

    private List<Long> parseLongArray(String json) {
        List<Long> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return result;
            }
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                if (item != null && item.canConvertToLong()) {
                    result.add(item.asLong());
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON 返回空数组
        }
        return result;
    }

    private JsonNode parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isArray()) {
                return node;
            }
        } catch (Exception ignored) {
            // 非法 JSON 返回空数组
        }
        return objectMapper.createArrayNode();
    }

    public static class AuditSummaryDto {
        private long id;
        private long orgId;
        private String name;
        private String status;
        private Long jobId;
        private long tenderPackageId;
        private long bidPackageId;
        private List<Long> tenderDocumentIds = new ArrayList<>();
        private List<Long> bidDocumentIds = new ArrayList<>();
        private int tenderFileCount;
        private int bidFileCount;
        private int qualificationTotal;
        private int qualificationPassCount;
        private int qualificationFailCount;
        private int qualificationManualCount;
        private String qualificationPassRate;
        private BigDecimal scoreSuggested;
        private BigDecimal scoreTotal;
        private int logicIssueCount;
        private int typoCount;
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

        public List<Long> getTenderDocumentIds() {
            return tenderDocumentIds;
        }

        public void setTenderDocumentIds(List<Long> tenderDocumentIds) {
            this.tenderDocumentIds = tenderDocumentIds == null ? new ArrayList<>() : tenderDocumentIds;
        }

        public List<Long> getBidDocumentIds() {
            return bidDocumentIds;
        }

        public void setBidDocumentIds(List<Long> bidDocumentIds) {
            this.bidDocumentIds = bidDocumentIds == null ? new ArrayList<>() : bidDocumentIds;
        }

        public int getTenderFileCount() {
            return tenderFileCount;
        }

        public void setTenderFileCount(int tenderFileCount) {
            this.tenderFileCount = tenderFileCount;
        }

        public int getBidFileCount() {
            return bidFileCount;
        }

        public void setBidFileCount(int bidFileCount) {
            this.bidFileCount = bidFileCount;
        }

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

        public String getQualificationPassRate() {
            return qualificationPassRate;
        }

        public void setQualificationPassRate(String qualificationPassRate) {
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

    public static class CreateResponse {
        private long auditId;
        private long jobId;
        private long tenderPackageId;
        private long bidPackageId;
        private List<Long> tenderDocumentIds = new ArrayList<>();
        private List<Long> bidDocumentIds = new ArrayList<>();
        private List<Long> indexJobIds = new ArrayList<>();
        private List<BidSimulationAuditAppService.FileResult> tenderFiles = new ArrayList<>();
        private List<BidSimulationAuditAppService.FileResult> bidFiles = new ArrayList<>();

        public long getAuditId() {
            return auditId;
        }

        public void setAuditId(long auditId) {
            this.auditId = auditId;
        }

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
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

        public List<Long> getTenderDocumentIds() {
            return tenderDocumentIds;
        }

        public void setTenderDocumentIds(List<Long> tenderDocumentIds) {
            this.tenderDocumentIds = tenderDocumentIds == null ? new ArrayList<>() : tenderDocumentIds;
        }

        public List<Long> getBidDocumentIds() {
            return bidDocumentIds;
        }

        public void setBidDocumentIds(List<Long> bidDocumentIds) {
            this.bidDocumentIds = bidDocumentIds == null ? new ArrayList<>() : bidDocumentIds;
        }

        public List<Long> getIndexJobIds() {
            return indexJobIds;
        }

        public void setIndexJobIds(List<Long> indexJobIds) {
            this.indexJobIds = indexJobIds == null ? new ArrayList<>() : indexJobIds;
        }

        public List<BidSimulationAuditAppService.FileResult> getTenderFiles() {
            return tenderFiles;
        }

        public void setTenderFiles(List<BidSimulationAuditAppService.FileResult> tenderFiles) {
            this.tenderFiles = tenderFiles == null ? new ArrayList<>() : tenderFiles;
        }

        public List<BidSimulationAuditAppService.FileResult> getBidFiles() {
            return bidFiles;
        }

        public void setBidFiles(List<BidSimulationAuditAppService.FileResult> bidFiles) {
            this.bidFiles = bidFiles == null ? new ArrayList<>() : bidFiles;
        }
    }

    public static class AuditDetailDto extends AuditSummaryDto {
        private SummaryDto summary;
        private BasicInfoDto basicInfo;
        private List<AuditItemDto> qualificationItems = new ArrayList<>();
        private List<AuditItemDto> scoringItems = new ArrayList<>();
        private List<AuditItemDto> logicIssueItems = new ArrayList<>();
        private List<AuditItemDto> typoItems = new ArrayList<>();
        private List<DocumentDto> tenderDocuments = new ArrayList<>();
        private List<DocumentDto> bidDocuments = new ArrayList<>();

        public SummaryDto getSummary() {
            return summary;
        }

        public void setSummary(SummaryDto summary) {
            this.summary = summary;
        }

        public BasicInfoDto getBasicInfo() {
            return basicInfo;
        }

        public void setBasicInfo(BasicInfoDto basicInfo) {
            this.basicInfo = basicInfo;
        }

        public List<AuditItemDto> getQualificationItems() {
            return qualificationItems;
        }

        public void setQualificationItems(List<AuditItemDto> qualificationItems) {
            this.qualificationItems = qualificationItems == null ? new ArrayList<>() : qualificationItems;
        }

        public List<AuditItemDto> getScoringItems() {
            return scoringItems;
        }

        public void setScoringItems(List<AuditItemDto> scoringItems) {
            this.scoringItems = scoringItems == null ? new ArrayList<>() : scoringItems;
        }

        public List<AuditItemDto> getLogicIssueItems() {
            return logicIssueItems;
        }

        public void setLogicIssueItems(List<AuditItemDto> logicIssueItems) {
            this.logicIssueItems = logicIssueItems == null ? new ArrayList<>() : logicIssueItems;
        }

        public List<AuditItemDto> getTypoItems() {
            return typoItems;
        }

        public void setTypoItems(List<AuditItemDto> typoItems) {
            this.typoItems = typoItems == null ? new ArrayList<>() : typoItems;
        }

        public List<DocumentDto> getTenderDocuments() {
            return tenderDocuments;
        }

        public void setTenderDocuments(List<DocumentDto> tenderDocuments) {
            this.tenderDocuments = tenderDocuments == null ? new ArrayList<>() : tenderDocuments;
        }

        public List<DocumentDto> getBidDocuments() {
            return bidDocuments;
        }

        public void setBidDocuments(List<DocumentDto> bidDocuments) {
            this.bidDocuments = bidDocuments == null ? new ArrayList<>() : bidDocuments;
        }
    }

    public static class DocumentDto {
        private long documentId;
        private long packageId;
        private String fileName;
        private String fileExt;
        private String indexStatus;

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
        }

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
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

    public static class BasicInfoDto {
        private String tenderer;
        private String agency;
        private String budgetAmountText;
        private String bidDeadlineText;
        private Instant bidDeadlineAt;
        private JsonNode evidence;

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

        public JsonNode getEvidence() {
            return evidence;
        }

        public void setEvidence(JsonNode evidence) {
            this.evidence = evidence;
        }
    }

    public static class SummaryDto {
        private int qualificationTotal;
        private int qualificationPassCount;
        private int qualificationFailCount;
        private int qualificationManualCount;
        private String qualificationPassRate;
        private BigDecimal scoreSuggested;
        private BigDecimal scoreTotal;
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

        public String getQualificationPassRate() {
            return qualificationPassRate;
        }

        public void setQualificationPassRate(String qualificationPassRate) {
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

    public static class AuditItemDto {
        private long id;
        private long auditId;
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String result;
        private String resultLabel;
        private String reason;
        private BigDecimal suggestedScore;
        private BigDecimal fullScore;
        private String subType;
        private String subTypeLabel;
        private String sourceExcerpt;
        private Integer pageNo;
        private List<Long> chunkIds = new ArrayList<>();
        private JsonNode evidence;
        private String rawJson;
        private String severity;
        private String severityLabel;
        private String issueType;
        private String issueTypeLabel;
        private String suggestion;
        private String wrongText;
        private String suggestedText;
        private String confidence;
        private String confidenceLabel;
        private int sortOrder;
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

        public String getResultLabel() {
            return resultLabel;
        }

        public void setResultLabel(String resultLabel) {
            this.resultLabel = resultLabel;
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

        public String getSubTypeLabel() {
            return subTypeLabel;
        }

        public void setSubTypeLabel(String subTypeLabel) {
            this.subTypeLabel = subTypeLabel;
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
            this.chunkIds = chunkIds == null ? new ArrayList<>() : chunkIds;
        }

        public JsonNode getEvidence() {
            return evidence;
        }

        public void setEvidence(JsonNode evidence) {
            this.evidence = evidence;
        }

        public String getRawJson() {
            return rawJson;
        }

        public void setRawJson(String rawJson) {
            this.rawJson = rawJson;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getSeverityLabel() {
            return severityLabel;
        }

        public void setSeverityLabel(String severityLabel) {
            this.severityLabel = severityLabel;
        }

        public String getIssueType() {
            return issueType;
        }

        public void setIssueType(String issueType) {
            this.issueType = issueType;
        }

        public String getIssueTypeLabel() {
            return issueTypeLabel;
        }

        public void setIssueTypeLabel(String issueTypeLabel) {
            this.issueTypeLabel = issueTypeLabel;
        }

        public String getSuggestion() {
            return suggestion;
        }

        public void setSuggestion(String suggestion) {
            this.suggestion = suggestion;
        }

        public String getWrongText() {
            return wrongText;
        }

        public void setWrongText(String wrongText) {
            this.wrongText = wrongText;
        }

        public String getSuggestedText() {
            return suggestedText;
        }

        public void setSuggestedText(String suggestedText) {
            this.suggestedText = suggestedText;
        }

        public String getConfidence() {
            return confidence;
        }

        public void setConfidence(String confidence) {
            this.confidence = confidence;
        }

        public String getConfidenceLabel() {
            return confidenceLabel;
        }

        public void setConfidenceLabel(String confidenceLabel) {
            this.confidenceLabel = confidenceLabel;
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(Instant createdAt) {
            this.createdAt = createdAt;
        }
    }
}
