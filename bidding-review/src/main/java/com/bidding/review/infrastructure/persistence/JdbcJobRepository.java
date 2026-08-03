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

    private static final String COLUMNS = "id, org_id, project_id, type, status, progress, message, "
            + "payload_json, error_message, retry_count, created_by, worker_id, "
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
    public void markRunningProgress(long jobId, int progress, String message) {
        jdbcTemplate.update(
                "UPDATE biz_job SET progress = ?, message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                progress,
                message,
                jobId
        );
    }

    @Override
    public void markSuccess(long jobId, String message) {
        jdbcTemplate.update(
                "UPDATE biz_job SET status = 'SUCCESS', progress = 100, message = ?, error_message = NULL, "
                        + "finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
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
                        + "finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                truncated,
                jobId
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
