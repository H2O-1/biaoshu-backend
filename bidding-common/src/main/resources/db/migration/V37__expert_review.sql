-- 专家评审：项目级专家评审主表、成员、公司状态、评审结果与附件

CREATE TABLE IF NOT EXISTS biz_expert_review (
    id                            BIGSERIAL PRIMARY KEY,
    org_id                        BIGINT        NOT NULL,
    project_id                    BIGINT        NOT NULL,
    version                       INT           NOT NULL DEFAULT 1,
    status                        VARCHAR(32)   NOT NULL DEFAULT 'BUILDING',
    deadline_at                   TIMESTAMP,
    push_message                  TEXT,
    expert_count                  INT           NOT NULL DEFAULT 0,
    submitted_expert_count        INT           NOT NULL DEFAULT 0,
    submitted_company_count       INT           NOT NULL DEFAULT 0,
    has_any_complete_submission   BOOLEAN       NOT NULL DEFAULT FALSE,
    is_locked                     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by                    BIGINT        NOT NULL,
    created_at                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_expert_review_project_version UNIQUE (project_id, version)
);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_org_project
    ON biz_expert_review (org_id, project_id);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_org_status_created
    ON biz_expert_review (org_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_project_status
    ON biz_expert_review (project_id, status);

COMMENT ON TABLE biz_expert_review IS '专家评审主表：项目进入专家评审后的主记录';
COMMENT ON COLUMN biz_expert_review.id IS '主键，即 reviewId';
COMMENT ON COLUMN biz_expert_review.org_id IS '所属组织 ID，按登录用户组织隔离';
COMMENT ON COLUMN biz_expert_review.project_id IS '所属项目 ID';
COMMENT ON COLUMN biz_expert_review.version IS '项目内专家评审版本号，从 1 递增';
COMMENT ON COLUMN biz_expert_review.status IS '评审状态：BUILDING / RUNNING / SUCCESS / FAILED / OBSOLETE';
COMMENT ON COLUMN biz_expert_review.deadline_at IS '评审截止时间';
COMMENT ON COLUMN biz_expert_review.push_message IS '推送留言';
COMMENT ON COLUMN biz_expert_review.expert_count IS '专家总数';
COMMENT ON COLUMN biz_expert_review.submitted_expert_count IS '已提交专家数';
COMMENT ON COLUMN biz_expert_review.submitted_company_count IS '已完成评审的投标单位数';
COMMENT ON COLUMN biz_expert_review.has_any_complete_submission IS '是否已有任一专家完成全部投标单位评审';
COMMENT ON COLUMN biz_expert_review.is_locked IS '项目是否已锁定，不允许再推送 / 追加 / 改文件';
COMMENT ON COLUMN biz_expert_review.created_by IS '创建人用户 ID';
COMMENT ON COLUMN biz_expert_review.created_at IS '创建时间';
COMMENT ON COLUMN biz_expert_review.updated_at IS '更新时间';

CREATE TABLE IF NOT EXISTS biz_expert_review_member (
    id                    BIGSERIAL PRIMARY KEY,
    review_id             BIGINT        NOT NULL,
    project_id            BIGINT        NOT NULL,
    user_id               BIGINT        NOT NULL,
    username              VARCHAR(64)   NOT NULL,
    display_name          VARCHAR(128)  NOT NULL,
    password_hash         VARCHAR(255)  NOT NULL,
    status                VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    submit_status         VARCHAR(32)   NOT NULL DEFAULT 'NOT_SUBMITTED',
    submitted_at          TIMESTAMP,
    first_password_shown   BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_expert_review_member_review
        FOREIGN KEY (review_id) REFERENCES biz_expert_review (id),
    CONSTRAINT uk_biz_expert_review_member_review_user UNIQUE (review_id, user_id),
    CONSTRAINT uk_biz_expert_review_member_review_username UNIQUE (review_id, username)
);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_member_review
    ON biz_expert_review_member (review_id, status, submit_status, id);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_member_project
    ON biz_expert_review_member (project_id, status, submit_status);

COMMENT ON TABLE biz_expert_review_member IS '专家评审成员表：项目下的临时专家账号';
COMMENT ON COLUMN biz_expert_review_member.id IS '主键';
COMMENT ON COLUMN biz_expert_review_member.review_id IS '所属专家评审 ID，关联 biz_expert_review.id';
COMMENT ON COLUMN biz_expert_review_member.project_id IS '所属项目 ID';
COMMENT ON COLUMN biz_expert_review_member.user_id IS 'identity 用户 ID';
COMMENT ON COLUMN biz_expert_review_member.username IS '登录账号';
COMMENT ON COLUMN biz_expert_review_member.display_name IS '专家姓名';
COMMENT ON COLUMN biz_expert_review_member.password_hash IS '密码哈希';
COMMENT ON COLUMN biz_expert_review_member.status IS '专家状态：ACTIVE / READ_ONLY / DISABLED / DELETED';
COMMENT ON COLUMN biz_expert_review_member.submit_status IS '提交状态：NOT_SUBMITTED / SUBMITTED / OVERDUE_SUBMITTED';
COMMENT ON COLUMN biz_expert_review_member.submitted_at IS '提交时间';
COMMENT ON COLUMN biz_expert_review_member.first_password_shown IS '是否已展示过明文密码';
COMMENT ON COLUMN biz_expert_review_member.created_at IS '创建时间';
COMMENT ON COLUMN biz_expert_review_member.updated_at IS '更新时间';

CREATE TABLE IF NOT EXISTS biz_expert_review_company (
    id                      BIGSERIAL PRIMARY KEY,
    review_id               BIGINT        NOT NULL,
    project_id              BIGINT        NOT NULL,
    bid_package_id          BIGINT        NOT NULL,
    supplier_name           VARCHAR(256)  NOT NULL,
    company_status          VARCHAR(32)   NOT NULL DEFAULT 'NORMAL',
    display_mode            VARCHAR(32)   NOT NULL DEFAULT 'NORMAL',
    veto_reason_summary     TEXT,
    veto_expert_names       TEXT,
    veto_item_count         INT           NOT NULL DEFAULT 0,
    average_score           NUMERIC(12, 2),
    confirmed_by            BIGINT,
    confirmed_at            TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_expert_review_company_review
        FOREIGN KEY (review_id) REFERENCES biz_expert_review (id),
    CONSTRAINT uk_biz_expert_review_company_review_package UNIQUE (review_id, bid_package_id)
);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_company_review
    ON biz_expert_review_company (review_id, company_status, display_mode, id);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_company_project
    ON biz_expert_review_company (project_id, bid_package_id);

COMMENT ON TABLE biz_expert_review_company IS '专家评审公司表：按项目 + 投标包维度管理废标和展示状态';
COMMENT ON COLUMN biz_expert_review_company.id IS '主键';
COMMENT ON COLUMN biz_expert_review_company.review_id IS '所属专家评审 ID，关联 biz_expert_review.id';
COMMENT ON COLUMN biz_expert_review_company.project_id IS '所属项目 ID';
COMMENT ON COLUMN biz_expert_review_company.bid_package_id IS '投标包 ID';
COMMENT ON COLUMN biz_expert_review_company.supplier_name IS '投标单位名称';
COMMENT ON COLUMN biz_expert_review_company.company_status IS '公司状态：NORMAL / PENDING_VETO / VETOED';
COMMENT ON COLUMN biz_expert_review_company.display_mode IS '展示模式：NORMAL / PENDING_CONFIRMATION / CONTINUE_DISPLAY';
COMMENT ON COLUMN biz_expert_review_company.veto_reason_summary IS '废标原因摘要';
COMMENT ON COLUMN biz_expert_review_company.veto_expert_names IS '废标专家姓名列表';
COMMENT ON COLUMN biz_expert_review_company.veto_item_count IS '当前公司废标建议数';
COMMENT ON COLUMN biz_expert_review_company.average_score IS '当前公司平均得分';
COMMENT ON COLUMN biz_expert_review_company.confirmed_by IS '管理员确认人';
COMMENT ON COLUMN biz_expert_review_company.confirmed_at IS '确认时间';
COMMENT ON COLUMN biz_expert_review_company.updated_at IS '更新时间';
COMMENT ON COLUMN biz_expert_review_company.created_at IS '创建时间';

CREATE TABLE IF NOT EXISTS biz_expert_review_item (
    id                    BIGSERIAL PRIMARY KEY,
    review_id             BIGINT        NOT NULL,
    member_id             BIGINT        NOT NULL,
    project_id            BIGINT        NOT NULL,
    bid_package_id        BIGINT        NOT NULL,
    requirement_item_id   BIGINT        NOT NULL,
    item_type             VARCHAR(32)   NOT NULL,
    requirement_code      VARCHAR(32)   NOT NULL,
    title                 VARCHAR(256),
    content               TEXT          NOT NULL,
    result                VARCHAR(32),
    score_value           NUMERIC(12, 2),
    full_score            NUMERIC(12, 2),
    comment               TEXT,
    is_veto               BOOLEAN       NOT NULL DEFAULT FALSE,
    veto_reason           TEXT,
    evidence_json         TEXT,
    sort_order            INT           NOT NULL DEFAULT 0,
    submitted_at          TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_expert_review_item_review
        FOREIGN KEY (review_id) REFERENCES biz_expert_review (id),
    CONSTRAINT fk_biz_expert_review_item_member
        FOREIGN KEY (member_id) REFERENCES biz_expert_review_member (id),
    CONSTRAINT fk_biz_expert_review_item_requirement
        FOREIGN KEY (requirement_item_id) REFERENCES biz_requirement_item (id),
    CONSTRAINT uk_biz_expert_review_item_unique UNIQUE (member_id, bid_package_id, requirement_item_id)
);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_item_review_member
    ON biz_expert_review_item (review_id, member_id, bid_package_id, sort_order, id);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_item_project_package
    ON biz_expert_review_item (project_id, bid_package_id, requirement_item_id);

COMMENT ON TABLE biz_expert_review_item IS '专家评审结果表：按专家、投标包、条目保存资格和评分结果';
COMMENT ON COLUMN biz_expert_review_item.id IS '主键';
COMMENT ON COLUMN biz_expert_review_item.review_id IS '所属专家评审 ID，关联 biz_expert_review.id';
COMMENT ON COLUMN biz_expert_review_item.member_id IS '专家成员 ID，关联 biz_expert_review_member.id';
COMMENT ON COLUMN biz_expert_review_item.project_id IS '所属项目 ID';
COMMENT ON COLUMN biz_expert_review_item.bid_package_id IS '投标包 ID';
COMMENT ON COLUMN biz_expert_review_item.requirement_item_id IS '对应 AI 审查条目 ID，关联 biz_requirement_item.id';
COMMENT ON COLUMN biz_expert_review_item.item_type IS '条目类型：QUALIFICATION / SCORING';
COMMENT ON COLUMN biz_expert_review_item.requirement_code IS '条目标识';
COMMENT ON COLUMN biz_expert_review_item.title IS '条目标题';
COMMENT ON COLUMN biz_expert_review_item.content IS '条目正文';
COMMENT ON COLUMN biz_expert_review_item.result IS '评审结果：PASS / FAIL / MANUAL / SCORE 等';
COMMENT ON COLUMN biz_expert_review_item.score_value IS '评分值';
COMMENT ON COLUMN biz_expert_review_item.full_score IS '满分';
COMMENT ON COLUMN biz_expert_review_item.comment IS '评审建议';
COMMENT ON COLUMN biz_expert_review_item.is_veto IS '是否废标建议';
COMMENT ON COLUMN biz_expert_review_item.veto_reason IS '废标原因';
COMMENT ON COLUMN biz_expert_review_item.evidence_json IS '证据 JSON 数组，用于前端查看原文片段';
COMMENT ON COLUMN biz_expert_review_item.sort_order IS '排序';
COMMENT ON COLUMN biz_expert_review_item.submitted_at IS '提交时间';
COMMENT ON COLUMN biz_expert_review_item.updated_at IS '更新时间';
COMMENT ON COLUMN biz_expert_review_item.created_at IS '创建时间';

CREATE TABLE IF NOT EXISTS biz_expert_review_attachment (
    id              BIGSERIAL PRIMARY KEY,
    item_id         BIGINT        NOT NULL,
    object_key      VARCHAR(512)  NOT NULL,
    file_name       VARCHAR(512)  NOT NULL,
    content_type    VARCHAR(128)  NOT NULL,
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_expert_review_attachment_item
        FOREIGN KEY (item_id) REFERENCES biz_expert_review_item (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_attachment_item
    ON biz_expert_review_attachment (item_id, sort_order, id);

COMMENT ON TABLE biz_expert_review_attachment IS '专家评审附件表：保存图片附件元数据';
COMMENT ON COLUMN biz_expert_review_attachment.id IS '主键';
COMMENT ON COLUMN biz_expert_review_attachment.item_id IS '所属评审结果 ID，关联 biz_expert_review_item.id';
COMMENT ON COLUMN biz_expert_review_attachment.object_key IS '对象存储 key';
COMMENT ON COLUMN biz_expert_review_attachment.file_name IS '文件名';
COMMENT ON COLUMN biz_expert_review_attachment.content_type IS 'MIME 类型';
COMMENT ON COLUMN biz_expert_review_attachment.sort_order IS '排序';
COMMENT ON COLUMN biz_expert_review_attachment.created_at IS '创建时间';
