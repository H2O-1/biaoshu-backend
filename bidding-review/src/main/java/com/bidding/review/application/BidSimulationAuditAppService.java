package com.bidding.review.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.BidSimulationAuditPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageCommandPort;
import com.bidding.common.port.TenderAnalysisDocumentCommandPort;
import com.bidding.common.security.BidderAuth;
import com.bidding.common.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 投标模拟审核应用服务。
 */
@Service
public class BidSimulationAuditAppService {

    private static final int MAX_NAME_LENGTH = 256;

    private final BidSimulationAuditPort bidSimulationAuditPort;
    private final PackageCommandPort packageCommandPort;
    private final TenderAnalysisDocumentCommandPort documentCommandPort;
    private final JobPort jobPort;
    private final ObjectMapper objectMapper;

    public BidSimulationAuditAppService(BidSimulationAuditPort bidSimulationAuditPort,
                                        PackageCommandPort packageCommandPort,
                                        TenderAnalysisDocumentCommandPort documentCommandPort,
                                        JobPort jobPort,
                                        ObjectMapper objectMapper) {
        this.bidSimulationAuditPort = bidSimulationAuditPort;
        this.packageCommandPort = packageCommandPort;
        this.documentCommandPort = documentCommandPort;
        this.jobPort = jobPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建投标模拟审核任务：内部招标包 + 内部投标包 + 文件上传 + INDEX 入队 + BID_SIMULATION_AUDIT 总任务。
     */
    @Transactional
    public CreateResult create(String name,
                               List<MultipartFile> tenderFiles,
                               List<String> tenderFileTypes,
                               List<MultipartFile> bidFiles,
                               List<String> bidFileTypes) {
        CurrentUser operator = BidderAuth.requireBidder("仅投标方可操作投标模拟审核");
        List<UploadFileCommand> tenderUploadFiles =
                requireUploadFiles(tenderFiles, tenderFileTypes, "招标文件");
        List<UploadFileCommand> bidUploadFiles =
                requireUploadFiles(bidFiles, bidFileTypes, "投标文件");
        String firstBidFileName = safeOriginalFilename(bidUploadFiles.get(0).getFile());
        String safeName = resolveName(name, firstBidFileName);

        long tenderPackageId = packageCommandPort.createUnboundTenderPackage(
                operator.getOrgId(), safeName, operator.getUserId());
        long bidPackageId = packageCommandPort.createUnboundBidPackage(
                operator.getOrgId(), safeName, operator.getUserId());

        List<FileResult> tenderFileResults = new ArrayList<>();
        List<FileResult> bidFileResults = new ArrayList<>();
        List<Long> tenderDocumentIds = new ArrayList<>();
        List<Long> bidDocumentIds = new ArrayList<>();
        List<Long> indexJobIds = new ArrayList<>();

        uploadFiles(operator, tenderPackageId, tenderUploadFiles, tenderFileResults, tenderDocumentIds, indexJobIds);
        uploadFiles(operator, bidPackageId, bidUploadFiles, bidFileResults, bidDocumentIds, indexJobIds);

        String tenderDocumentIdsJson = toJsonLongArray(tenderDocumentIds);
        String bidDocumentIdsJson = toJsonLongArray(bidDocumentIds);
        long auditId = bidSimulationAuditPort.createBuilding(
                operator.getOrgId(),
                safeName,
                tenderPackageId,
                bidPackageId,
                tenderDocumentIdsJson,
                bidDocumentIdsJson,
                operator.getUserId()
        );

        String payload = buildPayload(
                auditId,
                null,
                tenderPackageId,
                bidPackageId,
                tenderDocumentIdsJson,
                bidDocumentIdsJson,
                toJsonLongArray(indexJobIds));
        long jobId = jobPort.insert(
                operator.getOrgId(),
                null,
                JobPort.TYPE_BID_SIMULATION_AUDIT,
                JobPort.STATUS_PENDING,
                payload,
                operator.getUserId()
        );
        bidSimulationAuditPort.bindJob(auditId, jobId);

        CreateResult result = new CreateResult();
        result.setAuditId(auditId);
        result.setJobId(jobId);
        result.setTenderPackageId(tenderPackageId);
        result.setBidPackageId(bidPackageId);
        result.setTenderDocumentIds(tenderDocumentIds);
        result.setBidDocumentIds(bidDocumentIds);
        result.setIndexJobIds(indexJobIds);
        result.setTenderFiles(tenderFileResults);
        result.setBidFiles(bidFileResults);
        return result;
    }

    /**
     * 重新发起投标模拟审核：旧记录作废，复用原内部包和文档新建一条审核记录与总任务。
     */
    @Transactional
    public CreateResult rerun(long auditId) {
        CurrentUser operator = BidderAuth.requireBidder("仅投标方可操作投标模拟审核");
        BidSimulationAuditPort.AuditSummary existing =
                bidSimulationAuditPort.findById(operator.getOrgId(), auditId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投标模拟审核记录不存在");
        }
        if (BidSimulationAuditPort.STATUS_BUILDING.equals(existing.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "投标模拟审核进行中，请稍后重试");
        }
        if (existing.getTenderPackageId() < 1 || existing.getBidPackageId() < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "投标模拟审核记录缺少内部包信息");
        }

        List<Long> tenderDocumentIds =
                parseRequiredLongArray(existing.getTenderDocumentIdsJson(), "投标模拟审核记录缺少招标文件信息");
        List<Long> bidDocumentIds =
                parseRequiredLongArray(existing.getBidDocumentIdsJson(), "投标模拟审核记录缺少投标文件信息");

        int updated = bidSimulationAuditPort.markObsolete(operator.getOrgId(), auditId);
        if (updated < 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "投标模拟审核记录不存在");
        }

