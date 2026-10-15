package com.bidding.review.application;

import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageCommandPort;
import com.bidding.common.port.TenderAnalysisDocumentCommandPort;
import com.bidding.common.port.TenderAnalysisPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.TendererAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 招标解析应用服务：独立招标文件解析创建与查询。
 */
@Service
public class TenderAnalysisAppService {

    private static final int MAX_NAME_LENGTH = 256;

    private final TenderAnalysisPort tenderAnalysisPort;
    private final PackageCommandPort packageCommandPort;
    private final TenderAnalysisDocumentCommandPort documentCommandPort;
    private final JobPort jobPort;
    private final ObjectMapper objectMapper;

    public TenderAnalysisAppService(TenderAnalysisPort tenderAnalysisPort,
                                    PackageCommandPort packageCommandPort,
                                    TenderAnalysisDocumentCommandPort documentCommandPort,
                                    JobPort jobPort,
                                    ObjectMapper objectMapper) {
        this.tenderAnalysisPort = tenderAnalysisPort;
        this.packageCommandPort = packageCommandPort;
        this.documentCommandPort = documentCommandPort;
        this.jobPort = jobPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建招标解析任务：内部招标包 + 文件上传 + INDEX 入队 + TENDER_ANALYSIS 总任务。
     */
    @Transactional
    public CreateResult create(String name, List<MultipartFile> files, List<String> fileTypes) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作招标解析");
        List<UploadFileCommand> uploadFiles = requireUploadFiles(files, fileTypes);
        String firstFileName = safeOriginalFilename(uploadFiles.get(0).getFile());
        String safeName = resolveName(name, firstFileName);

        long packageId = packageCommandPort.createUnboundTenderPackage(
                operator.getOrgId(), safeName, operator.getUserId());

        List<FileResult> fileResults = new ArrayList<>();
        List<Long> documentIds = new ArrayList<>();
        List<Long> indexJobIds = new ArrayList<>();
        for (int i = 0; i < uploadFiles.size(); i++) {
            UploadFileCommand uploadFile = uploadFiles.get(i);
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

        String documentIdsJson = toJsonLongArray(documentIds);
        long analysisId = tenderAnalysisPort.createBuilding(
                operator.getOrgId(), packageId, safeName, documentIdsJson, operator.getUserId());

        String payload = "{\"analysisId\":" + analysisId
                + ",\"packageId\":" + packageId
                + ",\"documentIds\":" + documentIdsJson
                + ",\"indexJobIds\":" + toJsonLongArray(indexJobIds)
                + "}";
        long jobId = jobPort.insert(
                operator.getOrgId(),
                null,
                JobPort.TYPE_TENDER_ANALYSIS,
                JobPort.STATUS_PENDING,
                payload,
                operator.getUserId()
        );
        tenderAnalysisPort.bindJob(analysisId, jobId);

        CreateResult result = new CreateResult();
        result.setAnalysisId(analysisId);
        result.setJobId(jobId);
        result.setPackageId(packageId);
        result.setDocumentIds(documentIds);
        result.setIndexJobIds(indexJobIds);
        result.setFiles(fileResults);
        return result;
    }

    /**
     * 查询本组织招标解析历史记录。
     */
    @Transactional(readOnly = true)
    public PageResult<TenderAnalysisPort.AnalysisSummary> list(String keyword, String status, int page, int size) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作招标解析");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null ? "" : keyword.trim();
        String safeStatus = normalizeListStatus(status);

        List<TenderAnalysisPort.AnalysisSummary> items =
                tenderAnalysisPort.listByOrg(operator.getOrgId(), kw, safeStatus, offset, safeSize);
        long total = tenderAnalysisPort.countByOrg(operator.getOrgId(), kw, safeStatus);
        return new PageResult<>(items, total, safePage, safeSize);
    }

