package com.bidding.review.infrastructure.worker;

import com.bidding.common.port.JobPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 僵尸子任务回收（临时人工功能）的行为约束：
 * 只处理普通 INDEX / BUILD_CHECKLIST / REVIEW 子任务；
 * AI_REVIEW_PIPELINE 总任务永远不属于旧恢复逻辑处理范围（由租约接管）。
 */
class StaleAiWorkRecoveryTest {

    @Test
    void recoverOnlyTouchesChildJobTypesNeverPipeline() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StaleAiWorkRecovery recovery = new StaleAiWorkRecovery(jdbcTemplate, 30);

        recovery.recover();

        // biz_job 回收语句：恰好是三个子任务类型，不含 AI_REVIEW_PIPELINE
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(),
                anyString(),
                eq(JobPort.STATUS_RUNNING),
                eq(JobPort.TYPE_INDEX),
                eq(JobPort.TYPE_BUILD_CHECKLIST),
                eq(JobPort.TYPE_REVIEW),
                any(Timestamp.class));
        assertFalse(sql.getValue().contains(JobPort.TYPE_AI_REVIEW_PIPELINE),
                "一键审查总任务不得被启动回收误伤，应由租约过期自动接管");
        assertTrue(sql.getValue().contains("status = ?"), "只回收 RUNNING 子任务");
    }
}