        long newAuditId = bidSimulationAuditPort.createBuilding(
                operator.getOrgId(),
                existing.getName(),
                existing.getTenderPackageId(),
                existing.getBidPackageId(),
                existing.getTenderDocumentIdsJson(),
                existing.getBidDocumentIdsJson(),
                operator.getUserId()
        );
        String payload = buildPayload(
                newAuditId,
                auditId,
                existing.getTenderPackageId(),
                existing.getBidPackageId(),
                existing.getTenderDocumentIdsJson(),
                existing.getBidDocumentIdsJson(),
                "[]");
        long jobId = jobPort.insert(
                operator.getOrgId(),
                null,
                JobPort.TYPE_BID_SIMULATION_AUDIT,
                JobPort.STATUS_PENDING,
                payload,
                operator.getUserId()
        );
        bidSimulationAuditPort.bindJob(newAuditId, jobId);

        CreateResult result = new CreateResult();
        result.setAuditId(newAuditId);
        result.setJobId(jobId);
        result.setTenderPackageId(existing.getTenderPackageId());
        result.setBidPackageId(existing.getBidPackageId());
        result.setTenderDocumentIds(tenderDocumentIds);
        result.setBidDocumentIds(bidDocumentIds);
        result.setIndexJobIds(new ArrayList<>());
        result.setTenderFiles(new ArrayList<>());
        result.setBidFiles(new ArrayList<>());
        return result;
    }

    /**
     * 查询本组织投标模拟审核历史记录。
     */
    @Transactional(readOnly = true)
    public PageResult<BidSimulationAuditPort.AuditSummary> list(String keyword, String status, int page, int size) {
        CurrentUser operator = BidderAuth.requireBidder("仅投标方可操作投标模拟审核");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();
        String safeStatus = normalizeListStatus(status);

        List<BidSimulationAuditPort.AuditSummary> items =
                bidSimulationAuditPort.listByOrg(operator.getOrgId(), kw, safeStatus, offset, safeSize);
        long total = bidSimulationAuditPort.countByOrg(operator.getOrgId(), kw, safeStatus);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    /**
     * 查询本组织投标模拟审核详情。
     */
    @Transactional(readOnly = true)
    public BidSimulationAuditPort.AuditDetail get(long auditId) {
        CurrentUser operator = BidderAuth.requireBidder("仅投标方可操作投标模拟审核");
        BidSimulationAuditPort.AuditDetail detail =
                bidSimulationAuditPort.findDetail(operator.getOrgId(), auditId);
        if (detail == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投标模拟审核记录不存在");
        }
        return detail;
    }

    /**
     * 逻辑删除本组织投标模拟审核历史记录；不删除内部包、源文件和索引产物。
     */
    @Transactional
    public void delete(long auditId) {
        CurrentUser operator = BidderAuth.requireBidder("仅投标方可操作投标模拟审核");
        int updated = bidSimulationAuditPort.markObsolete(operator.getOrgId(), auditId);
        if (updated < 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "投标模拟审核记录不存在");
        }
    }

    private String normalizeListStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (BidSimulationAuditPort.STATUS_BUILDING.equals(normalized)
                || BidSimulationAuditPort.STATUS_SUCCESS.equals(normalized)
                || BidSimulationAuditPort.STATUS_FAILED.equals(normalized)) {
            return normalized;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "status 仅支持 BUILDING / SUCCESS / FAILED");
    }

    private void uploadFiles(CurrentUser operator,
                             long packageId,
                             List<UploadFileCommand> uploadFiles,
                             List<FileResult> fileResults,
                             List<Long> documentIds,
                             List<Long> indexJobIds) {
        for (UploadFileCommand uploadFile : uploadFiles) {
            TenderAnalysisDocumentCommandPort.UploadResult uploaded =
                    documentCommandPort.uploadAndIndex(
                            operator.getOrgId(),
                            packageId,
                            uploadFile.getFileType(),
                            uploadFile.getFile(),
                            operator.getUserId());
            FileResult fileResult = new FileResult();
            fileResult.setDocumentId(uploaded.getDocumentId());
            fileResult.setIndexJobId(uploaded.getJobId());
            fileResult.setFileName(uploaded.getFileName());
            fileResult.setFileType(uploaded.getFileType());
            fileResults.add(fileResult);
            documentIds.add(uploaded.getDocumentId());
            indexJobIds.add(uploaded.getJobId());
        }
    }

    private List<UploadFileCommand> requireUploadFiles(List<MultipartFile> files,
                                                       List<String> fileTypes,
                                                       String label) {
        if (files == null || files.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的" + label);
        }
        if (fileTypes == null || fileTypes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请为每个" + label + "选择文件类型");
        }
        if (files.size() != fileTypes.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "数量与文件类型数量不一致");
        }
        List<UploadFileCommand> result = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的" + label);
            }
            String fileType = normalizeFileType(fileTypes.get(i));
            result.add(new UploadFileCommand(file, fileType));
        }
        return result;
    }

    private String normalizeFileType(String fileType) {
        if (fileType == null || fileType.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件类型不能为空");
        }
        String trimmed = fileType.trim();
        if (trimmed.length() > 128) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件类型过长");
        }
        return trimmed;
    }

    private String resolveName(String name, String firstBidFileName) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            value = firstBidFileName;
        }
        if (value == null || value.trim().isEmpty()) {
            value = "未命名投标模拟审核";
        }
        value = value.trim();
        if (value.length() > MAX_NAME_LENGTH) {
            value = value.substring(0, MAX_NAME_LENGTH);
        }
        return value;
    }

    private String safeOriginalFilename(MultipartFile file) {
        String original = file == null ? null : file.getOriginalFilename();
        if (original == null || original.trim().isEmpty()) {
            return "未命名投标模拟审核";
        }
        String fileName = original.trim();
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slash >= 0 && slash < fileName.length() - 1) {
            fileName = fileName.substring(slash + 1);
        }
        return fileName;
    }

    private String toJsonLongArray(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? new ArrayList<>() : ids);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "序列化投标模拟审核文件 ID 失败");
        }
    }

    private String buildPayload(long auditId,
                                Long sourceAuditId,
                                long tenderPackageId,
                                long bidPackageId,
                                String tenderDocumentIdsJson,
                                String bidDocumentIdsJson,
                                String indexJobIdsJson) {
        StringBuilder payload = new StringBuilder();
        payload.append("{\"auditId\":").append(auditId);
        if (sourceAuditId != null) {
            payload.append(",\"sourceAuditId\":").append(sourceAuditId);
        }
        payload.append(",\"tenderPackageId\":").append(tenderPackageId)
                .append(",\"bidPackageId\":").append(bidPackageId)
                .append(",\"tenderDocumentIds\":").append(tenderDocumentIdsJson)
                .append(",\"bidDocumentIds\":").append(bidDocumentIdsJson)
                .append(",\"indexJobIds\":").append(indexJobIdsJson)
                .append("}");
        return payload.toString();
    }

    private List<Long> parseRequiredLongArray(String json, String errorMessage) {
        if (json == null || json.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, errorMessage);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray() || node.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, errorMessage);
            }
            List<Long> result = new ArrayList<>();
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                if (item == null || !item.canConvertToLong()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, errorMessage);
                }
                result.add(item.asLong());
            }
            return result;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, errorMessage);
        }
    }

    /** 创建任务结果。 */
    public static class CreateResult {
        private long auditId;
        private long jobId;
        private long tenderPackageId;
        private long bidPackageId;
        private List<Long> tenderDocumentIds = new ArrayList<>();
        private List<Long> bidDocumentIds = new ArrayList<>();
        private List<Long> indexJobIds = new ArrayList<>();
        private List<FileResult> tenderFiles = new ArrayList<>();
        private List<FileResult> bidFiles = new ArrayList<>();

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

        public List<FileResult> getTenderFiles() {
            return tenderFiles;
        }

        public void setTenderFiles(List<FileResult> tenderFiles) {
            this.tenderFiles = tenderFiles == null ? new ArrayList<>() : tenderFiles;
        }

        public List<FileResult> getBidFiles() {
            return bidFiles;
        }

        public void setBidFiles(List<FileResult> bidFiles) {
            this.bidFiles = bidFiles == null ? new ArrayList<>() : bidFiles;
        }
    }

    /** 单文件入队结果。 */
    public static class FileResult {
        private long documentId;
        private long indexJobId;
        private String fileName;
        private String fileType;

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
        }

        public long getIndexJobId() {
            return indexJobId;
        }

        public void setIndexJobId(long indexJobId) {
            this.indexJobId = indexJobId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getFileType() {
            return fileType;
        }

        public void setFileType(String fileType) {
            this.fileType = fileType;
        }
    }

    private static class UploadFileCommand {
        private final MultipartFile file;
        private final String fileType;

        UploadFileCommand(MultipartFile file, String fileType) {
            this.file = file;
            this.fileType = fileType;
        }

        MultipartFile getFile() {
            return file;
        }

        String getFileType() {
            return fileType;
        }
    }
}
