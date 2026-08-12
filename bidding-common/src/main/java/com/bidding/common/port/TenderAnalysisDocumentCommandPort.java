package com.bidding.common.port;

import org.springframework.web.multipart.MultipartFile;

/**
 * 招标解析文件写操作（实现在 file 模块）。
 */
public interface TenderAnalysisDocumentCommandPort {

    /**
     * 上传招标解析文件到指定内部招标包，并自动排队 INDEX。
     */
    UploadResult uploadAndIndex(long orgId,
                                long packageId,
                                String fileType,
                                MultipartFile file,
                                long createdBy);

    /** 上传并入队结果。 */
    class UploadResult {
        private long documentId;
        private long jobId;
        private String fileName;
        private String fileType;

        public long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(long documentId) {
            this.documentId = documentId;
        }

        public long getJobId() {
            return jobId;
        }

        public void setJobId(long jobId) {
            this.jobId = jobId;
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
}
