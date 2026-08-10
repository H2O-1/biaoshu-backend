package com.bidding.common.port;

/**
 * 组织基础信息查询端口（供其它业务模块展示组织名称）。
 */
public interface OrgLookupPort {

    /**
     * 查询未删除组织名称；不存在或已删除返回 null。
     */
    String findNameById(long orgId);
}
