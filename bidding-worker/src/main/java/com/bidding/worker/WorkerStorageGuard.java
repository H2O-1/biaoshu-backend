package com.bidding.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Worker 启动校验：独立 Worker 进程必须使用共享对象存储（S3/MinIO）。
 * <p>
 * API 与 Worker 是两个独立进程，InMemoryObjectStorage 只存在于各自 JVM 内：
 * 若 Worker 用 memory 存储启动，API 上传到 MinIO 的文件 Worker 读不到，
 * INDEX 必然失败。因此 Worker 在 bidding.storage.type != s3 时直接拒绝启动
 * （构造 Bean 即抛异常，快速失败，不带着错误配置运行）。
 * <p>
 * 本类只存在于 bidding-worker 模块，API 进程类路径不含它；
 * API 单进程 local 调试不受影响的内存存储不受此限制。
 */
@Component
public class WorkerStorageGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkerStorageGuard.class);

    public WorkerStorageGuard(@Value("${bidding.storage.type:memory}") String storageType) {
        String normalized = storageType == null ? "memory" : storageType.trim();
        if (!"s3".equalsIgnoreCase(normalized)) {
            throw new IllegalStateException(
                    "bidding-worker 是独立进程，与 API 不共享内存对象存储：当前 bidding.storage.type="
                            + normalized + "，API 上传的文件 Worker 将无法读取。"
                            + "请为 Worker 配置 STORAGE_TYPE=s3，并指向与 API 相同的 MinIO/S3"
                            + "（MINIO_ENDPOINT / MINIO_BUCKET 等保持一致）。");
        }
        log.info("Worker 对象存储校验通过 bidding.storage.type={}", normalized);
    }
}
