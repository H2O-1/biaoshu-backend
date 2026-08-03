package com.bidding.common.port;

import java.io.InputStream;

/**
 * 文档内容流（供 INDEX / OCR 等跨模块读取；实现在 file 模块）。
 * <p>
 * 调用方负责关闭 {@link DocumentContent#getInputStream()}。
 */
public interface DocumentContentPort {

    /**
     * 打开未删除文档的内容流；不存在或已删除返回 null。
     * 对象存储缺少文件时抛运行时异常（由上层转为任务失败）。
     */
    DocumentContent openActive(long documentId);

    /** 文档内容与元数据。 */
    class DocumentContent {
        private long documentId;
        private long orgId;
        private Long projectId;
        private long packageId;
        private String fileName;
        private String contentType;
        private String fileExt;
        private long sizeBytes;
        private String objectKey;
        private InputStream inputStream;

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
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

        public String getObjectKey() {
            return objectKey;
        }

        public void setObjectKey(String objectKey) {
            this.objectKey = objectKey;
        }

        public InputStream getInputStream() {
            return inputStream;
        }

        public void setInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
        }
    }
}
