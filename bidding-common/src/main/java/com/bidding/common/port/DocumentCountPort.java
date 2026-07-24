package com.bidding.common.port;

/**
 * 按标包统计未删除文档数（供标包摘要 fileCount、删包拦截；实现在 file 模块）。
 */
public interface DocumentCountPort {

    long countActiveByPackageId(long packageId);
}
