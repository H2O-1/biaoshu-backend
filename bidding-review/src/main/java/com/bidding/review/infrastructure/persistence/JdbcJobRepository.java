package com.bidding.review.infrastructure.persistence;

import com.bidding.common.port.JobPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * biz_job JDBC 实现。
 */
@Repository
public class JdbcJobRepository implements JobPort {

    private static final int NORMAL_JOB_LEASE_SECONDS = 600;

    private static final String COLUMNS = "id, org_id, project_id, type, status, progress, message, "
            + "payload_json, error_message, retry_count, created_by, worker_id, "
            + "phase, next_run_at, lease_expires_at, phase_started_at, checklist_id, review_id, "
            + "created_at, started_at, finished_at, updated_at";

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<JobRef> rowMapper = (rs, rowNum) -> {
        JobRef job = new JobRef();
        job.setId(rs.getLong("id"));
        job.setOrgId(rs.getLong("org_id"));
        long projectId = rs.getLong("project_id");
        job.setProjectId(rs.wasNull() ? null : projectId);
        job.setType(rs.getString("type"));
        job.setStatus(rs.getString("status"));
        job.setProgress(rs.getInt("progress"));
        job.setMessage(rs.getString("message"));
        job.setPayloadJson(rs.getString("payload_json"));
        job.setErrorMessage(rs.getString("error_message"));
        job.setRetryCount(rs.getInt("retry_count"));
        job.setCreatedBy(rs.getLong("created_by"));
        job.setWorkerId(rs.getString("worker_id"));
        job.setPhase(rs.getString("phase"));
        job.setNextRunAt(toInstant(rs.getTimestamp("next_run_at")));
        job.setLeaseExpiresAt(toInstant(rs.getTimestamp("lease_expires_at")));
        job.setPhaseStartedAt(toInstant(rs.getTimestamp("phase_started_at")));
        long checklistId = rs.getLong("checklist_id");
        job.setChecklistId(rs.wasNull() ? null : checklistId);
        long reviewId = rs.getLong("review_id");
        job.setReviewId(rs.wasNull() ? null : reviewId);
        job.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        job.setStartedAt(toInstant(rs.getTimestamp("started_at")));
        job.setFinishedAt(toInstant(rs.getTimestamp("finished_at")));
        job.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return job;
    };

    public JdbcJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insert(long orgId, Long projectId, String type, String status, String payloadJson, long createdBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO biz_job (org_id, project_id, type, status, progress, payload_json, "
                            + "retry_count, created_by, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 0, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, orgId);
            if (projectId == null) {
                ps.setObject(2, null);
            } else {
                ps.setLong(2, projectId);
            }
            ps.setString(3, type);
            ps.setString(4, status);
            ps.setString(5, payloadJson);
            ps.setLong(6, createdBy);
            return ps;
        }, keyHolder);

