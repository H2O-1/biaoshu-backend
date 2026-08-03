package com.bidding.common.port;

/**
 * 统计文档索引状态数量（供组织详情 / 平台看板；实现在 file 模块）。
 */
public interface DocumentIndexCountPort {

    /**
     * 未删除文档中，指定 index_status 的数量。
     *
     * @param indexStatus SUCCESS / FAILED / PENDING / PROCESSING 等
     */
    long countActiveByOrgIdAndIndexStatus(long orgId, String indexStatus);

    /**
     * 全平台未删除文档中，指定 index_status 的数量。
     */
    long countActiveByIndexStatus(String indexStatus);
}
