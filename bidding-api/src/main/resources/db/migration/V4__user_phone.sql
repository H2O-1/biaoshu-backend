-- 用户手机号：可空（兼容已有引导账号）；有值时唯一
ALTER TABLE sys_user ADD COLUMN phone VARCHAR(20) NULL;
CREATE UNIQUE INDEX uk_sys_user_phone ON sys_user (phone);

COMMENT ON COLUMN sys_user.phone IS '手机号（中国大陆 11 位）；可空，有值时全局唯一';
