package com.bidding.file.infrastructure.port;

import com.bidding.common.port.DocumentContentPort;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

/**
 * DocumentContentPort 适配：元数据走仓储，正文走对象存储。
 */
@Component
public class DocumentContentPortAdapter implements DocumentContentPort {

    private final DocumentRepository documentRepository;
    private final ObjectStoragePort objectStoragePort;

    public DocumentContentPortAdapter(DocumentRepository documentRepository,
                                      ObjectStoragePort objectStoragePort) {
        this.documentRepository = documentRepository;
        this.objectStoragePort = objectStoragePort;
    }

    @Override
    public DocumentContent openActive(long documentId) {
        Optional<Document> optional = documentRepository.findById(documentId);
        if (optional.isEmpty()) {
            return null;
        }
        Document doc = optional.get();
        if (doc.isDeleted()) {
            return null;
        }

        InputStream stream = objectStoragePort.open(doc.getObjectKey());

        DocumentContent content = new DocumentContent();
        content.setDocumentId(doc.getId());
        content.setOrgId(doc.getOrgId());
        content.setProjectId(doc.getProjectId());
        content.setPackageId(doc.getPackageId());
        content.setFileName(doc.getFileName());
        content.setContentType(doc.getContentType());
        content.setFileExt(doc.getFileExt());
        content.setSizeBytes(doc.getSizeBytes());
        content.setObjectKey(doc.getObjectKey());
        content.setInputStream(stream);
        return content;
    }
}
