package com.bidding.review.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.DocumentAccessPort;
import com.bidding.common.port.JobPort;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.RequirementChecklistPort;
import com.bidding.common.port.ReviewPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一键审查状态机执行器单元测试：逐阶段推进、重入幂等、续跑、
 * 子任务失败传导、阶段超时、租约易主保护与瞬时错误重试分类。
 */
@ExtendWith(MockitoExtension.class)
class AiReviewPipelineJobExecutorTest {

    private static final String WORKER = "w1";
    private static final long JOB_ID = 501L;
    private static final long ORG_ID = 2L;
    private static final long PROJECT_ID = 100L;
    private static final long CREATED_BY = 10L;
    private static final long WAIT_INTERVAL_MS = 3000L;
    private static final long PHASE_TIMEOUT_MS = 7_200_000L;
    private static final long RETRY_BACKOFF_MS = 10_000L;

    @Mock
    private JobPort jobPort;
    @Mock
    private JobAppService jobAppService;
    @Mock
    private PackageLookupPort packageLookupPort;
    @Mock
    private DocumentAccessPort documentAccessPort;
    @Mock
    private RequirementChecklistPort checklistPort;
    @Mock
    private ReviewPort reviewPort;
    @Mock
    private ChecklistAppService checklistAppService;
    @Mock
    private ReviewAppService reviewAppService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private AiReviewPipelineJobExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AiReviewPipelineJobExecutor(
                jobPort, jobAppService, packageLookupPort, documentAccessPort,
                checklistPort, reviewPort, checklistAppService, reviewAppService,
                transactionManager,
                60, WAIT_INTERVAL_MS, PHASE_TIMEOUT_MS, PHASE_TIMEOUT_MS, 3, RETRY_BACKOFF_MS);
    }

    // ---------- INDEX_SUBMIT ----------

    @Test
    void indexSubmitEnqueuesOnlyPendingAndFailedDocs() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_SUBMIT);
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_SUCCESS),
                        doc(2L, 50L, DocumentAccessPort.INDEX_PENDING)),
                List.of(doc(3L, 60L, DocumentAccessPort.INDEX_FAILED),
                        doc(4L, 60L, DocumentAccessPort.INDEX_PROCESSING)));
        when(jobAppService.createIndexJob(eq(ORG_ID), eq(PROJECT_ID), anyLong(), eq(CREATED_BY)))
                .thenReturn(900L);
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobAppService).createIndexJob(ORG_ID, PROJECT_ID, 2L, CREATED_BY);
        verify(jobAppService).createIndexJob(ORG_ID, PROJECT_ID, 3L, CREATED_BY);
        verify(jobAppService, times(2)).createIndexJob(anyLong(), anyLong(), anyLong(), anyLong());
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_INDEX_SUBMIT), eq(JobPort.PHASE_INDEX_WAIT),
                eq(10), anyString(), any(Instant.class), isNull(), isNull(), isNull());
        verify(jobPort, never()).failPipeline(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void indexSubmitReentryDoesNotDuplicateExistingWork() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_SUBMIT);
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_SUCCESS)),
                List.of(doc(3L, 60L, DocumentAccessPort.INDEX_PROCESSING)));
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobAppService, never()).createIndexJob(anyLong(), anyLong(), anyLong(), anyLong());
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_INDEX_SUBMIT), eq(JobPort.PHASE_INDEX_WAIT),
                anyInt(), anyString(), any(Instant.class), isNull(), isNull(), isNull());
    }

    @Test
    void indexSubmitFailsWhenProjectHasNoDocs() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_SUBMIT);
        givenClaim(job);
        givenDocs(List.of(), List.of());

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_INDEX_SUBMIT),
                contains("没有可解析的文件"));
        verify(jobPort, never()).advancePipeline(anyLong(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(), any(), any(), any());
    }

    // ---------- INDEX_WAIT ----------

    @Test
    void indexWaitAdvancesImmediatelyWhenAllDocsSuccess() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_SUCCESS)),
                List.of(doc(3L, 60L, DocumentAccessPort.INDEX_SUCCESS)));
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        ArgumentCaptor<Instant> nextRun = ArgumentCaptor.forClass(Instant.class);
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_INDEX_WAIT), eq(JobPort.PHASE_CHECKLIST_SUBMIT),
                eq(35), anyString(), nextRun.capture(), isNull(), isNull(), isNull());
        assertFalse(nextRun.getValue().isAfter(Instant.now()), "全部成功应立即可运行");
    }

    @Test
    void indexWaitFailsWhenAnyDocFailed() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_FAILED)),
                List.of(doc(3L, 60L, DocumentAccessPort.INDEX_SUCCESS)));

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_INDEX_WAIT),
                eq("文件解析失败：doc-1.pdf"));
    }

    @Test
    void indexWaitDefersWithoutRetryWhileDocsInFlight() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_PROCESSING)),
                List.of(doc(3L, 60L, DocumentAccessPort.INDEX_SUCCESS)));
        givenAdvanceOk();
        Instant before = Instant.now();

        assertTrue(executor.claimAndRunOnce(WORKER));

        ArgumentCaptor<Instant> nextRun = ArgumentCaptor.forClass(Instant.class);
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_INDEX_WAIT), eq(JobPort.PHASE_INDEX_WAIT),
                anyInt(), anyString(), nextRun.capture(), isNull(), isNull(), isNull());
        assertTrue(nextRun.getValue().isAfter(before.plusMillis(WAIT_INTERVAL_MS - 500)),
                "正常等待只推迟 next_run_at");
        // 正常等待不算瞬时错误重试
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
        verify(jobPort, never()).failPipeline(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void indexWaitFailsWhenPhaseTimedOut() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        job.setPhaseStartedAt(Instant.now().minusMillis(PHASE_TIMEOUT_MS + 1000));
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_PROCESSING)),
                List.of());

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_INDEX_WAIT),
                contains("超时"));
    }

    // ---------- CHECKLIST_SUBMIT ----------

    @Test
    void checklistSubmitReusesLatestSuccessChecklist() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_SUBMIT);
        givenClaim(job);
        when(checklistPort.findLatestSuccess(ORG_ID, PROJECT_ID))
                .thenReturn(checklist(77L, RequirementChecklistPort.STATUS_SUCCESS, 1));
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(checklistAppService, never()).createInternal(
                anyLong(), anyLong(), anyLong(), any(), any());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_CHECKLIST_SUBMIT), eq(JobPort.PHASE_REVIEW_SUBMIT),
                eq(55), eq("复用清单"), any(Instant.class), eq(77L), isNull(), payload.capture());
        assertTrue(payload.getValue().contains("\"checklistId\":77"));
        assertTrue(payload.getValue().contains("\"reusedChecklist\":true"));
    }

    @Test
    void checklistSubmitCreatesChecklistAndChildJob() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_SUBMIT);
        givenClaim(job);
        when(checklistPort.findLatestSuccess(ORG_ID, PROJECT_ID)).thenReturn(null);
        ChecklistAppService.CreateResult created = new ChecklistAppService.CreateResult();
        created.setChecklistId(88L);
        created.setJobId(900L);
        when(checklistAppService.createInternal(ORG_ID, PROJECT_ID, CREATED_BY, null, null))
                .thenReturn(created);
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_CHECKLIST_SUBMIT), eq(JobPort.PHASE_CHECKLIST_WAIT),
                eq(45), eq("建清单中"), any(Instant.class), eq(88L), isNull(), payload.capture());
        assertTrue(payload.getValue().contains("\"reusedChecklist\":false"));
    }

    // ---------- CHECKLIST_WAIT ----------

    @Test
    void checklistWaitResumesAndAdvancesWhenChecklistSuccess() {
        // 模拟「创建清单后重启」：任务以 CHECKLIST_WAIT + checklist_id 被重新认领，直接续跑不重建
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_WAIT);
        job.setChecklistId(88L);
        givenClaim(job);
        when(checklistPort.findDetail(ORG_ID, PROJECT_ID, 88L))
                .thenReturn(checklist(88L, RequirementChecklistPort.STATUS_SUCCESS, 2));
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(checklistAppService, never()).createInternal(
                anyLong(), anyLong(), anyLong(), any(), any());
        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_CHECKLIST_WAIT), eq(JobPort.PHASE_REVIEW_SUBMIT),
                eq(55), eq("清单完成"), any(Instant.class), isNull(), isNull(), isNull());
    }

    @Test
    void checklistWaitFailsWhenChecklistSuccessButEmpty() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_WAIT);
        job.setChecklistId(88L);
        givenClaim(job);
        when(checklistPort.findDetail(ORG_ID, PROJECT_ID, 88L))
                .thenReturn(checklist(88L, RequirementChecklistPort.STATUS_SUCCESS, 0));

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_CHECKLIST_WAIT),
                contains("无条目"));
    }

    @Test
    void checklistWaitFailsWhenChecklistFailed() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_WAIT);
        job.setChecklistId(88L);
        givenClaim(job);
        RequirementChecklistPort.ChecklistDetail detail =
                checklist(88L, RequirementChecklistPort.STATUS_FAILED, 0);
        detail.setErrorMessage("LLM 调用失败");
        when(checklistPort.findDetail(ORG_ID, PROJECT_ID, 88L)).thenReturn(detail);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_CHECKLIST_WAIT),
                contains("建清单失败"));
    }

    @Test
    void checklistWaitDefersWhileBuilding() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_WAIT);
        job.setChecklistId(88L);
        givenClaim(job);
        when(checklistPort.findDetail(ORG_ID, PROJECT_ID, 88L))
                .thenReturn(checklist(88L, RequirementChecklistPort.STATUS_BUILDING, 0));
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_CHECKLIST_WAIT), eq(JobPort.PHASE_CHECKLIST_WAIT),
                anyInt(), eq("建清单中"), any(Instant.class), isNull(), isNull(), isNull());
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void checklistWaitFailsWhenTimedOut() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_WAIT);
        job.setChecklistId(88L);
        job.setPhaseStartedAt(Instant.now().minusMillis(PHASE_TIMEOUT_MS + 1000));
        givenClaim(job);
        when(checklistPort.findDetail(ORG_ID, PROJECT_ID, 88L))
                .thenReturn(checklist(88L, RequirementChecklistPort.STATUS_BUILDING, 0));

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_CHECKLIST_WAIT),
                contains("超时"));
    }

    @Test
    void checklistWaitFailsWhenChecklistIdMissing() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_WAIT);
        givenClaim(job);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_CHECKLIST_WAIT),
                contains("checklistId"));
    }

    // ---------- REVIEW_SUBMIT ----------

    @Test
    void reviewSubmitCreatesReviewAndChildJob() {
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_SUBMIT);
        job.setChecklistId(88L);
        givenClaim(job);
        ReviewAppService.CreateResult created = new ReviewAppService.CreateResult();
        created.setReviewId(99L);
        created.setJobId(901L);
        when(reviewAppService.createInternal(ORG_ID, PROJECT_ID, CREATED_BY, 88L, null))
                .thenReturn(created);
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_REVIEW_SUBMIT), eq(JobPort.PHASE_REVIEW_WAIT),
                eq(70), eq("AI审查中"), any(Instant.class), isNull(), eq(99L), isNull());
    }

    @Test
    void reviewSubmitFailsWhenChecklistIdMissing() {
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_SUBMIT);
        givenClaim(job);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_REVIEW_SUBMIT),
                contains("checklistId"));
        verify(reviewAppService, never()).createInternal(anyLong(), anyLong(), anyLong(), any(), any());
    }

    // ---------- REVIEW_WAIT ----------

    @Test
    void reviewWaitResumesAndCompletesWithCompatPayload() {
        // 模拟「创建审查后重启」：任务以 REVIEW_WAIT + review_id 被重新认领，成功时写兼容 payload
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_WAIT);
        job.setChecklistId(88L);
        job.setReviewId(99L);
        job.setPayloadJson("{\"projectId\":100,\"phase\":\"CHECKLIST\",\"checklistId\":88,"
                + "\"reusedChecklist\":true}");
        givenClaim(job);
        when(reviewPort.findDetail(ORG_ID, PROJECT_ID, 99L))
                .thenReturn(review(99L, ReviewPort.STATUS_SUCCESS));
        when(jobPort.succeedPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_REVIEW_WAIT),
                anyString(), anyString())).thenReturn(true);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(reviewAppService, never()).createInternal(anyLong(), anyLong(), anyLong(), any(), any());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jobPort).succeedPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_REVIEW_WAIT),
                eq("审查完成"), payload.capture());
        String json = payload.getValue();
        assertTrue(json.contains("\"phase\":\"DONE\""), json);
        assertTrue(json.contains("\"checklistId\":88"), json);
        assertTrue(json.contains("\"reviewId\":99"), json);
        assertTrue(json.contains("\"reusedChecklist\":true"), json);
    }

    @Test
    void reviewWaitFailsWhenReviewFailed() {
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_WAIT);
        job.setReviewId(99L);
        givenClaim(job);
        ReviewPort.ReviewDetail detail = review(99L, ReviewPort.STATUS_FAILED);
        detail.setErrorMessage("对照失败");
        when(reviewPort.findDetail(ORG_ID, PROJECT_ID, 99L)).thenReturn(detail);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_REVIEW_WAIT),
                contains("审查失败"));
    }

    @Test
    void reviewWaitDefersWhileBuilding() {
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_WAIT);
        job.setReviewId(99L);
        givenClaim(job);
        when(reviewPort.findDetail(ORG_ID, PROJECT_ID, 99L))
                .thenReturn(review(99L, ReviewPort.STATUS_BUILDING));
        givenAdvanceOk();

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).advancePipeline(eq(JOB_ID), eq(WORKER),
                eq(JobPort.PHASE_REVIEW_WAIT), eq(JobPort.PHASE_REVIEW_WAIT),
                eq(80), eq("AI审查中"), any(Instant.class), isNull(), isNull(), isNull());
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void reviewWaitFailsWhenTimedOut() {
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_WAIT);
        job.setReviewId(99L);
        job.setPhaseStartedAt(Instant.now().minusMillis(PHASE_TIMEOUT_MS + 1000));
        givenClaim(job);
        when(reviewPort.findDetail(ORG_ID, PROJECT_ID, 99L))
                .thenReturn(review(99L, ReviewPort.STATUS_BUILDING));

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_REVIEW_WAIT),
                contains("超时"));
    }

    @Test
    void reviewWaitFailsWhenReviewIdMissing() {
        JobPort.JobRef job = job(JobPort.PHASE_REVIEW_WAIT);
        givenClaim(job);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_REVIEW_WAIT),
                contains("reviewId"));
    }

    // ---------- 租约易主保护 ----------

    @Test
    void leaseLostInWaitPhaseStopsSilently() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        givenClaim(job);
        givenDocs(
                List.of(doc(1L, 50L, DocumentAccessPort.INDEX_SUCCESS)),
                List.of());
        when(jobPort.advancePipeline(anyLong(), anyString(), anyString(), anyString(), anyInt(),
                anyString(), any(), any(), any(), any())).thenReturn(false);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort, never()).failPipeline(anyLong(), anyString(), anyString(), anyString());
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
        verify(jobPort, never()).succeedPipeline(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void leaseLostInSubmitPhaseRollsBackTransaction() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_SUBMIT);
        givenClaim(job);
        when(checklistPort.findLatestSuccess(ORG_ID, PROJECT_ID)).thenReturn(null);
        ChecklistAppService.CreateResult created = new ChecklistAppService.CreateResult();
        created.setChecklistId(88L);
        created.setJobId(900L);
        when(checklistAppService.createInternal(ORG_ID, PROJECT_ID, CREATED_BY, null, null))
                .thenReturn(created);
        when(jobPort.advancePipeline(anyLong(), anyString(), anyString(), anyString(), anyInt(),
                anyString(), any(), any(), any(), any())).thenReturn(false);

        assertTrue(executor.claimAndRunOnce(WORKER));

        // 推进未命中 → 整个 SUBMIT 事务回滚（子任务创建一并撤销），不再做任何终态/重试登记
        verify(transactionManager).rollback(any());
        verify(jobPort, never()).failPipeline(anyLong(), anyString(), anyString(), anyString());
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
    }

    // ---------- 瞬时错误重试 ----------

    @Test
    void transientErrorRetriesWithBackoff() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        givenClaim(job);
        when(packageLookupPort.findActiveByProjectIdAndType(PROJECT_ID, "TENDER"))
                .thenThrow(new QueryTimeoutException("connection reset"));
        when(jobPort.retryPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_INDEX_WAIT),
                anyString(), any(Instant.class))).thenReturn(true);
        Instant before = Instant.now();

        assertTrue(executor.claimAndRunOnce(WORKER));

        ArgumentCaptor<Instant> nextRun = ArgumentCaptor.forClass(Instant.class);
        verify(jobPort).retryPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_INDEX_WAIT),
                contains("一键审查异常"), nextRun.capture());
        assertTrue(nextRun.getValue().isAfter(before.plusMillis(RETRY_BACKOFF_MS - 2000)),
                "首次瞬时错误按基础退避推迟");
        verify(jobPort, never()).failPipeline(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void transientErrorFailsAfterMaxRetries() {
        JobPort.JobRef job = job(JobPort.PHASE_INDEX_WAIT);
        job.setRetryCount(3);
        givenClaim(job);
        when(packageLookupPort.findActiveByProjectIdAndType(PROJECT_ID, "TENDER"))
                .thenThrow(new QueryTimeoutException("connection reset"));

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_INDEX_WAIT),
                contains("重试 3 次后仍失败"));
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void bizExceptionIsFatalAndDoesNotRetry() {
        JobPort.JobRef job = job(JobPort.PHASE_CHECKLIST_SUBMIT);
        givenClaim(job);
        when(checklistPort.findLatestSuccess(ORG_ID, PROJECT_ID)).thenReturn(null);
        when(checklistAppService.createInternal(ORG_ID, PROJECT_ID, CREATED_BY, null, null))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "没有已索引成功的招标文档"));

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq(JobPort.PHASE_CHECKLIST_SUBMIT),
                contains("没有已索引成功的招标文档"));
        verify(jobPort, never()).retryPipeline(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void unknownPhaseFailsFast() {
        JobPort.JobRef job = job("WHATEVER");
        givenClaim(job);

        assertTrue(executor.claimAndRunOnce(WORKER));

        verify(jobPort).failPipeline(eq(JOB_ID), eq(WORKER), eq("WHATEVER"), contains("未知流水线阶段"));
    }

    @Test
    void returnsFalseWhenNothingToClaim() {
        when(jobPort.claimNextRunnablePipeline(JobPort.TYPE_AI_REVIEW_PIPELINE, WORKER, 60))
                .thenReturn(Optional.empty());

        assertFalse(executor.claimAndRunOnce(WORKER));

        verify(jobPort, never()).advancePipeline(anyLong(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), any(), any(), any(), any());
    }

    // ---------- 测试数据构造 ----------

    private void givenClaim(JobPort.JobRef job) {
        when(jobPort.claimNextRunnablePipeline(JobPort.TYPE_AI_REVIEW_PIPELINE, WORKER, 60))
                .thenReturn(Optional.of(job));
    }

    private void givenAdvanceOk() {
        when(jobPort.advancePipeline(anyLong(), anyString(), anyString(), anyString(), anyInt(),
                anyString(), any(Instant.class), any(), any(), any())).thenReturn(true);
    }

    private void givenDocs(List<DocumentAccessPort.DocumentRef> tenderDocs,
                           List<DocumentAccessPort.DocumentRef> bidDocs) {
        PackageLookupPort.PackageRef tender = pkg(50L, "TENDER");
        when(packageLookupPort.findActiveByProjectIdAndType(PROJECT_ID, "TENDER")).thenReturn(tender);
        PackageLookupPort.PackageRef bid = pkg(60L, "BID");
        when(packageLookupPort.listActiveByProjectIdAndType(PROJECT_ID, "BID"))
                .thenReturn(List.of(bid));
        when(documentAccessPort.listActiveByPackageId(50L)).thenReturn(tenderDocs);
        when(documentAccessPort.listActiveByPackageId(60L)).thenReturn(bidDocs);
    }

    private static JobPort.JobRef job(String phase) {
        JobPort.JobRef job = new JobPort.JobRef();
        job.setId(JOB_ID);
        job.setOrgId(ORG_ID);
        job.setProjectId(PROJECT_ID);
        job.setType(JobPort.TYPE_AI_REVIEW_PIPELINE);
        job.setStatus(JobPort.STATUS_RUNNING);
        job.setPhase(phase);
        job.setCreatedBy(CREATED_BY);
        job.setPhaseStartedAt(Instant.now());
        job.setPayloadJson("{\"projectId\":100,\"phase\":\"INDEX\",\"createdBy\":10}");
        return job;
    }

    private static PackageLookupPort.PackageRef pkg(long id, String type) {
        PackageLookupPort.PackageRef ref = new PackageLookupPort.PackageRef();
        ref.setId(id);
        ref.setOrgId(ORG_ID);
        ref.setProjectId(PROJECT_ID);
        ref.setType(type);
        ref.setStatus("ACTIVE");
        return ref;
    }

    private static DocumentAccessPort.DocumentRef doc(long id, long packageId, String indexStatus) {
        DocumentAccessPort.DocumentRef ref = new DocumentAccessPort.DocumentRef();
        ref.setId(id);
        ref.setOrgId(ORG_ID);
        ref.setProjectId(PROJECT_ID);
        ref.setPackageId(packageId);
        ref.setFileName("doc-" + id + ".pdf");
        ref.setIndexStatus(indexStatus);
        return ref;
    }

    private static RequirementChecklistPort.ChecklistDetail checklist(long id, String status, int itemCount) {
        RequirementChecklistPort.ChecklistDetail detail = new RequirementChecklistPort.ChecklistDetail();
        detail.setId(id);
        detail.setOrgId(ORG_ID);
        detail.setProjectId(PROJECT_ID);
        detail.setVersion(1);
        detail.setStatus(status);
        List<RequirementChecklistPort.ItemView> items = new java.util.ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new RequirementChecklistPort.ItemView());
        }
        detail.setItems(items);
        return detail;
    }

    private static ReviewPort.ReviewDetail review(long id, String status) {
        ReviewPort.ReviewDetail detail = new ReviewPort.ReviewDetail();
        detail.setId(id);
        detail.setOrgId(ORG_ID);
        detail.setProjectId(PROJECT_ID);
        detail.setChecklistId(88L);
        detail.setStatus(status);
        return detail;
    }
}
