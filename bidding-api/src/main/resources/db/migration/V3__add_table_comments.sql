-- 表与字段中文注释（PostgreSQL / H2 PostgreSQL 模式均支持 COMMENT ON）

-- ---------- sys_org 组织 ----------
COMMENT ON TABLE sys_org IS '组织（平台级，非项目）';
COMMENT ON COLUMN sys_org.id IS '主键';
COMMENT ON COLUMN sys_org.code IS '组织编码，唯一';
COMMENT ON COLUMN sys_org.name IS '组织名称';
COMMENT ON COLUMN sys_org.status IS '状态：ACTIVE 等';
COMMENT ON COLUMN sys_org.created_at IS '创建时间';
COMMENT ON COLUMN sys_org.updated_at IS '更新时间';

-- ---------- sys_user 用户 ----------
COMMENT ON TABLE sys_user IS '平台用户账号';
COMMENT ON COLUMN sys_user.id IS '主键';
COMMENT ON COLUMN sys_user.org_id IS '所属组织 ID，关联 sys_org.id';
COMMENT ON COLUMN sys_user.username IS '登录用户名，唯一';
COMMENT ON COLUMN sys_user.password_hash IS '密码哈希（BCrypt），不明文存库';
COMMENT ON COLUMN sys_user.display_name IS '显示名称';
COMMENT ON COLUMN sys_user.status IS '账号状态：ACTIVE 正常 / DISABLED 停用 / DELETED 逻辑删除';
COMMENT ON COLUMN sys_user.must_change_password IS '是否必须修改密码（首次登录或重置后为 true）';
COMMENT ON COLUMN sys_user.token_version IS '会话版本号；变更密码/角色/停用/删除/退出时自增，用于吊销旧 JWT';
COMMENT ON COLUMN sys_user.created_at IS '创建时间';
COMMENT ON COLUMN sys_user.updated_at IS '更新时间';

-- ---------- sys_role 平台角色字典 ----------
COMMENT ON TABLE sys_role IS '平台角色字典（账号级角色，非项目成员角色）';
COMMENT ON COLUMN sys_role.code IS '角色编码，主键，如 PLATFORM_ADMIN';
COMMENT ON COLUMN sys_role.name IS '角色显示名称';

-- ---------- sys_user_role 用户角色关联 ----------
COMMENT ON TABLE sys_user_role IS '用户与平台角色关联表';
COMMENT ON COLUMN sys_user_role.user_id IS '用户 ID，关联 sys_user.id';
COMMENT ON COLUMN sys_user_role.role_code IS '角色编码，关联 sys_role.code';
COMMENT ON COLUMN sys_user_role.created_at IS '绑定时间';
