package com.bidding.common.port;

/**
 * 为文档排队 INDEX 任务（实现在 review；供 file 复制后自动触发）。
 */
public interface DocumentIndexJobPort {

    /**
     * 写入 PENDING 的 INDEX 任务，并把文档 index_status 置为 PROCESSING。
     *
     * @return jobId
     */
    long enqueueIndex(long orgId, Long projectId, long documentId, long createdBy);
}
