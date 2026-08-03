package com.bidding.file.infrastructure.persistence;

import com.bidding.common.port.DocumentIndexCountPort;
import com.bidding.file.domain.port.DocumentRepository;
import org.springframework.stereotype.Component;

/**
 * 组织详情用的文档索引状态计数实现。
 */
@Component
public class DocumentIndexCountPortAdapter implements DocumentIndexCountPort {

    private final DocumentRepository documentRepository;

    public DocumentIndexCountPortAdapter(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public long countActiveByOrgIdAndIndexStatus(long orgId, String indexStatus) {
        String status = indexStatus == null ? "" : indexStatus.trim();
        return documentRepository.countActiveByOrgIdAndIndexStatus(orgId, status);
    }

    @Override
    public long countActiveByIndexStatus(String indexStatus) {
        String status = indexStatus == null ? "" : indexStatus.trim();
        return documentRepository.countActiveByIndexStatus(status);
    }
}
