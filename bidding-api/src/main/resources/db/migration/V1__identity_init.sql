-- 身份与平台角色（首发基线：合并原 V1~V4）
-- 不含项目 / 招投标文件业务表

-- ---------- 组织 ----------
CREATE TABLE sys_org (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_org_code UNIQUE (code)
);

COMMENT ON TABLE sys_org IS '组织';
COMMENT ON COLUMN sys_org.id IS '主键';
COMMENT ON COLUMN sys_org.code IS '组织编码，唯一';
COMMENT ON COLUMN sys_org.name IS '组织名称';
COMMENT ON COLUMN sys_org.status IS '状态：ACTIVE 等';
COMMENT ON COLUMN sys_org.created_at IS '创建时间';
COMMENT ON COLUMN sys_org.updated_at IS '更新时间';

-- ---------- 用户 ----------
CREATE TABLE sys_user (
    id                    BIGSERIAL PRIMARY KEY,
    org_id                BIGINT       NOT NULL REFERENCES sys_org (id),
    username              VARCHAR(64)  NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    display_name          VARCHAR(128) NOT NULL,
    phone                 VARCHAR(20)  NULL,
    email                 VARCHAR(128) NULL,
    status                VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    token_version         INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE INDEX idx_sys_user_org ON sys_user (org_id);
CREATE UNIQUE INDEX uk_sys_user_phone ON sys_user (phone);
CREATE UNIQUE INDEX uk_sys_user_email ON sys_user (email);

COMMENT ON TABLE sys_user IS '平台用户账号';
COMMENT ON COLUMN sys_user.id IS '主键';
COMMENT ON COLUMN sys_user.org_id IS '所属组织 ID，关联 sys_org.id';
COMMENT ON COLUMN sys_user.username IS '登录用户名，唯一';
COMMENT ON COLUMN sys_user.password_hash IS '密码哈希（BCrypt），不明文存库';
COMMENT ON COLUMN sys_user.display_name IS '显示名称';
COMMENT ON COLUMN sys_user.phone IS '手机号（中国大陆 11 位）；可空，有值时全局唯一';
COMMENT ON COLUMN sys_user.email IS '邮箱；可空，有值时全局唯一';
COMMENT ON COLUMN sys_user.status IS '账号状态：ACTIVE 正常 / DISABLED 停用 / DELETED 逻辑删除';
COMMENT ON COLUMN sys_user.must_change_password IS '是否必须修改密码（首次登录或重置后为 true）';
COMMENT ON COLUMN sys_user.token_version IS '会话版本号；变更密码/角色/停用/删除/退出时自增，用于吊销旧 JWT';
COMMENT ON COLUMN sys_user.created_at IS '创建时间';
COMMENT ON COLUMN sys_user.updated_at IS '更新时间';

-- ---------- 平台角色字典 ----------
CREATE TABLE sys_role (
    code            VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL
);

COMMENT ON TABLE sys_role IS '平台角色字典';
COMMENT ON COLUMN sys_role.code IS '角色编码，主键，如 PLATFORM_ADMIN';
COMMENT ON COLUMN sys_role.name IS '角色显示名称';

-- ---------- 用户角色关联 ----------
CREATE TABLE sys_user_role (
    user_id         BIGINT       NOT NULL REFERENCES sys_user (id),
    role_code       VARCHAR(64)  NOT NULL REFERENCES sys_role (code),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_code)
);

CREATE INDEX idx_sys_user_role_code ON sys_user_role (role_code);

COMMENT ON TABLE sys_user_role IS '用户与平台角色关联表';
COMMENT ON COLUMN sys_user_role.user_id IS '用户 ID，关联 sys_user.id';
COMMENT ON COLUMN sys_user_role.role_code IS '角色编码，关联 sys_role.code';
COMMENT ON COLUMN sys_user_role.created_at IS '绑定时间';

-- ---------- 种子数据 ----------
INSERT INTO sys_org (code, name, status)
VALUES ('SYSTEM', '系统组织', 'ACTIVE');

INSERT INTO sys_role (code, name) VALUES
    ('PLATFORM_ADMIN', '平台管理员'),
    ('TENDERER', '招标方'),
    ('BIDDER', '投标方'),
    ('EXPERT', '专家');
