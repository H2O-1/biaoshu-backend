package com.bidding.file.infrastructure.port;

import com.bidding.common.port.TenderAnalysisDocumentCommandPort;
import com.bidding.file.application.DocumentAppService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 内部分析文件写操作适配。
 */
@Component
public class TenderAnalysisDocumentCommandPortAdapter implements TenderAnalysisDocumentCommandPort {

    private final DocumentAppService documentAppService;

    public TenderAnalysisDocumentCommandPortAdapter(DocumentAppService documentAppService) {
        this.documentAppService = documentAppService;
    }

    @Override
    public UploadResult uploadAndIndex(long orgId,
                                       long packageId,
                                       String fileType,
                                       MultipartFile file,
                                       long createdBy) {
        DocumentAppService.InternalUploadResult uploaded =
                documentAppService.uploadTenderAnalysisFile(orgId, packageId, fileType, file, createdBy);
        UploadResult result = new UploadResult();
        result.setDocumentId(uploaded.getDocumentId());
        result.setJobId(uploaded.getJobId());
        result.setFileName(uploaded.getFileName());
        result.setFileType(uploaded.getFileType());
        return result;
    }
}
