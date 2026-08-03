-- 用户账号记录创建人（平台管理员创建账号时写入）
ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS created_by BIGINT NULL REFERENCES sys_user (id);

COMMENT ON COLUMN sys_user.created_by IS '创建人用户 ID；引导账号可为空';
