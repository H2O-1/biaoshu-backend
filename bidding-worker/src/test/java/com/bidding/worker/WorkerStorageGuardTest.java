package com.bidding.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker 存储启动校验：独立 Worker 进程不得以内存对象存储启动，
 * 必须显式配置共享 S3/MinIO；校验组件只存在于 bidding-worker 模块，
 * 不影响 API 进程（API 类路径不包含本模块）。
 */
class WorkerStorageGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerStorageGuard.class);

    @Test
    void workerFailsFastWhenStorageTypeIsMemory() {
        runner.withPropertyValues("bidding.storage.type=memory")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("STORAGE_TYPE=s3")
                            .hasStackTraceContaining("bidding.storage.type=memory");
                });
    }

    @Test
    void workerFailsFastWhenStorageTypeMissing() {
        // 缺省按 memory 处理，同样拒绝启动
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("STORAGE_TYPE=s3");
        });
    }

    @Test
    void workerStartsWhenStorageTypeIsS3() {
        runner.withPropertyValues("bidding.storage.type=s3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WorkerStorageGuard.class);
                });
    }
}
