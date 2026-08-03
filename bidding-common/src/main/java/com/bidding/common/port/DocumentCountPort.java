package com.bidding.common.port;

/**
 * 按标包统计未删除文档数（供标包摘要 fileCount、删包拦截、项目列表；实现在 file 模块）。
 */
public interface DocumentCountPort {

    long countActiveByPackageId(long packageId);

    /** 组织下未删除文档数。 */
    long countActiveByOrgId(long orgId);

    /**
     * 项目下指定包类型（TENDER / BID）的未删除文档数。
     * 通过标包 type 区分招标/投标文件。
     */
    long countActiveByProjectIdAndPackageType(long projectId, String packageType);

    /**
     * 项目下指定包类型中，未删除且 index_status 不是 SUCCESS 的文档数
     * （含 PENDING / PROCESSING / FAILED）。
     */
    long countActiveNotSuccessIndexByProjectIdAndPackageType(long projectId, String packageType);

    /**
     * 查项目下指定包类型 + file_type 的未删除文件名；无则返回 null。
     * 业务约定主招标文件 file_type 为「招标文件」，且每项目至多一份。
     */
    String findActiveFileNameByProjectIdAndPackageTypeAndFileType(long projectId,
                                                                 String packageType,
                                                                 String fileType);

    /**
     * 项目下指定包类型中，至少有 1 个未删除文件的标包数（如「X 家已上传文件」）。
     */
    long countPackagesWithActiveFilesByProjectIdAndPackageType(long projectId, String packageType);

    /**
     * 标包文件解析状态汇总：FAILED &gt; PROCESSING &gt; PENDING &gt; SUCCESS；无文件为 null。
     */
    String resolveIndexStatusByPackageId(long packageId);
}
