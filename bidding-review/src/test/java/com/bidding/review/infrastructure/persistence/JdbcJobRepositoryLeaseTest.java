package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.JobPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * biz_job 租约与状态机的 JDBC 集成测试（只认真实 PostgreSQL：
 * FOR UPDATE SKIP LOCKED 只有真实 PG 可验证，H2 不支持该语义）。
 * <p>
 * 两种运行方式，取其一：
 * <ul>
 *   <li>默认：Testcontainers 自起一次性 PG 容器（pgvector/pgvector:pg16，含 vector 扩展）；</li>
 *   <li>Docker 不可用（如 Docker Desktop 4.82 npipe 拒绝 docker-java）时：
 *       通过环境变量 TEST_PG_JDBC_URL（及 TEST_PG_USER / TEST_PG_PASSWORD）指向任意
 *       可达 PG 的维护库（如本地 docker-compose.local-db.yml 的 postgres 库），
 *       测试自行建/删 scratch 数据库 bidding_lease_it / legacy_smoke，不碰业务库。</li>
 * </ul>
 * 用 bidding-common 中的正式 Flyway 迁移建库：跑通全量迁移本身即覆盖
 * 「Flyway 从空库可以执行」；{@link #migrationFromV29MapsLegacyPipelineJobs}
 * 覆盖「V29 → 最新」升级与旧数据映射。两种方式都不可用时整类跳过。
 */
class JdbcJobRepositoryLeaseTest {

    private static final String TYPE = JobPort.TYPE_AI_REVIEW_PIPELINE;
    /** 复用本地已有的 pgvector 镜像（含 vector 扩展，V8 迁移需要），避免联网拉取。 */
    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
    private static final String SCRATCH_DB = "bidding_lease_it";
    private static final String LEGACY_DB = "legacy_smoke";
    private static final String LEGACY_DUP_DB = "legacy_dup_smoke";

    private static PostgreSQLContainer<?> container;
    private static String adminJdbcUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String baseJdbcUrl;
    private static boolean externalPg;

    private static JdbcTemplate jdbc;
    private static JdbcJobRepository repository;
    private static TransactionTemplate txTemplate;

    @BeforeAll
    static void setUp() {
        String externalUrl = System.getenv("TEST_PG_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            externalPg = true;
            adminJdbcUrl = externalUrl;
            adminUser = System.getenv().getOrDefault("TEST_PG_USER", "bidding");
            adminPassword = System.getenv().getOrDefault("TEST_PG_PASSWORD", "bidding");
            JdbcTemplate admin = new JdbcTemplate(dataSource(adminJdbcUrl, adminUser, adminPassword));
            admin.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB + " WITH (FORCE)");
            admin.execute("CREATE DATABASE " + SCRATCH_DB);
            baseJdbcUrl = dbUrl(adminJdbcUrl, SCRATCH_DB);
        } else {
            try {
                container = new PostgreSQLContainer<>(PG_IMAGE);
                container.start();
            } catch (Throwable ex) {
                assumeTrue(false, "Testcontainers 不可用（" + ex.getMessage()
                        + "），且未设置 TEST_PG_JDBC_URL；跳过租约集成测试");
                return;
            }
            adminJdbcUrl = container.getJdbcUrl();
            adminUser = container.getUsername();
            adminPassword = container.getPassword();
            baseJdbcUrl = container.getJdbcUrl();
        }
        flyway(baseJdbcUrl, adminUser, adminPassword, null).migrate();
        jdbc = new JdbcTemplate(dataSource(baseJdbcUrl, adminUser, adminPassword));
        repository = new JdbcJobRepository(jdbc);
        txTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource(baseJdbcUrl, adminUser, adminPassword)));
    }

    @AfterAll
    static void tearDown() {
        if (externalPg) {
            JdbcTemplate admin = new JdbcTemplate(dataSource(adminJdbcUrl, adminUser, adminPassword));
            admin.execute("DROP DATABASE IF EXISTS " + SCRATCH_DB + " WITH (FORCE)");
            admin.execute("DROP DATABASE IF EXISTS " + LEGACY_DB + " WITH (FORCE)");
            admin.execute("DROP DATABASE IF EXISTS " + LEGACY_DUP_DB + " WITH (FORCE)");
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void cleanJobs() {
        jdbc.update("DELETE FROM biz_job");
    }

    @Test
    void flywayMigratesEmptyDatabaseToLatest() {
        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'biz_job' "
                        + "AND column_name IN ('phase', 'next_run_at', 'lease_expires_at', "
                        + "'phase_started_at', 'checklist_id', 'review_id')",
                Integer.class);
        assertEquals(6, cols, "V30 应为 biz_job 增加 6 个状态机字段");
    }

    @Test
    void concurrentClaimLetsOnlyOneWorkerWin() throws Exception {
        // 多轮压测同一竞态窗口：READ COMMITTED 快照 + SKIP LOCKED + 条件 UPDATE
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM biz_job");
            insertPipelineJob(JobPort.STATUS_PENDING, null);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<JobPort.JobRef>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String workerId = "w" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return claimInTx(workerId);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            int wins = 0;
            for (Future<Optional<JobPort.JobRef>> future : futures) {
                if (future.get(30, TimeUnit.SECONDS).isPresent()) {
                    wins++;
                }
            }
            pool.shutdownNow();
            assertEquals(1, wins, "第 " + round + " 轮：同一任务只能被一个 Worker 领取（SKIP LOCKED）");
        }
    }

    @Test
    void unexpiredLeaseBlocksTakeoverByOtherWorker() {
        insertPipelineJob(JobPort.STATUS_PENDING, null);

        Optional<JobPort.JobRef> first = claimInTx("w1");
        assertTrue(first.isPresent());

        Optional<JobPort.JobRef> second = claimInTx("w2");
        assertTrue(second.isEmpty(), "租约未过期时其他 Worker 不得接管");
    }

    @Test
    void expiredLeaseAllowsTakeoverKeepingPhase() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, null);
        Optional<JobPort.JobRef> first = claimInTx("w1");
        assertTrue(first.isPresent());
        Instant firstStartedAt = first.get().getStartedAt();
        jdbc.update("UPDATE biz_job SET phase = ?, lease_expires_at = ? WHERE id = ?",
                JobPort.PHASE_INDEX_WAIT, Timestamp.from(Instant.now().minusSeconds(5)), jobId);

        Optional<JobPort.JobRef> takeover = claimInTx("w2");

        assertTrue(takeover.isPresent(), "租约过期后其他 Worker 可接管");
        assertEquals("w2", takeover.get().getWorkerId());
        assertEquals(JobPort.STATUS_RUNNING, takeover.get().getStatus());
        assertEquals(JobPort.PHASE_INDEX_WAIT, takeover.get().getPhase(), "接管应保持当前阶段续跑");
        assertEquals(firstStartedAt, takeover.get().getStartedAt(), "started_at 仅首次写入");
    }

    @Test
    void pendingClaimInitializesPhaseAndLease() {
        insertPipelineJob(JobPort.STATUS_PENDING, null);

        Optional<JobPort.JobRef> claimed = claimInTx("w1");

        assertTrue(claimed.isPresent());
        JobPort.JobRef job = claimed.get();
        assertEquals(JobPort.STATUS_RUNNING, job.getStatus());
        assertEquals(JobPort.PHASE_INDEX_SUBMIT, job.getPhase(), "空阶段应初始化为 INDEX_SUBMIT");
        assertEquals("w1", job.getWorkerId());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getPhaseStartedAt());
        assertNotNull(job.getLeaseExpiresAt());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()), "租约应在未来过期");
    }

    @Test
    void normalPendingClaimInitializesLeaseWithoutPhase() {
        insertNormalJob(JobPort.STATUS_PENDING);

        Optional<JobPort.JobRef> claimed = claimNormalInTx("w1");

        assertTrue(claimed.isPresent());
        JobPort.JobRef job = claimed.get();
        assertEquals(JobPort.STATUS_RUNNING, job.getStatus());
        assertNull(job.getPhase(), "普通任务不使用阶段状态机");
        assertEquals("w1", job.getWorkerId());
        assertNotNull(job.getStartedAt());
        assertNotNull(job.getLeaseExpiresAt());
        assertTrue(job.getLeaseExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void normalUnexpiredLeaseBlocksOtherWorkerAndExpiredLeaseAllowsTakeover() {
        long jobId = insertNormalJob(JobPort.STATUS_PENDING);
        Optional<JobPort.JobRef> first = claimNormalInTx("w1");
        assertTrue(first.isPresent());
        Instant firstStartedAt = first.get().getStartedAt();

        assertTrue(claimNormalInTx("w2").isEmpty(), "普通任务租约未过期时不可接管");

        jdbc.update("UPDATE biz_job SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), jobId);
        Optional<JobPort.JobRef> takeover = claimNormalInTx("w2");

        assertTrue(takeover.isPresent(), "普通任务租约过期后可由新 Worker 接管");
        assertEquals("w2", takeover.get().getWorkerId());
        assertEquals(firstStartedAt, takeover.get().getStartedAt());
        assertTrue(takeover.get().getLeaseExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void runningProgressRenewsNormalLease() {
        long jobId = insertNormalJob(JobPort.STATUS_PENDING);
        Optional<JobPort.JobRef> claimed = claimNormalInTx("w1");
        assertTrue(claimed.isPresent());
        jdbc.update("UPDATE biz_job SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(5)), jobId);

        repository.markRunningProgress(jobId, 50, "处理中");

        JobPort.JobRef renewed = repository.findById(jobId).orElseThrow();
        assertEquals(50, renewed.getProgress());
        assertEquals("处理中", renewed.getMessage());
        assertTrue(renewed.getLeaseExpiresAt().isAfter(Instant.now().plusSeconds(60)),
                "进度更新应延长普通任务租约");
    }

    @Test
    void jobWithFutureNextRunAtIsNotClaimable() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, null);
        jdbc.update("UPDATE biz_job SET next_run_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().plusSeconds(3600)), jobId);

        assertTrue(claimInTx("w1").isEmpty(), "next_run_at 未到期不可领取");
    }

    @Test
    void advanceRejectsWrongWorkerOrPhaseAndReleasesLeaseOnSuccess() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, null);
        assertTrue(claimInTx("w1").isPresent());

        boolean wrongWorker = repository.advancePipeline(jobId, "w2",
                JobPort.PHASE_INDEX_SUBMIT, JobPort.PHASE_INDEX_WAIT,
                10, "x", Instant.now(), null, null, null);
        boolean wrongPhase = repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_REVIEW_WAIT, JobPort.PHASE_INDEX_WAIT,
                10, "x", Instant.now(), null, null, null);
        assertFalse(wrongWorker, "旧 Worker 不得覆盖");
        assertFalse(wrongPhase, "阶段不匹配不得覆盖");

        JobPort.JobRef untouched = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.PHASE_INDEX_SUBMIT, untouched.getPhase());
        assertEquals("w1", untouched.getWorkerId());

        Instant beforeAdvance = Instant.now().minusMillis(5);
        boolean advanced = repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_INDEX_SUBMIT, JobPort.PHASE_INDEX_WAIT,
                10, "推进", Instant.now(), 77L, null, null);
        assertTrue(advanced);

        JobPort.JobRef after = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.PHASE_INDEX_WAIT, after.getPhase());
        assertEquals(77L, after.getChecklistId());
        assertNull(after.getWorkerId(), "推进后应释放租约");
        assertNull(after.getLeaseExpiresAt());
        assertFalse(after.getPhaseStartedAt().isBefore(beforeAdvance),
                "换阶段应重置 phase_started_at");
    }

    @Test
    void samePhaseDeferKeepsPhaseStartedAt() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, null);
        assertTrue(claimInTx("w1").isPresent());
        assertTrue(repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_INDEX_SUBMIT, JobPort.PHASE_INDEX_WAIT,
                10, "推进", Instant.now(), null, null, null));
        Instant waitPhaseStartedAt = repository.findById(jobId).orElseThrow().getPhaseStartedAt();

        assertTrue(claimInTx("w1").isPresent(), "租约已释放且 next_run_at 到期，可再次认领");
        assertTrue(repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_INDEX_WAIT, JobPort.PHASE_INDEX_WAIT,
                12, "继续等待", Instant.now().plusSeconds(3), null, null, null));

        JobPort.JobRef deferred = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.PHASE_INDEX_WAIT, deferred.getPhase());
        assertEquals(waitPhaseStartedAt, deferred.getPhaseStartedAt(),
                "同阶段推迟应保留 phase_started_at（阶段超时据此计时）");
        assertNotNull(deferred.getNextRunAt());
    }

    @Test
    void succeedPipelineIsConditionalAndWritesTerminalState() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, JobPort.PHASE_REVIEW_WAIT);
        assertTrue(claimInTx("w1").isPresent());

        assertFalse(repository.succeedPipeline(jobId, "w2",
                JobPort.PHASE_REVIEW_WAIT, "ok", null), "其他 Worker 不得标记成功");

        String payload = "{\"projectId\":100,\"phase\":\"DONE\",\"reviewId\":99}";
        assertTrue(repository.succeedPipeline(jobId, "w1",
                JobPort.PHASE_REVIEW_WAIT, "审查完成", payload));

        JobPort.JobRef done = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.STATUS_SUCCESS, done.getStatus());
        assertEquals(JobPort.PHASE_SUCCESS, done.getPhase());
        assertEquals(100, done.getProgress());
        assertEquals(payload, done.getPayloadJson());
        assertNotNull(done.getFinishedAt());
        assertNull(done.getWorkerId());

        assertTrue(claimInTx("w3").isEmpty(), "终态任务不可再被认领");
        assertFalse(repository.failPipeline(jobId, "w1", JobPort.PHASE_REVIEW_WAIT, "x"),
                "终态任务不可再被覆盖");
    }

    @Test
    void failPipelineIsConditionalAndTruncatesError() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, JobPort.PHASE_CHECKLIST_WAIT);
        assertTrue(claimInTx("w1").isPresent());

        assertFalse(repository.failPipeline(jobId, "w2",
                JobPort.PHASE_CHECKLIST_WAIT, "x"), "其他 Worker 不得标记失败");

        String longError = "x".repeat(3000);
        assertTrue(repository.failPipeline(jobId, "w1", JobPort.PHASE_CHECKLIST_WAIT, longError));

        JobPort.JobRef failed = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.STATUS_FAILED, failed.getStatus());
        assertEquals(JobPort.PHASE_FAILED, failed.getPhase());
        assertEquals(2000, failed.getErrorMessage().length(), "错误信息最多保留 2000 字符");
        assertNotNull(failed.getFinishedAt());
    }

    @Test
    void retryPipelineIncrementsCountAndDefers() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, JobPort.PHASE_INDEX_WAIT);
        assertTrue(claimInTx("w1").isPresent());

        Instant nextRun = Instant.ofEpochSecond(Instant.now().plusSeconds(20).getEpochSecond());
        assertTrue(repository.retryPipeline(jobId, "w1", JobPort.PHASE_INDEX_WAIT, "网络超时", nextRun));

        JobPort.JobRef retried = repository.findById(jobId).orElseThrow();
        assertEquals(1, retried.getRetryCount());
        assertEquals("网络超时", retried.getErrorMessage());
        assertEquals(nextRun, retried.getNextRunAt());
        assertEquals(JobPort.STATUS_RUNNING, retried.getStatus());
        assertNull(retried.getWorkerId(), "登记重试后释放租约");

        assertFalse(repository.retryPipeline(jobId, "w1", JobPort.PHASE_INDEX_WAIT, "x", nextRun),
                "租约释放后旧 Worker 不得再登记");
    }

    @Test
    void validLeaseAllowsOriginalWorkerToAdvance() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, null);
        assertTrue(claimInTx("w1").isPresent());

        assertTrue(repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_INDEX_SUBMIT, JobPort.PHASE_INDEX_WAIT,
                10, "推进", Instant.now(), null, null, null),
                "租约有效时原 Worker 可以推进");
    }

    @Test
    void expiredLeaseBlocksAllWritesFromOriginalWorker() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, JobPort.PHASE_INDEX_WAIT);
        assertTrue(claimInTx("w1").isPresent());

        // 强制租约过期（模拟阶段处理超过租约秒数仍未完成）
        jdbc.update("UPDATE biz_job SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), jobId);

        assertFalse(repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_INDEX_WAIT, JobPort.PHASE_CHECKLIST_SUBMIT,
                35, "x", Instant.now(), null, null, null), "租约过期后原 Worker 不能推进");
        assertFalse(repository.retryPipeline(jobId, "w1", JobPort.PHASE_INDEX_WAIT,
                "x", Instant.now()), "租约过期后原 Worker 不能安排重试");
        assertFalse(repository.succeedPipeline(jobId, "w1", JobPort.PHASE_INDEX_WAIT,
                "x", null), "租约过期后原 Worker 不能标记成功");
        assertFalse(repository.failPipeline(jobId, "w1", JobPort.PHASE_INDEX_WAIT, "x"),
                "租约过期后原 Worker 不能标记失败");

        JobPort.JobRef job = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.STATUS_RUNNING, job.getStatus(), "过期租约的任何写入都不得生效");
        assertEquals(JobPort.PHASE_INDEX_WAIT, job.getPhase());
    }

    @Test
    void afterTakeoverNewWorkerAdvancesAndOldWorkerCannotOverwrite() {
        long jobId = insertPipelineJob(JobPort.STATUS_PENDING, JobPort.PHASE_INDEX_WAIT);
        assertTrue(claimInTx("w1").isPresent());
        jdbc.update("UPDATE biz_job SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), jobId);

        Optional<JobPort.JobRef> takeover = claimInTx("w2");
        assertTrue(takeover.isPresent(), "租约过期后新 Worker 可接管");
        assertTrue(repository.advancePipeline(jobId, "w2",
                JobPort.PHASE_INDEX_WAIT, JobPort.PHASE_CHECKLIST_SUBMIT,
                35, "推进", Instant.now(), null, null, null), "新 Worker 租约有效可正常推进");

        // 旧 Worker 持过期上下文写入：worker_id 已不匹配，且其原租约已过期
        assertFalse(repository.advancePipeline(jobId, "w1",
                JobPort.PHASE_INDEX_WAIT, JobPort.PHASE_REVIEW_WAIT,
                55, "x", Instant.now(), null, null, null), "旧 Worker 不得推进");
        assertFalse(repository.succeedPipeline(jobId, "w1", JobPort.PHASE_INDEX_WAIT,
                "x", null), "旧 Worker 不得标记成功");

        JobPort.JobRef job = repository.findById(jobId).orElseThrow();
        assertEquals(JobPort.PHASE_CHECKLIST_SUBMIT, job.getPhase(), "旧 Worker 不得覆盖新 Worker 的结果");
    }

    @Test
    void migrationFromV29MapsLegacyPipelineJobs() {
        // 独立 scratch 数据库：先在 V29 基线上造旧形态数据，再升级到最新验证 V30 映射
        JdbcTemplate admin = new JdbcTemplate(dataSource(adminJdbcUrl, adminUser, adminPassword));
        admin.execute("DROP DATABASE IF EXISTS " + LEGACY_DB + " WITH (FORCE)");
        admin.execute("CREATE DATABASE " + LEGACY_DB);
        String legacyUrl = dbUrl(adminJdbcUrl, LEGACY_DB);
        flyway(legacyUrl, adminUser, adminPassword, "29").migrate();

        JdbcTemplate legacy = new JdbcTemplate(dataSource(legacyUrl, adminUser, adminPassword));
        // V29 之前的旧形态：无 phase / next_run_at / lease 字段
        long successId = legacy.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at) "
                        + "VALUES (2, 100, ?, 'SUCCESS', 100, '{}', 0, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, TYPE);
        long failedId = legacy.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at) "
                        + "VALUES (2, 100, ?, 'FAILED', 0, '{}', 0, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, TYPE);
        long pendingId = legacy.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at) "
                        + "VALUES (2, 101, ?, 'PENDING', 0, '{}', 0, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, TYPE);
        long runningId = legacy.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, worker_id, locked_at, started_at, created_at, updated_at) "
                        + "VALUES (2, 102, ?, 'RUNNING', 40, '{}', 0, 10, 'old-worker', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, TYPE);
        long cancelledId = legacy.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at) "
                        + "VALUES (2, 103, ?, 'CANCELLED', 0, '{}', 0, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, TYPE);
        long indexJobId = legacy.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, worker_id, locked_at, started_at, created_at, updated_at) "
                        + "VALUES (2, 100, 'INDEX', 'RUNNING', 40, '{}', 0, 10, 'old-worker', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class);

        flyway(legacyUrl, adminUser, adminPassword, null).migrate();

        JdbcJobRepository legacyRepository = new JdbcJobRepository(legacy);
        assertEquals(JobPort.PHASE_SUCCESS,
                legacyRepository.findById(successId).orElseThrow().getPhase());
        assertEquals(JobPort.PHASE_FAILED,
                legacyRepository.findById(failedId).orElseThrow().getPhase());
        assertEquals(JobPort.PHASE_INDEX_SUBMIT,
                legacyRepository.findById(pendingId).orElseThrow().getPhase());

        JobPort.JobRef migratedRunning = legacyRepository.findById(runningId).orElseThrow();
        assertEquals(JobPort.STATUS_FAILED, migratedRunning.getStatus(),
                "旧版 RUNNING 未持久化 checklist_id/review_id，无法无损续跑，统一置 FAILED");
        assertEquals(JobPort.PHASE_FAILED, migratedRunning.getPhase());
        assertTrue(migratedRunning.getErrorMessage().contains("系统升级"),
                "错误信息应说明旧版运行中任务无法安全续跑: " + migratedRunning.getErrorMessage());
        assertNull(migratedRunning.getWorkerId(), "V30 应释放旧 Worker");
        assertNotNull(migratedRunning.getFinishedAt());

        // CANCELLED 不映射：状态与 phase（NULL）都不变
        JobPort.JobRef cancelled = legacyRepository.findById(cancelledId).orElseThrow();
        assertEquals(JobPort.STATUS_CANCELLED, cancelled.getStatus());
        assertNull(cancelled.getPhase());

        // 非流水线任务不受 V30 影响
        JobPort.JobRef indexJob = legacyRepository.findById(indexJobId).orElseThrow();
        assertNull(indexJob.getPhase());
        assertEquals("old-worker", indexJob.getWorkerId());

        // 迁移后：旧 PENDING 任务可被新 Worker 认领重驱动；旧 RUNNING（已 FAILED）不会被认领
        Optional<JobPort.JobRef> claimed = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource(legacyUrl, adminUser, adminPassword)))
                .execute(tx -> legacyRepository.claimNextRunnablePipeline(TYPE, "new-worker", 60));
        assertTrue(claimed.isPresent(), "旧 PENDING 任务迁移后可被新 Worker 认领");
        assertEquals(pendingId, claimed.get().getId());
        assertEquals(JobPort.PHASE_INDEX_SUBMIT, claimed.get().getPhase());
    }

    @Test
    void migrationToLatestCleansDuplicateActivePipelinesAndEnforcesUnique() {
        // 场景：升级到 V31 前同项目已有多条活跃一键审查任务 —— 迁移必须先去重再建唯一索引
        JdbcTemplate admin = new JdbcTemplate(dataSource(adminJdbcUrl, adminUser, adminPassword));
        admin.execute("DROP DATABASE IF EXISTS " + LEGACY_DUP_DB + " WITH (FORCE)");
        admin.execute("CREATE DATABASE " + LEGACY_DUP_DB);
        String dupUrl = dbUrl(adminJdbcUrl, LEGACY_DUP_DB);
        flyway(dupUrl, adminUser, adminPassword, "30").migrate();

        JdbcTemplate dup = new JdbcTemplate(dataSource(dupUrl, adminUser, adminPassword));
        // p200：两条活跃（早 PENDING + 晚 RUNNING）+ 一条历史 SUCCESS
        long earliestId = insertLegacyPipeline(dup, 200, "PENDING", "2 hours");
        long duplicateId = insertLegacyPipeline(dup, 200, "RUNNING", "1 hour");
        insertLegacyPipeline(dup, 200, "SUCCESS", "3 hours");
        // p201：一条活跃；不同项目互不处理
        long otherProjectId = insertLegacyPipeline(dup, 201, "PENDING", "1 hour");

        flyway(dupUrl, adminUser, adminPassword, null).migrate();

        JobPort.JobRef kept = new JdbcJobRepository(dup).findById(earliestId).orElseThrow();
        assertEquals(JobPort.STATUS_PENDING, kept.getStatus(), "保留每个项目最早一条活跃任务");
        JobPort.JobRef cancelled = new JdbcJobRepository(dup).findById(duplicateId).orElseThrow();
        assertEquals(JobPort.STATUS_CANCELLED, cancelled.getStatus(), "重复活跃任务标记 CANCELLED");
        assertTrue(cancelled.getErrorMessage().contains("仅保留最早一条"));
        assertEquals(JobPort.STATUS_PENDING,
                new JdbcJobRepository(dup).findById(otherProjectId).orElseThrow().getStatus());

        String indexName = dup.queryForObject(
                "SELECT indexname FROM pg_indexes WHERE indexname = 'uk_biz_job_active_ai_review_pipeline'",
                String.class);
        assertNotNull(indexName, "V31 应创建活跃任务部分唯一索引");

        // 索引生效：同项目再插入活跃任务被拒绝；项目 202 不受影响
        assertThrowsDuplicateKey(() -> insertPipeline(dup, 200, "PENDING"));
        insertPipeline(dup, 202, "PENDING");
        // 保留的活跃任务终态化后，同项目可再创建
        dup.update("UPDATE biz_job SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                earliestId);
        insertPipeline(dup, 200, "PENDING");
    }

    @Test
    void sameProjectConcurrentInsertAllowsOnlyOneActive() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    repository.insert(2L, 300L, TYPE, JobPort.STATUS_PENDING, "{}", 10L);
                    return "OK";
                } catch (DuplicateKeyException ex) {
                    return "DUP";
                }
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(1, results.stream().filter("OK"::equals).count(),
                "同项目并发插入活跃任务只能成功一个");
        assertEquals(1, results.stream().filter("DUP"::equals).count(),
                "另一个被部分唯一索引拒绝（DuplicateKeyException）");
    }

    @Test
    void differentProjectsAndTerminalHistoryDoNotBlock() {
        repository.insert(2L, 300L, TYPE, JobPort.STATUS_PENDING, "{}", 10L);
        // 不同项目可同时活跃
        repository.insert(2L, 301L, TYPE, JobPort.STATUS_PENDING, "{}", 10L);

        // 历史终态任务不阻止新任务
        repository.insert(2L, 302L, TYPE, JobPort.STATUS_SUCCESS, "{}", 10L);
        repository.insert(2L, 302L, TYPE, JobPort.STATUS_FAILED, "{}", 10L);
        repository.insert(2L, 302L, TYPE, JobPort.STATUS_PENDING, "{}", 10L);

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_job WHERE type = ? AND status IN ('PENDING', 'RUNNING')",
                Integer.class, TYPE);
        assertEquals(3, active);
    }

    // ---------- 工具 ----------

    private static PGSimpleDataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static Flyway flyway(String url, String user, String password, String target) {
        var configured = Flyway.configure()
                .dataSource(url, user, password)
                .locations("filesystem:" + migrationsDir());
        if (target != null) {
            configured.target(target);
        }
        return configured.load();
    }

    /** 迁移目录：兼容 Maven（模块目录）与 IDE（backend 或仓库根目录）工作目录。 */
    private static String migrationsDir() {
        String[] candidates = {
                "../bidding-common/src/main/resources/db/migration",
                "bidding-common/src/main/resources/db/migration",
                "backend/bidding-common/src/main/resources/db/migration"
        };
        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (dir.isDirectory()) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 Flyway 迁移目录 db/migration");
    }

    /** 把 jdbc:postgresql://host:port/某库[?参数] 的库名替换成目标库。 */
    private static String dbUrl(String jdbcUrl, String dbName) {
        int queryStart = jdbcUrl.indexOf('?');
        String noQuery = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
        String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
        return noQuery.substring(0, noQuery.lastIndexOf('/')) + "/" + dbName + query;
    }

    private Optional<JobPort.JobRef> claimInTx(String workerId) {
        return txTemplate.execute(tx ->
                repository.claimNextRunnablePipeline(TYPE, workerId, 60));
    }

    private Optional<JobPort.JobRef> claimNormalInTx(String workerId) {
        return txTemplate.execute(tx ->
                repository.claimNextRunnable(JobPort.TYPE_TENDER_ANALYSIS, workerId));
    }

    private static long insertPipelineJob(String status, String phase) {
        return jdbc.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at, phase) "
                        + "VALUES (2, 100, ?, ?, 0, '{\"projectId\":100}', 0, 10, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?) RETURNING id",
                Long.class, TYPE, status, phase);
    }

    private static long insertNormalJob(String status) {
        return jdbc.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at) "
                        + "VALUES (2, 100, ?, ?, 0, '{\"analysisId\":50}', 0, 10, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, JobPort.TYPE_TENDER_ANALYSIS, status);
    }

    /** 在指定库直接插入一键审查任务（绕开应用层，验证数据库唯一约束本身）。 */
    private static long insertPipeline(JdbcTemplate template, long projectId, String status) {
        return template.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, created_at, updated_at) "
                        + "VALUES (2, ?, ?, ?, 0, '{}', 0, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id",
                Long.class, projectId, TYPE, status);
    }

    /** 造 V31 之前的活跃/历史任务（createdAgo 为 PostgreSQL interval 文本，如 '2 hours'）。 */
    private static long insertLegacyPipeline(JdbcTemplate template, long projectId, String status,
                                             String createdAgo) {
        return template.queryForObject(
                "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                        + "retry_count, created_by, worker_id, created_at, updated_at) "
                        + "VALUES (2, ?, ?, ?, 0, '{}', 0, 10, "
                        + "CASE WHEN ? = 'RUNNING' THEN 'old-worker' ELSE NULL END, "
                        + "CURRENT_TIMESTAMP - (?::interval), CURRENT_TIMESTAMP) RETURNING id",
                Long.class, projectId, TYPE, status, status, createdAgo);
    }

    private static void assertThrowsDuplicateKey(Runnable insert) {
        try {
            insert.run();
        } catch (DuplicateKeyException ex) {
            assertTrue(ex.getMessage() == null
                            || ex.getMessage().contains("uk_biz_job_active_ai_review_pipeline"),
                    "应是活跃一键审查唯一索引冲突: " + ex.getMessage());
            return;
        }
        throw new AssertionError("预期 DuplicateKeyException（同项目活跃任务唯一约束）");
    }

}
