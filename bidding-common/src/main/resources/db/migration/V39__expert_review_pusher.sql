-- 专家评审推送人：记录真正点击推送的用户

ALTER TABLE biz_expert_review
    ADD COLUMN IF NOT EXISTS pushed_by BIGINT;

UPDATE biz_expert_review
SET pushed_by = created_by
WHERE pushed_by IS NULL
  AND status IN ('RUNNING', 'SUCCESS', 'FAILED');

COMMENT ON COLUMN biz_expert_review.pushed_by IS '推送人用户 ID；历史已推送数据回填为创建人';
