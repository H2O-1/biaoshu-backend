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

    /** 组织下未删除文档数。 */
    long countActiveByOrgId(long orgId);

    /** 未删除文档中指定索引状态的数量。 */
    long countActiveByOrgIdAndIndexStatus(long orgId, String indexStatus);

    /** 全平台未删除文档中指定索引状态的数量。 */
    long countActiveByIndexStatus(String indexStatus);

    /**
     * 项目下指定标包类型的未删除文档数。
     *
     * @param packageType TENDER / BID
     */
    long countActiveByProjectIdAndPackageType(long projectId, String packageType);

    /**
     * 项目下指定标包类型中，未删除且 index_status &lt;&gt; SUCCESS 的文档数。
     */
    long countActiveNotSuccessIndexByProjectIdAndPackageType(long projectId, String packageType);

    /**
     * 项目下指定标包类型 + file_type 的未删除文件名；无则 empty。
     */
    Optional<String> findActiveFileNameByProjectIdAndPackageTypeAndFileType(
            long projectId, String packageType, String fileType);

    /**
     * 项目下指定标包类型中，至少有 1 个未删除文件的标包数。
     */
    long countPackagesWithActiveFilesByProjectIdAndPackageType(long projectId, String packageType);

    /**
     * 标包下未删除文档的 index_status 汇总（包级展示用）。
     * 优先级：FAILED &gt; PROCESSING &gt; PENDING &gt; SUCCESS；无文件返回 null。
     */
    String resolveIndexStatusByPackageId(long packageId);

    long insert(long orgId,
                Long projectId,
                long packageId,
                String fileName,
                String contentType,
                String fileExt,
                String fileType,
                long sizeBytes,
                String objectKey,
                String indexStatus,
                String status,
                long createdBy);

    void markDeleted(long documentId);

    void updateIndexStatus(long documentId, String indexStatus);

    /**
     * 标包绑项目后：把包内 project_id 为空的文档回写为该项目。
     *
     * @return 更新行数
     */
    int bindProjectIdByPackageId(long packageId, long projectId);
}