    /**
     * 查询本组织招标解析详情。
     */
    @Transactional(readOnly = true)
    public TenderAnalysisPort.AnalysisDetail get(long analysisId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作招标解析");
        TenderAnalysisPort.AnalysisDetail detail =
                tenderAnalysisPort.findDetail(operator.getOrgId(), analysisId);
        if (detail == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "招标解析记录不存在");
        }
        return detail;
    }

    /**
     * 逻辑删除本组织招标解析历史记录；不删除内部包、源文件和索引产物。
     */
    @Transactional
    public void delete(long analysisId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作招标解析");
        int updated = tenderAnalysisPort.markObsolete(operator.getOrgId(), analysisId);
        if (updated < 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "招标解析记录不存在");
        }
    }

    /**
     * 重试招标解析：旧记录作废，新建一条 BUILDING 记录并重新创建总任务。
     */
    @Transactional
    public CreateResult rerun(long analysisId) {
        CurrentUser operator = TendererAuth.requireTenderer("仅招标方可操作招标解析");
        TenderAnalysisPort.AnalysisSummary existing =
                tenderAnalysisPort.findAnyById(operator.getOrgId(), analysisId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "招标解析记录不存在");
        }
        if (TenderAnalysisPort.STATUS_BUILDING.equals(existing.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "招标解析进行中，请稍后重试");
        }
        if (!TenderAnalysisPort.STATUS_OBSOLETE.equals(existing.getStatus())) {
            tenderAnalysisPort.markObsolete(operator.getOrgId(), analysisId);
        }

        String documentIdsJson = existing.getDocumentIdsJson();
        if (documentIdsJson == null || documentIdsJson.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "招标解析记录缺少文档信息");
        }

        Instant originalCreatedAt = existing.getOriginalCreatedAt() == null
                ? existing.getCreatedAt()
                : existing.getOriginalCreatedAt();
        if (originalCreatedAt == null) {
            throw new BizException(ErrorCode.CONFLICT, "招标解析记录缺少创建时间");
        }

        long newAnalysisId = tenderAnalysisPort.createRerunBuilding(
                operator.getOrgId(),
                existing.getPackageId(),
                existing.getName(),
                documentIdsJson,
                operator.getUserId(),
                analysisId,
                originalCreatedAt);

        String payload = "{\"analysisId\":" + newAnalysisId
                + ",\"sourceAnalysisId\":" + analysisId
                + ",\"packageId\":" + existing.getPackageId()
                + ",\"documentIds\":" + documentIdsJson
                + "}";
        long jobId = jobPort.insert(
                operator.getOrgId(),
                null,
                JobPort.TYPE_TENDER_ANALYSIS,
                JobPort.STATUS_PENDING,
                payload,
                operator.getUserId()
        );
        tenderAnalysisPort.bindJob(newAnalysisId, jobId);

        CreateResult result = new CreateResult();
        result.setAnalysisId(newAnalysisId);
        result.setJobId(jobId);
        result.setPackageId(existing.getPackageId());
        result.setDocumentIds(parseLongArray(documentIdsJson));
        result.setIndexJobIds(new ArrayList<>());
        result.setFiles(new ArrayList<>());
        return result;
    }

    private List<UploadFileCommand> requireUploadFiles(List<MultipartFile> files, List<String> fileTypes) {
        if (files == null || files.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的招标文件");
        }
        if (fileTypes == null || fileTypes.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请为每个招标文件选择文件类型");
        }
        if (files.size() != fileTypes.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件数量与文件类型数量不一致");
        }
        List<UploadFileCommand> result = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的招标文件");
            }
            String fileType = normalizeFileType(fileTypes.get(i));
            result.add(new UploadFileCommand(file, fileType));
        }
        return result;
    }

    private String normalizeListStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "";
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (TenderAnalysisPort.STATUS_BUILDING.equals(normalized)
                || TenderAnalysisPort.STATUS_SUCCESS.equals(normalized)
                || TenderAnalysisPort.STATUS_FAILED.equals(normalized)) {
            return normalized;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "status 仅支持 BUILDING / SUCCESS / FAILED");
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

    private String resolveName(String name, String firstFileName) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            value = firstFileName;
        }
        if (value == null || value.trim().isEmpty()) {
            value = "未命名招标解析";
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
            return "未命名招标解析";
        }
        String name = original.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name;
    }

    private String toJsonLongArray(List<Long> ids) {
        try {
            return objectMapper.writeValueAsString(ids == null ? new ArrayList<>() : ids);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.INTERNAL, "序列化招标解析文件 ID 失败");
        }
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

    /** 创建任务结果。 */
    public static class CreateResult {
        private long analysisId;
        private long jobId;
        private long packageId;
        private List<Long> documentIds = new ArrayList<>();
        private List<Long> indexJobIds = new ArrayList<>();
        private List<FileResult> files = new ArrayList<>();

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

        public List<FileResult> getFiles() {
            return files;
        }

        public void setFiles(List<FileResult> files) {
            this.files = files == null ? new ArrayList<>() : files;
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
