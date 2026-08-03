package com.bidding.file.infrastructure.port;

import com.bidding.common.port.DocumentAccessPort;
import com.bidding.file.domain.model.Document;
import com.bidding.file.domain.port.DocumentRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DocumentAccessPort 适配：委托 DocumentRepository。
 */
@Component
public class DocumentAccessPortAdapter implements DocumentAccessPort {

    private final DocumentRepository documentRepository;

    public DocumentAccessPortAdapter(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public DocumentRef findActiveById(long documentId) {
        Optional<Document> optional = documentRepository.findById(documentId);
        if (optional.isEmpty()) {
            return null;
        }
        Document doc = optional.get();
        if (doc.isDeleted()) {
            return null;
        }
        DocumentRef ref = new DocumentRef();
        ref.setId(doc.getId());
        ref.setOrgId(doc.getOrgId());
        ref.setProjectId(doc.getProjectId());
        ref.setPackageId(doc.getPackageId());
        ref.setFileName(doc.getFileName());
        ref.setFileExt(doc.getFileExt());
        ref.setObjectKey(doc.getObjectKey());
        ref.setIndexStatus(doc.getIndexStatus());
        return ref;
    }

    @Override
    public List<DocumentRef> listActiveByPackageId(long packageId) {
        List<Document> docs = documentRepository.listByPackageId(packageId);
        List<DocumentRef> result = new ArrayList<>();
        if (docs == null) {
            return result;
        }
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            if (doc == null || doc.isDeleted()) {
                continue;
            }
            result.add(toRef(doc));
        }
        return result;
    }

    @Override
    public void updateIndexStatus(long documentId, String indexStatus) {
        documentRepository.updateIndexStatus(documentId, indexStatus);
    }

    private DocumentRef toRef(Document doc) {
        DocumentRef ref = new DocumentRef();
        ref.setId(doc.getId());
        ref.setOrgId(doc.getOrgId());
        ref.setProjectId(doc.getProjectId());
        ref.setPackageId(doc.getPackageId());
        ref.setFileName(doc.getFileName());
        ref.setFileExt(doc.getFileExt());
        ref.setObjectKey(doc.getObjectKey());
        ref.setIndexStatus(doc.getIndexStatus());
        return ref;
    }
}
