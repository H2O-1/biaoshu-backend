package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.JobPort;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSseServiceTest {

    @Mock
    private JobPort jobPort;

    @Mock
    private JobAppService jobAppService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JobSseService jobSseService;

    @BeforeEach
    void setUp() {
        Set<String> roles = new HashSet<>();
        roles.add("TENDERER");
        UserContext.set(new CurrentUser(10L, 2L, "tenderer01", roles, 0, false));
        jobSseService = new JobSseService(
                jobPort,
                jobAppService,
                objectMapper,
                true,
                200L,
                60_000L,
                5_000L
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void eventNameForFirstRunningIsSnapshot() {
        assertEquals("snapshot", JobSseService.eventNameFor(JobPort.STATUS_RUNNING, true));
        assertEquals("progress", JobSseService.eventNameFor(JobPort.STATUS_RUNNING, false));
        assertEquals("done", JobSseService.eventNameFor(JobPort.STATUS_SUCCESS, true));
        assertEquals("done", JobSseService.eventNameFor(JobPort.STATUS_SUCCESS, false));
        assertEquals("failed", JobSseService.eventNameFor(JobPort.STATUS_FAILED, false));
        assertEquals("failed", JobSseService.eventNameFor(JobPort.STATUS_CANCELLED, true));
    }

    @Test
    void isTerminalRecognizesEndStates() {
        assertTrue(JobSseService.isTerminal(JobPort.STATUS_SUCCESS));
        assertTrue(JobSseService.isTerminal(JobPort.STATUS_FAILED));
        assertTrue(JobSseService.isTerminal(JobPort.STATUS_CANCELLED));
        assertFalse(JobSseService.isTerminal(JobPort.STATUS_PENDING));
        assertFalse(JobSseService.isTerminal(JobPort.STATUS_RUNNING));
    }

    @Test
    void fingerprintChangesWithProgress() {
        JobAppService.JobSummary a = baseSummary(JobPort.STATUS_RUNNING, 10, "向量化中");
        JobAppService.JobSummary b = baseSummary(JobPort.STATUS_RUNNING, 40, "建清单中");
        assertFalse(JobSseService.fingerprint(a).equals(JobSseService.fingerprint(b)));
        assertEquals(JobSseService.fingerprint(a), JobSseService.fingerprint(baseSummary(
                JobPort.STATUS_RUNNING, 10, "向量化中")));
    }

    @Test
    void openStreamRejectsWhenDisabled() {
        JobSseService disabled = new JobSseService(
                jobPort, jobAppService, objectMapper, false, 1000L, 15000L, 7200000L);
        BizException ex = assertThrows(BizException.class, () -> disabled.openStream(55L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void openStreamHidesOtherOrgViaGetJob() {
        when(jobAppService.getJob(55L)).thenThrow(new BizException(ErrorCode.NOT_FOUND, "任务不存在"));
        BizException ex = assertThrows(BizException.class, () -> jobSseService.openStream(55L));
        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
    }

    @Test
    void openStreamAcceptsAlreadySuccessWithoutThrowing() {
        UserContext.set(new CurrentUser(10L, 2L, "bidder01", Set.of("BIDDER"), 0, false));
        // 无 Servlet 异步上下文时 SseEmitter.onCompletion 不一定回调，此处只校验鉴权读库与建连
        JobAppService.JobSummary done = baseSummary(JobPort.STATUS_SUCCESS, 100, "完成");
        when(jobAppService.getJob(55L)).thenReturn(done);

        SseEmitter emitter = jobSseService.openStream(55L);
        assertTrue(emitter != null);
        verify(jobAppService).getJob(55L);
        emitter.complete();
    }

    @Test
    void openStreamSchedulesPollWhileRunning() throws Exception {
        JobAppService.JobSummary running = baseSummary(JobPort.STATUS_RUNNING, 10, "处理中");
        when(jobAppService.getJob(55L)).thenReturn(running);

        JobPort.JobRef ref = new JobPort.JobRef();
        ref.setId(55L);
        ref.setOrgId(2L);
        ref.setType(JobPort.TYPE_INDEX);
        ref.setStatus(JobPort.STATUS_RUNNING);
        ref.setProgress(10);
        ref.setMessage("处理中");

        JobAppService.JobSummary mid = baseSummary(JobPort.STATUS_RUNNING, 50, "过半");
        JobAppService.JobSummary done = baseSummary(JobPort.STATUS_SUCCESS, 100, "完成");

        when(jobPort.findById(55L)).thenReturn(java.util.Optional.of(ref));
        when(jobAppService.toSummary(ref)).thenReturn(mid, done);

        SseEmitter emitter = jobSseService.openStream(55L);
        assertTrue(emitter != null);
        // 等待至少一轮 scheduleWithFixedDelay
        Thread.sleep(600L);
        verify(jobPort, atLeastOnce()).findById(55L);
        emitter.complete();
    }

    private static JobAppService.JobSummary baseSummary(String status, int progress, String message) {
        JobAppService.JobSummary s = new JobAppService.JobSummary();
        s.setId(55L);
        s.setOrgId(2L);
        s.setProjectId(100L);
        s.setType(JobPort.TYPE_INDEX);
        s.setStatus(status);
        s.setProgress(progress);
        s.setMessage(message);
        s.setCreatedBy(10L);
        return s;
    }
}
