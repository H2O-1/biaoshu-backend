package com.bidding.common.port;

/**
 * 按组织统计项目数量（供组织管理展示；实现放在 project 模块）。
 */
public interface ProjectCountPort {

    /** 未删除项目数 */
    long countByOrgId(long orgId);
}
