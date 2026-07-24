package com.bidding.file.infrastructure.persistence;

import com.bidding.common.port.DocumentCountPort;
import com.bidding.file.domain.port.DocumentRepository;
import org.springframework.stereotype.Component;

/**
 * 文档数量统计适配（供 project 模块删包拦截与 fileCount）。
 */
@Component
public class DocumentCountPortAdapter implements DocumentCountPort {

    private final DocumentRepository documentRepository;

    public DocumentCountPortAdapter(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public long countActiveByPackageId(long packageId) {
        return documentRepository.countActiveByPackageId(packageId);
    }
}
