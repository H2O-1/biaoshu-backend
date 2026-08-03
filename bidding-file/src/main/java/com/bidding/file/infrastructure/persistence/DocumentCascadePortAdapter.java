package com.bidding.file.infrastructure.persistence;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentCascadePort;
import com.bidding.common.port.DocumentIndexStorePort;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import com.bidding.file.domain.port.ObjectStoragePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标包级联逻辑删除文档（含对象存储与切块/向量）。
 */
@Component
public class DocumentCascadePortAdapter implements DocumentCascadePort {

    private final DocumentRepository documentRepository;
    private final ObjectStoragePort objectStoragePort;
    private final DocumentIndexStorePort documentIndexStorePort;

    public DocumentCascadePortAdapter(DocumentRepository documentRepository,
                                      ObjectStoragePort objectStoragePort,
                                      DocumentIndexStorePort documentIndexStorePort) {
        this.documentRepository = documentRepository;
        this.objectStoragePort = objectStoragePort;
        this.documentIndexStorePort = documentIndexStorePort;
    }

    @Override
    @Transactional
    public int softDeleteAllActiveByPackageId(long packageId) {
        List<Document> docs = documentRepository.listByPackageId(packageId);
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            if (doc == null || doc.isDeleted()) {
                continue;
            }
            try {
                objectStoragePort.delete(doc.getObjectKey());
            } catch (RuntimeException e) {
                throw new BizException(ErrorCode.INTERNAL, "删除对象存储中的文件失败");
            }
            documentRepository.markDeleted(doc.getId());
            documentIndexStorePort.deleteByDocumentId(doc.getId());
            count++;
        }
        return count;
    }
}
