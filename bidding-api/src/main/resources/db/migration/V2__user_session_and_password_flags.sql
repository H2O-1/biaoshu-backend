-- 会话吊销（token_version）+ 强制改密标记
ALTER TABLE sys_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sys_user ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
