package com.bidding.common.port;

/**
 * 标包绑定项目后，回写包内文档（及索引产物）的 project_id。
 * 实现在 file 模块；供 project 在绑包时调用。
 */
public interface DocumentProjectBindPort {

    /**
     * 将指定标包下尚未绑定项目的文档回写 projectId；
     * 同步更新 chunk / vector 上为空的 project_id（避免先上传后绑包导致统计/召回不到）。
     *
     * @return 更新的文档行数
     */
    int bindPackageDocumentsToProject(long packageId, long projectId);
}
