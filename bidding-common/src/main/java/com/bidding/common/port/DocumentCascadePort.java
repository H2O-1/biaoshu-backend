package com.bidding.common.port;

/**
 * 标包级联清理文档（实现在 file 模块）。
 * 供删包时调用：逻辑删包内文件、尽量删对象存储、清切块/向量。
 */
public interface DocumentCascadePort {

    /**
     * 逻辑删除指定标包下全部未删除文档，并清理索引产物。
     *
     * @return 删除的文档数
     */
    int softDeleteAllActiveByPackageId(long packageId);
}
