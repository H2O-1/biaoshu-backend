-- 身份与平台角色（不含项目 / 招投标文件）

CREATE TABLE sys_org (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_org_code UNIQUE (code)
);

CREATE TABLE sys_user (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL REFERENCES sys_org (id),
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE INDEX idx_sys_user_org ON sys_user (org_id);

CREATE TABLE sys_role (
    code            VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL
);

CREATE TABLE sys_user_role (
    user_id         BIGINT       NOT NULL REFERENCES sys_user (id),
    role_code       VARCHAR(64)  NOT NULL REFERENCES sys_role (code),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_code)
);

CREATE INDEX idx_sys_user_role_code ON sys_user_role (role_code);

INSERT INTO sys_org (code, name, status)
VALUES ('SYSTEM', '系统组织', 'ACTIVE');

INSERT INTO sys_role (code, name) VALUES
    ('PLATFORM_ADMIN', '平台管理员'),
    ('TENDERER', '招标方'),
    ('BIDDER', '投标方'),
    ('EXPERT', '专家');
