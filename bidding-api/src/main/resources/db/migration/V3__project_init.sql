-- 项目主数据（招标方本组织下创建与管理；无项目成员表）
CREATE TABLE biz_project (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL,
    name            VARCHAR(128) NOT NULL,
    agency          VARCHAR(128) NOT NULL,
    project_type    VARCHAR(64)  NOT NULL,
    leader_name     VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_biz_project_org_status ON biz_project (org_id, status);
CREATE INDEX idx_biz_project_org_name ON biz_project (org_id, name);

COMMENT ON TABLE biz_project IS '项目（归属组织；由招标方创建与管理）';
COMMENT ON COLUMN biz_project.id IS '主键';
COMMENT ON COLUMN biz_project.org_id IS '所属组织 ID，关联 sys_org.id';
COMMENT ON COLUMN biz_project.name IS '项目名称（同组织内未删除记录唯一，创建后可改）';
COMMENT ON COLUMN biz_project.agency IS '代理机构';
COMMENT ON COLUMN biz_project.project_type IS '项目类型';
COMMENT ON COLUMN biz_project.leader_name IS '项目负责人';
COMMENT ON COLUMN biz_project.status IS '状态：ACTIVE 正常 / DISABLED 停用 / DELETED 已删除';
COMMENT ON COLUMN biz_project.created_by IS '创建人用户 ID，关联 sys_user.id';
COMMENT ON COLUMN biz_project.created_at IS '创建时间';
COMMENT ON COLUMN biz_project.updated_at IS '更新时间';
