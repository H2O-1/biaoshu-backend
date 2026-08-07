-- AI_REVIEW_PIPELINE persistent state machine and Worker lease.
ALTER TABLE biz_job ADD COLUMN IF NOT EXISTS phase VARCHAR(32);
ALTER TABLE biz_job ADD COLUMN IF NOT EXISTS next_run_at TIMESTAMP;
ALTER TABLE biz_job ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE biz_job ADD COLUMN IF NOT EXISTS phase_started_at TIMESTAMP;
ALTER TABLE biz_job ADD COLUMN IF NOT EXISTS checklist_id BIGINT;
ALTER TABLE biz_job ADD COLUMN IF NOT EXISTS review_id BIGINT;

-- 旧版一键审查任务映射（仅处理本次新增的 phase 仍为 NULL 的行）：
--   SUCCESS / FAILED → 同名终态阶段，状态不变；
--   PENDING → INDEX_SUBMIT：SUBMIT 阶段幂等（SUCCESS 文件跳过、PROCESSING 不重复提交、
--             成功清单复用），可由新 Worker 安全重驱动；
--   旧版 RUNNING → FAILED：旧实现长时间占线程等待，未持久化 checklist_id / review_id，
--             无法保证无损续跑；若从 INDEX_SUBMIT 重跑可能重复创建清单/审查，
--             故统一置失败、释放 worker_id / locked_at，提示用户重新发起；
--   CANCELLED 不映射（终态，不属于流水线阶段模型）。
UPDATE biz_job
SET phase = CASE
        WHEN status IN ('SUCCESS', 'FAILED') THEN status
        WHEN status = 'PENDING' THEN 'INDEX_SUBMIT'
        ELSE 'FAILED'
    END,
    status = CASE
        WHEN status = 'RUNNING' THEN 'FAILED'
        ELSE status
    END,
    message = CASE
        WHEN status = 'RUNNING' THEN '失败'
        ELSE message
    END,
    error_message = CASE
        WHEN status = 'RUNNING' THEN '系统升级，旧版运行中任务无法安全续跑，请重新发起'
        ELSE error_message
    END,
    next_run_at = CASE
        WHEN status = 'PENDING' THEN CURRENT_TIMESTAMP
        ELSE next_run_at
    END,
    phase_started_at = COALESCE(started_at, created_at),
    worker_id = CASE WHEN status = 'RUNNING' THEN NULL ELSE worker_id END,
    locked_at = CASE WHEN status = 'RUNNING' THEN NULL ELSE locked_at END,
    finished_at = CASE WHEN status = 'RUNNING' THEN CURRENT_TIMESTAMP ELSE finished_at END
WHERE type = 'AI_REVIEW_PIPELINE' AND phase IS NULL AND status <> 'CANCELLED';

CREATE INDEX IF NOT EXISTS idx_biz_job_pipeline_runnable
    ON biz_job (type, status, next_run_at, lease_expires_at, created_at);

COMMENT ON COLUMN biz_job.phase IS 'Pipeline phase; null for ordinary jobs';
COMMENT ON COLUMN biz_job.next_run_at IS 'Earliest time at which a Worker may advance the job';
COMMENT ON COLUMN biz_job.lease_expires_at IS 'Worker lease expiry; another instance may claim after expiry';
COMMENT ON COLUMN biz_job.phase_started_at IS 'Time at which the current pipeline phase started';
COMMENT ON COLUMN biz_job.checklist_id IS 'Checklist created by the pipeline';
COMMENT ON COLUMN biz_job.review_id IS 'Review created by the pipeline';
