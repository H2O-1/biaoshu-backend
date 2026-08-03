package com.bidding.file.infrastructure.persistence;

import com.bidding.common.port.DocumentCountPort;
import com.bidding.file.domain.port.DocumentRepository;
import org.springframework.stereotype.Component;

/**
 * 文档数量统计适配（供 project 模块删包拦截、fileCount、workflowStatus、列表摘要）。
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

    @Override
    public long countActiveByOrgId(long orgId) {
        return documentRepository.countActiveByOrgId(orgId);
    }

    @Override
    public long countActiveByProjectIdAndPackageType(long projectId, String packageType) {
        return documentRepository.countActiveByProjectIdAndPackageType(projectId, packageType);
    }

    @Override
    public long countActiveNotSuccessIndexByProjectIdAndPackageType(long projectId, String packageType) {
        return documentRepository.countActiveNotSuccessIndexByProjectIdAndPackageType(
                projectId, packageType);
    }

    @Override
    public String findActiveFileNameByProjectIdAndPackageTypeAndFileType(long projectId,
                                                                        String packageType,
                                                                        String fileType) {
        return documentRepository.findActiveFileNameByProjectIdAndPackageTypeAndFileType(
                projectId, packageType, fileType).orElse(null);
    }

    @Override
    public long countPackagesWithActiveFilesByProjectIdAndPackageType(long projectId,
                                                                      String packageType) {
        return documentRepository.countPackagesWithActiveFilesByProjectIdAndPackageType(
                projectId, packageType);
    }

    @Override
    public String resolveIndexStatusByPackageId(long packageId) {
        return documentRepository.resolveIndexStatusByPackageId(packageId);
    }
}
