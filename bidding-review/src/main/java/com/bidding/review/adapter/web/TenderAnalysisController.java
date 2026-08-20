package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.review.application.TenderAnalysisAppService;
import com.bidding.review.application.TenderAnalysisExportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 招标解析 API：独立招标文件解析记录查询。
 */
@RestController
@RequestMapping("/api/tender-analyses")
public class TenderAnalysisController {

    private static final MediaType XLSX_MEDIA_TYPE =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TenderAnalysisAppService tenderAnalysisAppService;
    private final TenderAnalysisExportService tenderAnalysisExportService;
    private final ObjectMapper objectMapper;

    public TenderAnalysisController(TenderAnalysisAppService tenderAnalysisAppService,
                                    TenderAnalysisExportService tenderAnalysisExportService,
                                    ObjectMapper objectMapper) {
        this.tenderAnalysisAppService = tenderAnalysisAppService;
        this.tenderAnalysisExportService = tenderAnalysisExportService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CreateResponse> create(
            @RequestParam(required = false, defaultValue = "") String name,
            @RequestParam("fileTypes") List<String> fileTypes,
            @RequestPart("files") List<MultipartFile> files) {
        TenderAnalysisAppService.CreateResult created =
                tenderAnalysisAppService.create(name, files, fileTypes);
        CreateResponse response = new CreateResponse();
        response.setAnalysisId(created.getAnalysisId());
        response.setJobId(created.getJobId());
        response.setPackageId(created.getPackageId());
        response.setDocumentIds(created.getDocumentIds());
        response.setIndexJobIds(created.getIndexJobIds());
        response.setFiles(created.getFiles());
        return ApiResponse.ok(response);
    }

    @GetMapping
    public ApiResponse<PageResult<AnalysisSummaryDto>> list(
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        PageResult<TenderAnalysisPort.AnalysisSummary> result =
                tenderAnalysisAppService.list(keyword, status, page, size);
        List<AnalysisSummaryDto> items = new ArrayList<>();
        for (TenderAnalysisPort.AnalysisSummary item : result.getItems()) {
            items.add(toSummaryDto(item));
        }
        return ApiResponse.ok(new PageResult<>(
                items, result.getTotal(), result.getPage(), result.getSize()));
    }

    @GetMapping("/{analysisId}")
    public ApiResponse<AnalysisDetailDto> get(@PathVariable("analysisId") long analysisId) {
        return ApiResponse.ok(toDetailDto(tenderAnalysisAppService.get(analysisId)));
    }

    @GetMapping("/{analysisId}/export")
    public ResponseEntity<byte[]> export(@PathVariable("analysisId") long analysisId) {
        TenderAnalysisExportService.ExportFile file = tenderAnalysisExportService.exportAnalysis(analysisId);
        String encodedFileName = URLEncoder.encode(file.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(file.getContent());
    }

    @DeleteMapping("/{analysisId}")
    public ApiResponse<Void> delete(@PathVariable("analysisId") long analysisId) {
        tenderAnalysisAppService.delete(analysisId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{analysisId}/rerun")
    public ApiResponse<CreateResponse> rerun(@PathVariable("analysisId") long analysisId) {
        TenderAnalysisAppService.CreateResult rerun = tenderAnalysisAppService.rerun(analysisId);
        CreateResponse response = new CreateResponse();
        response.setAnalysisId(rerun.getAnalysisId());
        response.setJobId(rerun.getJobId());
        response.setPackageId(rerun.getPackageId());
        response.setDocumentIds(rerun.getDocumentIds());
        response.setIndexJobIds(rerun.getIndexJobIds());
        response.setFiles(rerun.getFiles());
        return ApiResponse.ok(response);
    }

    private AnalysisSummaryDto toSummaryDto(TenderAnalysisPort.AnalysisSummary s) {
        AnalysisSummaryDto dto = new AnalysisSummaryDto();
        dto.setId(s.getId());
        dto.setOrgId(s.getOrgId());
        dto.setPackageId(s.getPackageId());
        dto.setName(s.getName());
        dto.setStatus(s.getStatus());
        dto.setJobId(s.getJobId());
        dto.setDocumentIds(parseLongArray(s.getDocumentIdsJson()));
        dto.setFileCount(dto.getDocumentIds().size());
        dto.setTenderer(s.getTenderer());
        dto.setAgency(s.getAgency());
        dto.setBudgetAmountText(s.getBudgetAmountText());
        dto.setBidDeadlineText(s.getBidDeadlineText());
        dto.setBidDeadlineAt(s.getBidDeadlineAt());
        dto.setErrorMessage(s.getErrorMessage());
        dto.setRequirementCount(s.getRequirementCount());
        dto.setIssueCount(s.getIssueCount());
        dto.setCreatedBy(s.getCreatedBy());
        dto.setSourceAnalysisId(s.getSourceAnalysisId());
        dto.setOriginalCreatedAt(s.getOriginalCreatedAt());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }

    private AnalysisDetailDto toDetailDto(TenderAnalysisPort.AnalysisDetail detail) {
        AnalysisSummaryDto base = toSummaryDto(detail);
        AnalysisDetailDto dto = new AnalysisDetailDto();
        dto.setId(base.getId());
        dto.setOrgId(base.getOrgId());
        dto.setPackageId(base.getPackageId());
        dto.setName(base.getName());
        dto.setStatus(base.getStatus());
        dto.setJobId(base.getJobId());
        dto.setDocumentIds(base.getDocumentIds());
        dto.setFileCount(base.getFileCount());
        dto.setTenderer(base.getTenderer());
        dto.setAgency(base.getAgency());
        dto.setBudgetAmountText(base.getBudgetAmountText());
        dto.setBidDeadlineText(base.getBidDeadlineText());
        dto.setBidDeadlineAt(base.getBidDeadlineAt());
        dto.setErrorMessage(base.getErrorMessage());
        dto.setCreatedBy(base.getCreatedBy());
        dto.setSourceAnalysisId(base.getSourceAnalysisId());
        dto.setOriginalCreatedAt(base.getOriginalCreatedAt());
        dto.setCreatedAt(base.getCreatedAt());
        dto.setUpdatedAt(base.getUpdatedAt());

        BasicInfoDto basic = new BasicInfoDto();
        basic.setTenderer(detail.getTenderer());
        basic.setAgency(detail.getAgency());
        basic.setBudgetAmountText(detail.getBudgetAmountText());
        basic.setBidDeadlineText(detail.getBidDeadlineText());
        basic.setBidDeadlineAt(detail.getBidDeadlineAt());
        basic.setProjectIntro(detail.getProjectIntro());
        basic.setProjectBackground(detail.getProjectBackground());
        basic.setProjectOverview(detail.getProjectOverview());
        basic.setEvidence(parseJsonArray(detail.getEvidenceJson()));
        dto.setBasicInfo(basic);
        dto.setBasicInfoRawJson(detail.getBasicInfoRawJson());
        dto.setChecklistRawJson(detail.getChecklistRawJson());

        List<TenderAnalysisPort.ItemView> items = detail.getItems();
        for (TenderAnalysisPort.ItemView item : items) {
            AnalysisItemDto itemDto = toItemDto(item);
            if (TenderAnalysisPort.ITEM_SCORING.equals(item.getItemType())) {
                dto.getScoringItems().add(itemDto);
            } else if (TenderAnalysisPort.ITEM_LOGIC_ISSUE.equals(item.getItemType())) {
                dto.getLogicIssueItems().add(itemDto);
            } else if (TenderAnalysisPort.ITEM_TYPO.equals(item.getItemType())) {
                dto.getTypoItems().add(itemDto);
            } else {
                dto.getQualificationItems().add(itemDto);
            }
        }
        return dto;
    }

    private AnalysisItemDto toItemDto(TenderAnalysisPort.ItemView item) {
        AnalysisItemDto dto = new AnalysisItemDto();
        dto.setId(item.getId());
        dto.setAnalysisId(item.getAnalysisId());
        dto.setItemType(item.getItemType());
        dto.setRequirementCode(item.getRequirementCode());
        dto.setTitle(item.getTitle());
        dto.setContent(item.getContent());
        dto.setSubType(item.getSubType());
        dto.setSourceExcerpt(item.getSourceExcerpt());
        dto.setPageNo(item.getPageNo());
        dto.setChunkIds(parseLongArray(item.getChunkIdsJson()));
        JsonNode evidence = parseJsonArray(item.getEvidenceJson());
        dto.setEvidence(evidence);
        applyDisplayFields(dto, evidence);
        dto.setScoreTotal(item.getScoreTotal());
        dto.setScoreParts(parseJsonArray(item.getScorePartsJson()));
        dto.setScoreSumMatches(item.getScoreSumMatches());
        dto.setSortOrder(item.getSortOrder());
        dto.setCreatedAt(item.getCreatedAt());
        return dto;
    }

    private void applyDisplayFields(AnalysisItemDto dto, JsonNode evidence) {
        if (dto == null) {
            return;
        }
        JsonNode first = firstEvidence(evidence);
        if (TenderAnalysisPort.ITEM_LOGIC_ISSUE.equals(dto.getItemType())) {
            String severity = upperText(textOf(first, "severity"));
            dto.setSeverity(severity);
            dto.setSeverityLabel(severityLabel(severity));
            String issueType = upperText(firstNonBlank(textOf(first, "issueType"), dto.getSubType()));
            dto.setIssueType(issueType);
            dto.setIssueTypeLabel(issueTypeLabel(issueType, dto.getItemType()));
            dto.setSuggestion(textOf(first, "suggestion"));
        } else if (TenderAnalysisPort.ITEM_TYPO.equals(dto.getItemType())) {
            String severity = upperText(firstNonBlank(textOf(first, "severity"), "LOW"));
            dto.setSeverity(severity);
            dto.setSeverityLabel(severityLabel(severity));
            dto.setIssueType(TenderAnalysisPort.ITEM_TYPO);
            dto.setIssueTypeLabel("错别字");
            dto.setWrongText(textOf(first, "wrongText"));
            dto.setSuggestedText(textOf(first, "suggestedText"));
            String confidence = upperText(textOf(first, "confidence"));
            dto.setConfidence(confidence);
            dto.setConfidenceLabel(confidenceLabel(confidence));
            dto.setSuggestion(textOf(first, "suggestion"));
        }
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
        if (TenderAnalysisPort.ITEM_TYPO.equals(itemType)) {
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
            return "术语前后一致性";
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

    public static class AnalysisSummaryDto {
        private long id;
        private long orgId;
        private long packageId;
        private String name;
        private String status;
        private Long jobId;
        private List<Long> documentIds = new ArrayList<>();
        private int fileCount;
        private String tenderer;
        private String agency;
        private String budgetAmountText;
        private String bidDeadlineText;
        private Instant bidDeadlineAt;
        private String errorMessage;
        private int requirementCount;
        private int issueCount;
        private long createdBy;
        private Long sourceAnalysisId;
        private Instant originalCreatedAt;
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

        public List<Long> getDocumentIds() {
            return documentIds;
        }

        public void setDocumentIds(List<Long> documentIds) {
            this.documentIds = documentIds == null ? new ArrayList<>() : documentIds;
        }

        public int getFileCount() {
            return fileCount;
        }

        public void setFileCount(int fileCount) {
            this.fileCount = fileCount;
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

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public int getRequirementCount() {
            return requirementCount;
        }

        public void setRequirementCount(int requirementCount) {
            this.requirementCount = requirementCount;
        }

        public int getIssueCount() {
            return issueCount;
        }

        public void setIssueCount(int issueCount) {
            this.issueCount = issueCount;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Long getSourceAnalysisId() {
            return sourceAnalysisId;
        }

        public void setSourceAnalysisId(Long sourceAnalysisId) {
            this.sourceAnalysisId = sourceAnalysisId;
        }

        public Instant getOriginalCreatedAt() {
            return originalCreatedAt;
        }

        public void setOriginalCreatedAt(Instant originalCreatedAt) {
            this.originalCreatedAt = originalCreatedAt;
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
        private long analysisId;
        private long jobId;
        private long packageId;
        private List<Long> documentIds = new ArrayList<>();
        private List<Long> indexJobIds = new ArrayList<>();
        private List<TenderAnalysisAppService.FileResult> files = new ArrayList<>();

        public long getAnalysisId() {
            return analysisId;
        }

        public void setAnalysisId(long analysisId) {
            this.analysisId = analysisId;
        }

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
        }

        public long getPackageId() {
            return packageId;
        }

        public void setPackageId(long packageId) {
            this.packageId = packageId;
        }

        public List<Long> getDocumentIds() {
            return documentIds;
        }

        public void setDocumentIds(List<Long> documentIds) {
            this.documentIds = documentIds == null ? new ArrayList<>() : documentIds;
        }

        public List<Long> getIndexJobIds() {
            return indexJobIds;
        }

        public void setIndexJobIds(List<Long> indexJobIds) {
            this.indexJobIds = indexJobIds == null ? new ArrayList<>() : indexJobIds;
        }

        public List<TenderAnalysisAppService.FileResult> getFiles() {
            return files;
        }

        public void setFiles(List<TenderAnalysisAppService.FileResult> files) {
            this.files = files == null ? new ArrayList<>() : files;
        }
    }

    public static class AnalysisDetailDto extends AnalysisSummaryDto {
        private BasicInfoDto basicInfo;
        private List<AnalysisItemDto> qualificationItems = new ArrayList<>();
        private List<AnalysisItemDto> scoringItems = new ArrayList<>();
        private List<AnalysisItemDto> logicIssueItems = new ArrayList<>();
        private List<AnalysisItemDto> typoItems = new ArrayList<>();
        private String basicInfoRawJson;
        private String checklistRawJson;

        public BasicInfoDto getBasicInfo() {
            return basicInfo;
        }

        public void setBasicInfo(BasicInfoDto basicInfo) {
            this.basicInfo = basicInfo;
        }

        public List<AnalysisItemDto> getQualificationItems() {
            return qualificationItems;
        }

        public void setQualificationItems(List<AnalysisItemDto> qualificationItems) {
            this.qualificationItems = qualificationItems == null ? new ArrayList<>() : qualificationItems;
        }

        public List<AnalysisItemDto> getScoringItems() {
            return scoringItems;
        }

        public void setScoringItems(List<AnalysisItemDto> scoringItems) {
            this.scoringItems = scoringItems == null ? new ArrayList<>() : scoringItems;
        }

        public List<AnalysisItemDto> getLogicIssueItems() {
            return logicIssueItems;
        }

        public void setLogicIssueItems(List<AnalysisItemDto> logicIssueItems) {
            this.logicIssueItems = logicIssueItems == null ? new ArrayList<>() : logicIssueItems;
        }

        public List<AnalysisItemDto> getTypoItems() {
            return typoItems;
        }

        public void setTypoItems(List<AnalysisItemDto> typoItems) {
            this.typoItems = typoItems == null ? new ArrayList<>() : typoItems;
        }

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
    }

    public static class BasicInfoDto {
        private String tenderer;
        private String agency;
        private String budgetAmountText;
        private String bidDeadlineText;
        private Instant bidDeadlineAt;
        private String projectIntro;
        private String projectBackground;
        private String projectOverview;
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

        public JsonNode getEvidence() {
            return evidence;
        }

        public void setEvidence(JsonNode evidence) {
            this.evidence = evidence;
        }
    }

    public static class AnalysisItemDto {
        private long id;
        private long analysisId;
        private String itemType;
        private String requirementCode;
        private String title;
        private String content;
        private String subType;
        private String sourceExcerpt;
        private Integer pageNo;
        private List<Long> chunkIds = new ArrayList<>();
        private JsonNode evidence;
        private BigDecimal scoreTotal;
        private JsonNode scoreParts;
        private Boolean scoreSumMatches;
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

        public long getAnalysisId() {
            return analysisId;
        }

        public void setAnalysisId(long analysisId) {
            this.analysisId = analysisId;
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

        public BigDecimal getScoreTotal() {
            return scoreTotal;
        }

        public void setScoreTotal(BigDecimal scoreTotal) {
            this.scoreTotal = scoreTotal;
        }

        public JsonNode getScoreParts() {
            return scoreParts;
        }

        public void setScoreParts(JsonNode scoreParts) {
            this.scoreParts = scoreParts;
        }

        public Boolean getScoreSumMatches() {
            return scoreSumMatches;
        }

        public void setScoreSumMatches(Boolean scoreSumMatches) {
            this.scoreSumMatches = scoreSumMatches;
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
