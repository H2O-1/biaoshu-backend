package com.bidding.file.domain.model;

import java.time.Instant;

/**
 * 文档元数据（对应 biz_document；文件本体在对象存储）。
 */
public class Document {

    private final long id;
    private final long orgId;
    private final Long projectId;
    private final long packageId;
    private final String fileName;
    private final String contentType;
    private final String fileExt;
    private final long sizeBytes;
    private final String objectKey;
    private final String indexStatus;
    private final String status;
    private final long createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Document(long id,
                    long orgId,
                    Long projectId,
                    long packageId,
                    String fileName,
                    String contentType,
                    String fileExt,
                    long sizeBytes,
                    String objectKey,
                    String indexStatus,
                    String status,
                    long createdBy,
                    Instant createdAt,
                    Instant updatedAt) {
        this.id = id;
        this.orgId = orgId;
        this.projectId = projectId;
        this.packageId = packageId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.fileExt = fileExt;
        this.sizeBytes = sizeBytes;
        this.objectKey = objectKey;
        this.indexStatus = indexStatus;
        this.status = status;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public long getId() {
        return id;
    }

    public long getOrgId() {
        return orgId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public long getPackageId() {
        return packageId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getFileExt() {
        return fileExt;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getIndexStatus() {
        return indexStatus;
    }

    public String getStatus() {
        return status;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return "DELETED".equalsIgnoreCase(status);
    }
}
