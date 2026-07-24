package com.bidding.file.domain.port;

import com.bidding.file.domain.model.Document;

import java.util.List;
import java.util.Optional;

/**
 * 文档元数据表操作。
 */
public interface DocumentRepository {

    Optional<Document> findById(long documentId);

    List<Document> listByPackageId(long packageId);

    long countActiveByPackageId(long packageId);

    long insert(long orgId,
                Long projectId,
                long packageId,
                String fileName,
                String contentType,
                String fileExt,
                long sizeBytes,
                String objectKey,
                String indexStatus,
                String status,
                long createdBy);

    void markDeleted(long documentId);
}
