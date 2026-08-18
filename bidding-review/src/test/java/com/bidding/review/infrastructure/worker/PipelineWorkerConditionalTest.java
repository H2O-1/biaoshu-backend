package com.bidding.review.infrastructure.worker;

import com.bidding.review.application.AiReviewPipelineJobExecutor;
import com.bidding.review.application.BuildChecklistJobExecutor;
import com.bidding.review.application.ExpertReviewAppService;
import com.bidding.review.application.IndexJobExecutor;
import com.bidding.review.application.ReviewJobExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Poller / 僵尸回收的配置条件测试：
 * 默认（API 进程画像）五个 Poller 与 StaleAiWorkRecovery 全部不装配；
 * 显式开启（Worker 进程画像）后全部装配。
 * 隔离完全由 bidding.*.worker-enabled / bidding.recovery.enabled 配置决定，与包扫描无关。
 */
class PipelineWorkerConditionalTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(
                    MockExecutors.class,
                    IndexJobPoller.class,
                    BuildChecklistJobPoller.class,
                    ReviewJobPoller.class,
                    AiReviewPipelineJobPoller.class,
                    ExpertReviewDeadlinePoller.class,
                    StaleAiWorkRecovery.class);

    @Test
    void apiProfileDoesNotStartAnyWorkerPoller() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(IndexJobPoller.class);
            assertThat(context).doesNotHaveBean(BuildChecklistJobPoller.class);
            assertThat(context).doesNotHaveBean(ReviewJobPoller.class);
            assertThat(context).doesNotHaveBean(AiReviewPipelineJobPoller.class);
            assertThat(context).doesNotHaveBean(ExpertReviewDeadlinePoller.class);
            assertThat(context).doesNotHaveBean(StaleAiWorkRecovery.class);
        });
    }

    @Test
    void explicitFalseKeepsPollersOff() {
        runner.withPropertyValues(
                "bidding.index.worker-enabled=false",
                "bidding.checklist.worker-enabled=false",
                "bidding.review.worker-enabled=false",
                "bidding.ai-review-pipeline.worker-enabled=false",
                "bidding.expert-review.deadline-worker-enabled=false",
                "bidding.recovery.enabled=false"
        ).run(context -> {
            assertThat(context).doesNotHaveBean(IndexJobPoller.class);
            assertThat(context).doesNotHaveBean(BuildChecklistJobPoller.class);
            assertThat(context).doesNotHaveBean(ReviewJobPoller.class);
            assertThat(context).doesNotHaveBean(AiReviewPipelineJobPoller.class);
            assertThat(context).doesNotHaveBean(ExpertReviewDeadlinePoller.class);
            assertThat(context).doesNotHaveBean(StaleAiWorkRecovery.class);
        });
    }

    @Test
    void workerProfileStartsAllPollersButNotRecovery() {
        // Worker 进程画像：五个 Poller 开启；僵尸回收默认仍关闭（临时人工功能）
        runner.withPropertyValues(
                "bidding.index.worker-enabled=true",
                "bidding.checklist.worker-enabled=true",
                "bidding.review.worker-enabled=true",
                "bidding.ai-review-pipeline.worker-enabled=true",
                "bidding.expert-review.deadline-worker-enabled=true"
        ).run(context -> {
            assertThat(context).hasSingleBean(IndexJobPoller.class);
            assertThat(context).hasSingleBean(BuildChecklistJobPoller.class);
            assertThat(context).hasSingleBean(ReviewJobPoller.class);
            assertThat(context).hasSingleBean(AiReviewPipelineJobPoller.class);
            assertThat(context).hasSingleBean(ExpertReviewDeadlinePoller.class);
            assertThat(context).doesNotHaveBean(StaleAiWorkRecovery.class);
        });
    }

    @Test
    void recoveryCreatedOnlyWhenExplicitlyEnabled() {
        runner.withPropertyValues("bidding.recovery.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(StaleAiWorkRecovery.class));
    }

    /** 只提供 Poller 构造所需的执行器与 JdbcTemplate，不触发真实调度与数据库。 */
    @Configuration(proxyBeanMethods = false)
    static class MockExecutors {

        @Bean
        IndexJobExecutor indexJobExecutor() {
            return mock(IndexJobExecutor.class);
        }

        @Bean
        BuildChecklistJobExecutor buildChecklistJobExecutor() {
            return mock(BuildChecklistJobExecutor.class);
        }

        @Bean
        ReviewJobExecutor reviewJobExecutor() {
            return mock(ReviewJobExecutor.class);
        }

        @Bean
        AiReviewPipelineJobExecutor aiReviewPipelineJobExecutor() {
            return mock(AiReviewPipelineJobExecutor.class);
        }

        @Bean
        ExpertReviewAppService expertReviewAppService() {
            return mock(ExpertReviewAppService.class);
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }
    }
}
