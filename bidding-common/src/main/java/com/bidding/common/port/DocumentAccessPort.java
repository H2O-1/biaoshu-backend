package com.bidding.common.port;

import java.util.List;

/**
 * 文档只读与索引状态更新（供 review / worker 使用；实现在 file 模块）。
 */
public interface DocumentAccessPort {

    /** 索引状态：待处理。 */
    String INDEX_PENDING = "PENDING";
    /** 索引状态：处理中。 */
    String INDEX_PROCESSING = "PROCESSING";
    /** 索引状态：成功。 */
    String INDEX_SUCCESS = "SUCCESS";
    /** 索引状态：失败。 */
    String INDEX_FAILED = "FAILED";

    /**
     * 按 ID 查未删除文档；不存在或已删除返回 null。
     */
    DocumentRef findActiveById(long documentId);

    /**
     * 列出标包下未删除文档（含各 indexStatus）。
     */
    List<DocumentRef> listActiveByPackageId(long packageId);

    void updateIndexStatus(long documentId, String indexStatus);

    /** 跨模块文档摘要。 */
    class DocumentRef {
        private long id;
        private long orgId;
        private Long projectId;
        private long packageId;
        private String fileName;
        private String fileExt;
        private String objectKey;
        private String indexStatus;

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

        public String getFileExt() {
            return fileExt;
        }

        public void setFileExt(String fileExt) {
            this.fileExt = fileExt;
        }

        public String getObjectKey() {
            return objectKey;
        }

        public void setObjectKey(String objectKey) {
            this.objectKey = objectKey;
        }

        public String getIndexStatus() {
            return indexStatus;
        }

        public void setIndexStatus(String indexStatus) {
            this.indexStatus = indexStatus;
        }
    }
}
