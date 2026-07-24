package com.bidding.file.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 标包下文件：上传 / 列表 / 删除 / 下载 / PDF 预览。
 * 仅招标方可操作；单文件 ≤ 配置上限；每包文件数有上限。
 * 预览仅支持 PDF；Word/Excel 请下载。
 */
@Service
public class DocumentAppService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String INDEX_PENDING = "PENDING";

    private static final Set<String> ALLOWED_EXT = new HashSet<>();

    static {
        ALLOWED_EXT.add("pdf");
        ALLOWED_EXT.add("doc");
        ALLOWED_EXT.add("docx");
        ALLOWED_EXT.add("xls");
        ALLOWED_EXT.add("xlsx");
    }

    private final DocumentRepository documentRepository;
    private final ObjectStoragePort objectStoragePort;
    private final PackageLookupPort packageLookupPort;
    private final long maxFileBytes;
    private final int maxFilesPerPackage;

    public DocumentAppService(DocumentRepository documentRepository,
                              ObjectStoragePort objectStoragePort,
                              PackageLookupPort packageLookupPort,
                              @Value("${bidding.file.max-size-bytes:52428800}") long maxFileBytes,
                              @Value("${bidding.file.max-files-per-package:5}") int maxFilesPerPackage) {
        this.documentRepository = documentRepository;
        this.objectStoragePort = objectStoragePort;
        this.packageLookupPort = packageLookupPort;
        this.maxFileBytes = maxFileBytes;
        this.maxFilesPerPackage = maxFilesPerPackage;
    }

    @Transactional
    public DocumentSummary upload(long packageId, MultipartFile file) {
        CurrentUser operator = checkTenderer();
        PackageLookupPort.PackageRef pkg = requireOwnedPackage(packageId, operator.getOrgId());

        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择要上传的文件");
        }

        long size = file.getSize();
        if (size <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (size > maxFileBytes) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单文件不能超过 50MB");
        }

        long existing = documentRepository.countActiveByPackageId(packageId);
        if (existing >= maxFilesPerPackage) {
            throw new BizException(ErrorCode.CONFLICT, "每个标包最多上传 " + maxFilesPerPackage + " 个文件");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名无效");
        }
        String safeName = sanitizeFileName(originalName.trim());
        String ext = extractExt(safeName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持 PDF / Word / Excel 文件");
        }

        String contentType = file.getContentType();
        String objectKey = buildObjectKey(operator.getOrgId(), packageId, safeName);

        try (InputStream in = file.getInputStream()) {
            objectStoragePort.put(objectKey, in, size, contentType);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取上传文件失败");
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL, "文件写入对象存储失败");
        }

        long id;
        try {
            id = documentRepository.insert(
                    operator.getOrgId(),
                    pkg.getProjectId(),
                    packageId,
                    safeName,
                    contentType,
                    ext,
                    size,
                    objectKey,
                    INDEX_PENDING,
                    STATUS_ACTIVE,
                    operator.getUserId()
            );
        } catch (RuntimeException e) {
            // 元数据失败则尽量删掉已上传对象，避免孤儿文件
            try {
                objectStoragePort.delete(objectKey);
            } catch (RuntimeException ignored) {
                // ignore
            }
            throw e;
        }

        return toSummary(requireOwnedDocument(id, operator.getOrgId()));
    }

    @Transactional(readOnly = true)
    public List<DocumentSummary> listByPackage(long packageId) {
        CurrentUser operator = checkTenderer();
        requireOwnedPackage(packageId, operator.getOrgId());

        List<Document> docs = documentRepository.listByPackageId(packageId);
        List<DocumentSummary> items = new ArrayList<>();
        for (Document doc : docs) {
            items.add(toSummary(doc));
        }
        return items;
    }

    /**
     * 删除文件：先删对象存储，再逻辑删元数据。
     */
    @Transactional
    public void delete(long packageId, long documentId) {
        CurrentUser operator = checkTenderer();
        requireOwnedPackage(packageId, operator.getOrgId());
        Document doc = requireOwnedDocument(documentId, operator.getOrgId());
        if (doc.getPackageId() != packageId) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (doc.isDeleted()) {
            return;
        }

        try {
            objectStoragePort.delete(doc.getObjectKey());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL, "删除对象存储中的文件失败");
        }
        documentRepository.markDeleted(documentId);
    }

    /**
     * 下载：任意已允许类型。
     */
    @Transactional(readOnly = true)
    public FileContent openForDownload(long packageId, long documentId) {
        return openContent(packageId, documentId, false);
    }

    /**
     * 预览：仅 PDF；其它类型请下载。
     */
    @Transactional(readOnly = true)
    public FileContent openForPreview(long packageId, long documentId) {
        FileContent content = openContent(packageId, documentId, true);
        if (!"pdf".equalsIgnoreCase(content.getFileExt())) {
            try {
                content.getInputStream().close();
            } catch (IOException ignored) {
                // ignore
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "仅支持预览 PDF，Word/Excel 请下载后查看");
        }
        return content;
    }

    private FileContent openContent(long packageId, long documentId, boolean inline) {
        CurrentUser operator = checkTenderer();
        requireOwnedPackage(packageId, operator.getOrgId());
        Document doc = requireOwnedDocument(documentId, operator.getOrgId());
        if (doc.getPackageId() != packageId) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }

        InputStream stream;
        try {
            stream = objectStoragePort.open(doc.getObjectKey());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.INTERNAL, "读取对象存储文件失败");
        }

        String contentType = doc.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = guessContentType(doc.getFileExt());
        }

        FileContent content = new FileContent();
        content.setInputStream(stream);
        content.setFileName(doc.getFileName());
        content.setFileExt(doc.getFileExt());
        content.setContentType(contentType);
        content.setSizeBytes(doc.getSizeBytes());
        content.setInline(inline);
        return content;
    }

    private String guessContentType(String ext) {
        if ("pdf".equalsIgnoreCase(ext)) {
            return "application/pdf";
        }
        if ("doc".equalsIgnoreCase(ext)) {
            return "application/msword";
        }
        if ("docx".equalsIgnoreCase(ext)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if ("xls".equalsIgnoreCase(ext)) {
            return "application/vnd.ms-excel";
        }
        if ("xlsx".equalsIgnoreCase(ext)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/octet-stream";
    }

    private DocumentSummary toSummary(Document doc) {
        DocumentSummary summary = new DocumentSummary();
        summary.setId(doc.getId());
        summary.setOrgId(doc.getOrgId());
        summary.setProjectId(doc.getProjectId());
        summary.setPackageId(doc.getPackageId());
        summary.setFileName(doc.getFileName());
        summary.setContentType(doc.getContentType());
        summary.setFileExt(doc.getFileExt());
        summary.setSizeBytes(doc.getSizeBytes());
        summary.setIndexStatus(doc.getIndexStatus());
        summary.setStatus(doc.getStatus());
        summary.setCreatedBy(doc.getCreatedBy());
        summary.setCreatedAt(doc.getCreatedAt());
        summary.setUpdatedAt(doc.getUpdatedAt());
        return summary;
    }

    private PackageLookupPort.PackageRef requireOwnedPackage(long packageId, long orgId) {
        PackageLookupPort.PackageRef pkg = packageLookupPort.findActiveById(packageId);
        if (pkg == null || pkg.getOrgId() != orgId) {
            throw new BizException(ErrorCode.NOT_FOUND, "标包不存在");
        }
        return pkg;
    }

    private Document requireOwnedDocument(long documentId, long orgId) {
        Optional<Document> optional = documentRepository.findById(documentId);
        if (optional.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        Document doc = optional.get();
        if (doc.getOrgId() != orgId || doc.isDeleted()) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return doc;
    }

    private CurrentUser checkTenderer() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isTenderer()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅招标方可操作文件");
        }
        return user;
    }

    private String sanitizeFileName(String name) {
        // 去掉路径，只留文件名
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0 && slash < base.length() - 1) {
            base = base.substring(slash + 1);
        }
        if (base.isEmpty() || base.length() > 256) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件名无效或过长");
        }
        for (int i = 0; i < base.length(); i++) {
            if (Character.isISOControl(base.charAt(i))) {
                throw new BizException(ErrorCode.BAD_REQUEST, "文件名包含非法字符");
            }
        }
        return base;
    }

    private String extractExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件缺少扩展名");
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String buildObjectKey(long orgId, long packageId, String fileName) {
        return "org/" + orgId + "/package/" + packageId + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "_" + fileName;
    }

    /** 文档摘要（不含下载地址，下期再做） */
    public static class DocumentSummary {
        private long id;
        private long orgId;
        private Long projectId;
        private long packageId;
        private String fileName;
        private String contentType;
        private String fileExt;
        private long sizeBytes;
        private String indexStatus;
        private String status;
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

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
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

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public String getFileExt() {
            return fileExt;
        }

        public void setFileExt(String fileExt) {
            this.fileExt = fileExt;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getIndexStatus() {
            return indexStatus;
        }

        public void setIndexStatus(String indexStatus) {
            this.indexStatus = indexStatus;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
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

    /** 下载 / 预览流式载荷。 */
    public static class FileContent {
        private InputStream inputStream;
        private String fileName;
        private String fileExt;
        private String contentType;
        private long sizeBytes;
        private boolean inline;

        public InputStream getInputStream() {
            return inputStream;
        }

        public void setInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
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

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public boolean isInline() {
            return inline;
        }

        public void setInline(boolean inline) {
            this.inline = inline;
        }
    }
}
