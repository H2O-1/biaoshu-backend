-- M1：统一异步任务表（INDEX / 后续 BUILD_CHECKLIST / REVIEW 共用）
-- 若库中已有旧版手工/残留表，使用 IF NOT EXISTS，避免重复创建失败。
-- 注意：若 flyway_schema_history 已有 version=7 但脚本内容不同，需 DBA 核对后 repair，勿盲目改历史脚本。

CREATE TABLE IF NOT EXISTS biz_job (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL,
    project_id      BIGINT,
    type            VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    progress        INT          NOT NULL DEFAULT 0,
    message         VARCHAR(512),
    payload_json    TEXT         NOT NULL,
    error_message   TEXT,
    retry_count     INT          NOT NULL DEFAULT 0,
    created_by      BIGINT       NOT NULL,
    worker_id       VARCHAR(64),
    locked_at       TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at      TIMESTAMP,
    finished_at     TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_biz_job_status_type_created ON biz_job (status, type, created_at);
CREATE INDEX IF NOT EXISTS idx_biz_job_org_created ON biz_job (org_id, created_at);

COMMENT ON TABLE biz_job IS '统一异步任务（INDEX / BUILD_CHECKLIST / REVIEW）';
COMMENT ON COLUMN biz_job.id IS '主键，即 jobId';
COMMENT ON COLUMN biz_job.org_id IS '所属组织 ID';
COMMENT ON COLUMN biz_job.project_id IS '所属项目 ID，可空';
COMMENT ON COLUMN biz_job.type IS '任务类型：INDEX / BUILD_CHECKLIST / REVIEW';
COMMENT ON COLUMN biz_job.status IS 'PENDING / RUNNING / SUCCESS / FAILED / CANCELLED';
COMMENT ON COLUMN biz_job.progress IS '进度 0-100';
COMMENT ON COLUMN biz_job.message IS '人类可读进度或摘要';
COMMENT ON COLUMN biz_job.payload_json IS '输入参数 JSON，INDEX 含 documentId';
COMMENT ON COLUMN biz_job.error_message IS '失败详情';
COMMENT ON COLUMN biz_job.retry_count IS '重试次数';
COMMENT ON COLUMN biz_job.created_by IS '创建人用户 ID';
COMMENT ON COLUMN biz_job.worker_id IS '认领 Worker 标识';
COMMENT ON COLUMN biz_job.locked_at IS '认领时间';
COMMENT ON COLUMN biz_job.created_at IS '创建时间';
COMMENT ON COLUMN biz_job.started_at IS '开始执行时间';
COMMENT ON COLUMN biz_job.finished_at IS '结束时间';
COMMENT ON COLUMN biz_job.updated_at IS '更新时间';
