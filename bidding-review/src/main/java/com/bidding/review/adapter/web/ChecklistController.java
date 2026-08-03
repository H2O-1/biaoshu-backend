package com.bidding.review.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.review.application.ChecklistAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 招标要求清单 API（建清单异步；查询同步）。
 */
@RestController
public class ChecklistController {

    private final ChecklistAppService checklistAppService;

    public ChecklistController(ChecklistAppService checklistAppService) {
        this.checklistAppService = checklistAppService;
    }

    @PostMapping("/api/projects/{projectId}/checklists")
    public ApiResponse<CreateResponse> create(
            @PathVariable("projectId") long projectId,
            @RequestBody(required = false) CreateRequest request) {
        List<String> dimensionCodes = null;
        List<Long> documentIds = null;
        if (request != null) {
            dimensionCodes = request.getDimensionCodes();
            documentIds = request.getDocumentIds();
        }
        ChecklistAppService.CreateResult created =
                checklistAppService.create(projectId, dimensionCodes, documentIds);
        CreateResponse resp = new CreateResponse();
        resp.setChecklistId(created.getChecklistId());
        resp.setJobId(created.getJobId());
        resp.setVersion(created.getVersion());
        return ApiResponse.ok(resp);
    }

    @GetMapping("/api/projects/{projectId}/checklists")
    public ApiResponse<List<ChecklistSummaryDto>> list(@PathVariable("projectId") long projectId) {
        List<RequirementChecklistPort.ChecklistSummary> rows = checklistAppService.list(projectId);
        List<ChecklistSummaryDto> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            result.add(toSummaryDto(rows.get(i)));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping("/api/projects/{projectId}/checklists/{checklistId}")
    public ApiResponse<ChecklistDetailDto> get(
            @PathVariable("projectId") long projectId,
            @PathVariable("checklistId") long checklistId) {
        RequirementChecklistPort.ChecklistDetail detail = checklistAppService.get(projectId, checklistId);
        return ApiResponse.ok(toDetailDto(detail));
    }

    private ChecklistSummaryDto toSummaryDto(RequirementChecklistPort.ChecklistSummary s) {
        ChecklistSummaryDto dto = new ChecklistSummaryDto();
        dto.setId(s.getId());
        dto.setOrgId(s.getOrgId());
        dto.setProjectId(s.getProjectId());
        dto.setVersion(s.getVersion());
        dto.setStatus(s.getStatus());
        dto.setDimensionCodesJson(s.getDimensionCodesJson());
        dto.setTenderDocumentIdsJson(s.getTenderDocumentIdsJson());
        dto.setPromptVersion(s.getPromptVersion());
        dto.setRecallTopK(s.getRecallTopK());
        dto.setRerankTopN(s.getRerankTopN());
        dto.setErrorMessage(s.getErrorMessage());
        dto.setCreatedBy(s.getCreatedBy());
        dto.setCreatedAt(s.getCreatedAt());
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }

    private ChecklistDetailDto toDetailDto(RequirementChecklistPort.ChecklistDetail detail) {
        ChecklistDetailDto dto = new ChecklistDetailDto();
        ChecklistSummaryDto base = toSummaryDto(detail);
        dto.setId(base.getId());
        dto.setOrgId(base.getOrgId());
        dto.setProjectId(base.getProjectId());
        dto.setVersion(base.getVersion());
        dto.setStatus(base.getStatus());
        dto.setDimensionCodesJson(base.getDimensionCodesJson());
        dto.setTenderDocumentIdsJson(base.getTenderDocumentIdsJson());
        dto.setPromptVersion(base.getPromptVersion());
        dto.setRecallTopK(base.getRecallTopK());
        dto.setRerankTopN(base.getRerankTopN());
        dto.setErrorMessage(base.getErrorMessage());
        dto.setCreatedBy(base.getCreatedBy());
        dto.setCreatedAt(base.getCreatedAt());
        dto.setUpdatedAt(base.getUpdatedAt());
        dto.setLlmRawJson(detail.getLlmRawJson());
        List<ItemDto> items = new ArrayList<>();
        List<RequirementChecklistPort.ItemView> src = detail.getItems();
        for (int i = 0; i < src.size(); i++) {
            RequirementChecklistPort.ItemView v = src.get(i);
            ItemDto item = new ItemDto();
            item.setId(v.getId());
            item.setChecklistId(v.getChecklistId());
            item.setDimensionCode(v.getDimensionCode());
            item.setText(v.getText());
            item.setSourceExcerpt(v.getSourceExcerpt());
            item.setPageNo(v.getPageNo());
            item.setChunkIdsJson(v.getChunkIdsJson());
            item.setMust(v.isMust());
            item.setSortOrder(v.getSortOrder());
            item.setTitle(v.getTitle());
            item.setMainType(v.getMainType());
            item.setSubType(v.getSubType());
            item.setBidSearchJson(v.getBidSearchJson());
            item.setEvidenceJson(v.getEvidenceJson());
            item.setScoreTotal(v.getScoreTotal());
            item.setScorePartsJson(v.getScorePartsJson());
            item.setScoreSumMatches(v.getScoreSumMatches());
            item.setRequirementCode(v.getRequirementCode());
            items.add(item);
        }
        dto.setItems(items);
        return dto;
    }

    public static class CreateRequest {
        /** 已忽略：建清单固定使用全部启用维度。 */
        private List<String> dimensionCodes;
        private List<Long> documentIds;

        public List<String> getDimensionCodes() {
            return dimensionCodes;
        }

        public void setDimensionCodes(List<String> dimensionCodes) {
            this.dimensionCodes = dimensionCodes;
        }

        public List<Long> getDocumentIds() {
            return documentIds;
        }

        public void setDocumentIds(List<Long> documentIds) {
            this.documentIds = documentIds;
        }
    }

    public static class CreateResponse {
        private long checklistId;
        private long jobId;
        private int version;

        public long getChecklistId() {
            return checklistId;
        }

        public void setChecklistId(long checklistId) {
            this.checklistId = checklistId;
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

    public static class ChecklistSummaryDto {
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

    public static class ChecklistDetailDto extends ChecklistSummaryDto {
        private List<ItemDto> items = new ArrayList<>();
        /** 建清单 LLM 原始输出（message.content）。 */
        private String llmRawJson;

        public List<ItemDto> getItems() {
            return items;
        }

        public void setItems(List<ItemDto> items) {
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

    public static class ItemDto {
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
