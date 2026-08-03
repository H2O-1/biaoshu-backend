-- 标包主数据（招标包 / 投标包合表；文件下期再挂）
-- 招标包：可先挂组织、project_id 为空；创建项目时绑定，一项目仅一个招标包
-- 投标包：创建时即带 project_id；同项目下按名称区分
-- 唯一性（未删除范围内）：应用层校验。
--   招标包：同组织 name 唯一；已绑定的 project_id 全局唯一（一项目一招标包）
--   投标包：同 project_id 下 name 唯一
CREATE TABLE biz_package (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL,
    project_id      BIGINT,
    type            VARCHAR(16)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_biz_package_org_type_status ON biz_package (org_id, type, status);
CREATE INDEX idx_biz_package_project_id ON biz_package (project_id);
CREATE INDEX idx_biz_package_org_type_name ON biz_package (org_id, type, name);

COMMENT ON TABLE biz_package IS '标包（招标包/投标包合表；归属组织，可绑定项目）';
COMMENT ON COLUMN biz_package.id IS '主键';
COMMENT ON COLUMN biz_package.org_id IS '所属组织 ID，关联 sys_org.id';
COMMENT ON COLUMN biz_package.project_id IS '绑定项目 ID，关联 biz_project.id；招标包未绑定时为空，投标包必填';
COMMENT ON COLUMN biz_package.type IS '包类型：TENDER 招标包 / BID 投标包';
COMMENT ON COLUMN biz_package.name IS '包名称（招标包同组织唯一；投标包同项目唯一，应用层保证）';
COMMENT ON COLUMN biz_package.status IS '状态：ACTIVE 正常 / DELETED 已删除';
COMMENT ON COLUMN biz_package.created_by IS '创建人用户 ID，关联 sys_user.id';
COMMENT ON COLUMN biz_package.created_at IS '创建时间';
COMMENT ON COLUMN biz_package.updated_at IS '更新时间';
