package com.bidding.file.infrastructure.persistence;

import com.bidding.common.port.DocumentProjectBindPort;
import com.bidding.file.domain.port.DocumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 标包绑项目后回写文档 / chunk / vector 的 project_id。
 */
@Component
public class DocumentProjectBindPortAdapter implements DocumentProjectBindPort {

    private final DocumentRepository documentRepository;
    private final JdbcTemplate jdbcTemplate;

    public DocumentProjectBindPortAdapter(DocumentRepository documentRepository,
                                          JdbcTemplate jdbcTemplate) {
        this.documentRepository = documentRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int bindPackageDocumentsToProject(long packageId, long projectId) {
        int docs = documentRepository.bindProjectIdByPackageId(packageId, projectId);
        // 先上传后绑包时，INDEX 产物也可能仍是 null
        jdbcTemplate.update(
                "UPDATE biz_document_chunk SET project_id = ? "
                        + "WHERE package_id = ? AND project_id IS NULL",
                projectId,
                packageId
        );
        jdbcTemplate.update(
                "UPDATE biz_document_vector v SET project_id = ? "
                        + "FROM biz_document d "
                        + "WHERE v.document_id = d.id AND d.package_id = ? AND v.project_id IS NULL",
                projectId,
                packageId
        );
        return docs;
    }
}
