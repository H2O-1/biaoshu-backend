package com.bidding.common.port;

/**
 * 按组织统计标包数量（供组织列表 / 详情；实现在 project 模块）。
 */
public interface PackageCountPort {

    /**
     * 未删除标包数。
     *
     * @param type TENDER / BID；空串表示不限类型
     */
    long countActiveByOrgIdAndType(long orgId, String type);

    /**
     * 未删除标包中，按包级解析状态统计数量。
     *
     * @param type        TENDER / BID
     * @param indexStatus SUCCESS / FAILED
     */
    long countActiveByOrgIdAndTypeAndIndexStatus(long orgId, String type, String indexStatus);
}