        Number key = null;
        if (keyHolder.getKeys() != null && keyHolder.getKeys().get("id") != null) {
            key = (Number) keyHolder.getKeys().get("id");
        }
        if (key == null) {
            key = keyHolder.getKey();
        }
        if (key == null) {
            throw new IllegalStateException("插入任务未返回主键");
        }
        return key.longValue();
    }

    @Override
    public Optional<JobRef> findById(long jobId) {
        List<JobRef> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM biz_job WHERE id = ?",
                rowMapper,
                jobId
        );
        if (list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    @Override
    @Transactional
    public Optional<JobRef> claimNextPending(String type, String workerId) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM biz_job WHERE status = 'PENDING' AND type = ? ORDER BY created_at ASC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                type
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long id = ids.get(0);
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'RUNNING', worker_id = ?, locked_at = CURRENT_TIMESTAMP, "
                        + "started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, "
                        + "message = '处理中', progress = 1 "
                        + "WHERE id = ? AND status = 'PENDING'",
                workerId,
                id
        );
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(id);
    }

    @Override
    @Transactional
    public Optional<JobRef> claimNextPendingOrOwnedRunning(String type, String workerId) {
        List<Long> ownedRunning = jdbcTemplate.query(
                "SELECT id FROM biz_job WHERE status = 'RUNNING' AND type = ? AND worker_id = ? "
                        + "ORDER BY updated_at ASC, created_at ASC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                type,
                workerId
        );
        if (!ownedRunning.isEmpty()) {
            return findById(ownedRunning.get(0));
        }

        List<Long> pending = jdbcTemplate.query(
                "SELECT id FROM biz_job WHERE status = 'PENDING' AND type = ? "
                        + "ORDER BY created_at ASC LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"),
                type
        );
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        long id = pending.get(0);
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'RUNNING', worker_id = ?, locked_at = CURRENT_TIMESTAMP, "
                        + "started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, "
                        + "message = '处理中', progress = 1 "
                        + "WHERE id = ? AND status = 'PENDING'",
                workerId,
                id
        );
        if (updated != 1) {
            return Optional.empty();
        }
        return findById(id);
    }

    @Override
    @Transactional
    public Optional<JobRef> claimNextRunnable(String type, String workerId) {
        int leaseSeconds = Math.max(NORMAL_JOB_LEASE_SECONDS, 10);
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM biz_job WHERE type = ? AND status IN ('PENDING', 'RUNNING') "
                        + "AND (status = 'PENDING' OR lease_expires_at IS NULL "
                        + "OR lease_expires_at <= CURRENT_TIMESTAMP) "
                        + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                (rs, rowNum) -> rs.getLong(1),
                type
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long id = ids.get(0);
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'RUNNING', worker_id = ?, locked_at = CURRENT_TIMESTAMP, "
                        + "lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), "
                        + "started_at = COALESCE(started_at, CURRENT_TIMESTAMP), "
                        + "message = CASE WHEN status = 'PENDING' THEN '处理中' ELSE message END, "
                        + "progress = CASE WHEN status = 'PENDING' THEN 1 ELSE progress END, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status IN ('PENDING', 'RUNNING') "
                        + "AND (status = 'PENDING' OR lease_expires_at IS NULL "
                        + "OR lease_expires_at <= CURRENT_TIMESTAMP)",
                workerId,
                leaseSeconds,
                id
        );
        return updated == 1 ? findById(id) : Optional.empty();
    }

    @Override
    @Transactional
    public Optional<JobRef> claimNextRunnablePipeline(String type, String workerId, int leaseSeconds) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM biz_job WHERE type = ? AND status IN ('PENDING', 'RUNNING') "
                        + "AND COALESCE(next_run_at, CURRENT_TIMESTAMP) <= CURRENT_TIMESTAMP "
                        + "AND (status = 'PENDING' OR lease_expires_at IS NULL "
                        + "OR lease_expires_at <= CURRENT_TIMESTAMP) "
                        + "ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), type);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long id = ids.get(0);
        // UPDATE 必须在最新行版本上重查全部可领取条件：
        // READ COMMITTED 下并发 SELECT 可能基于提交前的快照返回 id，
        // 仅靠 id 更新会让两个 Worker 都认领成功（二次认领竞态）
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'RUNNING', phase = COALESCE(phase, ?), "
                        + "worker_id = ?, locked_at = CURRENT_TIMESTAMP, "
                        + "lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'), "
                        + "started_at = COALESCE(started_at, CURRENT_TIMESTAMP), "
                        + "phase_started_at = COALESCE(phase_started_at, CURRENT_TIMESTAMP), "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status IN ('PENDING', 'RUNNING') "
                        + "AND COALESCE(next_run_at, CURRENT_TIMESTAMP) <= CURRENT_TIMESTAMP "
                        + "AND (status = 'PENDING' OR lease_expires_at IS NULL "
                        + "OR lease_expires_at <= CURRENT_TIMESTAMP)",
                PHASE_INDEX_SUBMIT, workerId, Math.max(leaseSeconds, 10), id);
        return updated == 1 ? findById(id) : Optional.empty();
    }

    @Override
    public boolean advancePipeline(long jobId, String workerId, String expectedPhase,
                                   String nextPhase, int progress, String message,
                                   Instant nextRunAt, Long checklistId, Long reviewId,
                                   String payloadJson) {
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET phase = ?, progress = ?, message = ?, next_run_at = ?, "
                        + "checklist_id = COALESCE(?, checklist_id), review_id = COALESCE(?, review_id), "
                        + "payload_json = COALESCE(?, payload_json), "
                        + "phase_started_at = CASE WHEN phase <> ? THEN CURRENT_TIMESTAMP ELSE phase_started_at END, "
                        + "worker_id = NULL, locked_at = NULL, lease_expires_at = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'RUNNING' AND worker_id = ? AND phase = ? "
                        + "AND lease_expires_at > CURRENT_TIMESTAMP",
                nextPhase, progress, message, Timestamp.from(nextRunAt), checklistId, reviewId,
                payloadJson, nextPhase, jobId, workerId, expectedPhase);
        return updated == 1;
    }

    @Override
    public boolean retryPipeline(long jobId, String workerId, String expectedPhase,
                                 String errorMessage, Instant nextRunAt) {
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET retry_count = retry_count + 1, message = '等待重试', "
                        + "error_message = ?, next_run_at = ?, "
                        + "worker_id = NULL, locked_at = NULL, lease_expires_at = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'RUNNING' AND worker_id = ? AND phase = ? "
                        + "AND lease_expires_at > CURRENT_TIMESTAMP",
                truncate(errorMessage), Timestamp.from(nextRunAt), jobId, workerId, expectedPhase);
        return updated == 1;
    }

    @Override
    public boolean succeedPipeline(long jobId, String workerId, String expectedPhase,
                                   String message, String payloadJson) {
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'SUCCESS', phase = '" + PHASE_SUCCESS + "', progress = 100, "
                        + "message = ?, payload_json = COALESCE(?, payload_json), error_message = NULL, "
                        + "worker_id = NULL, locked_at = NULL, lease_expires_at = NULL, "
                        + "finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'RUNNING' AND worker_id = ? AND phase = ? "
                        + "AND lease_expires_at > CURRENT_TIMESTAMP",
                message, payloadJson, jobId, workerId, expectedPhase);
        return updated == 1;
    }

    @Override
    public boolean failPipeline(long jobId, String workerId, String expectedPhase, String errorMessage) {
        int updated = jdbcTemplate.update(
                "UPDATE biz_job SET status = 'FAILED', phase = '" + PHASE_FAILED + "', message = '失败', "
                        + "error_message = ?, "
                        + "worker_id = NULL, locked_at = NULL, lease_expires_at = NULL, "
                        + "finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ? AND status = 'RUNNING' AND worker_id = ? AND phase = ? "
                        + "AND lease_expires_at > CURRENT_TIMESTAMP",
                truncate(errorMessage), jobId, workerId, expectedPhase);
        return updated == 1;
    }

    /** 错误信息最多保留 2000 字符。 */
    private static String truncate(String errorMessage) {
        if (errorMessage != null && errorMessage.length() > 2000) {
            return errorMessage.substring(0, 2000);
        }
        return errorMessage;
    }

    @Override
    public void markRunningProgress(long jobId, int progress, String message) {
        jdbcTemplate.update(
                "UPDATE biz_job SET progress = ?, message = ?, "
                        + "lease_expires_at = CASE WHEN status = 'RUNNING' "
                        + "THEN CURRENT_TIMESTAMP + (? * INTERVAL '1 second') ELSE lease_expires_at END, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                progress,
                message,
                NORMAL_JOB_LEASE_SECONDS,
                jobId
        );
    }

    @Override
    public void markSuccess(long jobId, String message) {
        jdbcTemplate.update(
                "UPDATE biz_job SET status = 'SUCCESS', progress = 100, message = ?, error_message = NULL, "
                        + "phase = CASE WHEN type = 'AI_REVIEW_PIPELINE' THEN 'SUCCESS' ELSE phase END, "
                        + "worker_id = NULL, lease_expires_at = NULL, finished_at = CURRENT_TIMESTAMP, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                message,
                jobId
        );
    }

    @Override
    public void markFailed(long jobId, String errorMessage) {
        String truncated = errorMessage;
        if (truncated != null && truncated.length() > 2000) {
            truncated = truncated.substring(0, 2000);
        }
        jdbcTemplate.update(
                "UPDATE biz_job SET status = 'FAILED', message = '失败', error_message = ?, "
                        + "phase = CASE WHEN type = 'AI_REVIEW_PIPELINE' THEN 'FAILED' ELSE phase END, "
                        + "worker_id = NULL, lease_expires_at = NULL, finished_at = CURRENT_TIMESTAMP, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                truncated,
                jobId
        );
    }

    @Override
    public int failRunningByTypes(String reason, String... types) {
        if (types == null || types.length == 0) {
            return 0;
        }
        String truncated = reason;
        if (truncated != null && truncated.length() > 2000) {
            truncated = truncated.substring(0, 2000);
        }
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                inClause.append(',');
            }
            inClause.append('?');
        }
        Object[] args = new Object[types.length + 2];
        args[0] = truncated;
        args[1] = STATUS_RUNNING;
        for (int i = 0; i < types.length; i++) {
            args[i + 2] = types[i];
        }
        return jdbcTemplate.update(
                "UPDATE biz_job SET status = 'FAILED', message = '失败', error_message = ?, "
                        + "finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE status = ? AND type IN (" + inClause + ")",
                args
        );
    }

    @Override
    public void updatePayload(long jobId, String payloadJson) {
        jdbcTemplate.update(
                "UPDATE biz_job SET payload_json = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                payloadJson,
                jobId
        );
    }

    @Override
    public boolean existsActiveByProjectIdAndType(long projectId, String type) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM biz_job "
                        + "WHERE project_id = ? AND type = ? AND status IN (?, ?)",
                Long.class,
                projectId,
                type,
                STATUS_PENDING,
                STATUS_RUNNING
        );
        return count != null && count > 0;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
