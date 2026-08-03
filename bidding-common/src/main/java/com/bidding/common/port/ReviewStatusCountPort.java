package com.bidding.common.port;

/**
 * 统计审查（REVIEW）状态数量（供组织详情 / 平台看板；实现在 review 模块）。
 */
public interface ReviewStatusCountPort {

    /**
     * 指定 status 的审查数（含历史版本行）。
     *
     * @param status SUCCESS / FAILED / BUILDING 等
     */
    long countByOrgIdAndStatus(long orgId, String status);

    /**
     * 全平台指定 status 的审查数（含历史版本行）。
     */
    long countByStatus(String status);
}
