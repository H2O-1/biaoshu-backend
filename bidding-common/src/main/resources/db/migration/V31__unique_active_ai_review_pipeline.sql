-- 同一项目最多一条活跃（PENDING / RUNNING）一键审查任务，堵住
-- 「先查存在、再插入」的并发双开竞态。
--
-- 升级前可能已存在重复活跃数据（竞态或旧版遗留），直接建唯一索引会失败，
-- 因此先去重：每个项目保留最早创建的一条活跃任务（created_at 最小，并列取 id 最小），
-- 其余标记 CANCELLED。
-- 选择说明：活跃任务代表用户的有效意图，先到者优先与应用层「请勿重复提交」语义一致；
-- 被取消的是并发/重复提交，并非执行出错，故用 CANCELLED 而非 FAILED。
-- 不处理不同项目之间的任务；不影响已为 SUCCESS / FAILED / CANCELLED 的历史任务。
UPDATE biz_job
SET status = 'CANCELLED',
    message = '重复任务已取消',
    error_message = '系统升级：同项目存在多条活跃一键审查任务，仅保留最早一条',
    worker_id = NULL,
    locked_at = NULL,
    lease_expires_at = NULL,
    finished_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE type = 'AI_REVIEW_PIPELINE'
  AND status IN ('PENDING', 'RUNNING')
  AND id NOT IN (
      SELECT DISTINCT ON (project_id) id
      FROM biz_job
      WHERE type = 'AI_REVIEW_PIPELINE'
        AND status IN ('PENDING', 'RUNNING')
      ORDER BY project_id, created_at ASC, id ASC
  );

-- 部分唯一索引：仅约束活跃任务；历史终态任务不参与，新任务在旧任务终态后可再创建。
-- project_id 为 NULL 的行按 PostgreSQL 语义互不参与唯一约束（一键审查任务必有 projectId）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_biz_job_active_ai_review_pipeline
    ON biz_job (project_id, type)
    WHERE type = 'AI_REVIEW_PIPELINE'
      AND status IN ('PENDING', 'RUNNING');

COMMENT ON INDEX uk_biz_job_active_ai_review_pipeline IS '同一项目最多一条活跃 AI_REVIEW_PIPELINE 任务（防并发双开）';
