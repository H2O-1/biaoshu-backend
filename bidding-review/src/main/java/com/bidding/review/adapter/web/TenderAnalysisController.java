package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.review.application.TenderAnalysisAppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 招标解析 API：独立招标文件解析记录查询。
 */
@RestController
@RequestMapping("/api/tender-analyses")
public class TenderAnalysisController {

    private final TenderAnalysisAppService tenderAnalysisAppService;
    private final ObjectMapper objectMapper;

    public TenderAnalysisController(TenderAnalysisAppService tenderAnalysisAppService,
                                    ObjectMapper objectMapper) {
        this.tenderAnalysisAppService = tenderAnalysisAppService;
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
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        PageResult<TenderAnalysisPort.AnalysisSummary> result =
                tenderAnalysisAppService.list(keyword, page, size);
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

    @DeleteMapping("/{analysisId}")
    public ApiResponse<Void> delete(@PathVariable("analysisId") long analysisId) {
        tenderAnalysisAppService.delete(analysisId);
        return ApiResponse.ok(null);
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
        dto.setCreatedBy(s.getCreatedBy());
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
        dto.setEvidence(parseJsonArray(item.getEvidenceJson()));
        dto.setScoreTotal(item.getScoreTotal());
        dto.setScoreParts(parseJsonArray(item.getScorePartsJson()));
        dto.setScoreSumMatches(item.getScoreSumMatches());
        dto.setSortOrder(item.getSortOrder());
        dto.setCreatedAt(item.getCreatedAt());
        return dto;
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
