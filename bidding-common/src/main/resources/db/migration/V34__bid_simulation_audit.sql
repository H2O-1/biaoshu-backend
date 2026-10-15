-- 投标模拟审核：独立上传一个招标包和一个投标包，模拟资格/评分响应并识别投标文件问题
-- job 类型约定：BID_SIMULATION_AUDIT（biz_job.type 为 VARCHAR，无需枚举表迁移）

CREATE TABLE IF NOT EXISTS biz_bid_simulation_audit (
    id                              BIGSERIAL PRIMARY KEY,
    org_id                          BIGINT       NOT NULL,
    name                            VARCHAR(256) NOT NULL,
    status                          VARCHAR(32)  NOT NULL DEFAULT 'BUILDING',
    job_id                          BIGINT,
    tender_package_id               BIGINT       NOT NULL,
    bid_package_id                  BIGINT       NOT NULL,
    tender_document_ids_json        TEXT         NOT NULL,
    bid_document_ids_json           TEXT         NOT NULL,
    qualification_total             INT          NOT NULL DEFAULT 0,
    qualification_pass_count        INT          NOT NULL DEFAULT 0,
    qualification_fail_count        INT          NOT NULL DEFAULT 0,
    qualification_manual_count      INT          NOT NULL DEFAULT 0,
    qualification_pass_rate         NUMERIC(5, 2) NOT NULL DEFAULT 0,
    score_suggested                 NUMERIC(12, 2) NOT NULL DEFAULT 0,
    score_total                     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    logic_issue_count               INT          NOT NULL DEFAULT 0,
    typo_count                      INT          NOT NULL DEFAULT 0,
    error_message                   TEXT,
    created_by                      BIGINT       NOT NULL,
    created_at                      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_org_status_created
    ON biz_bid_simulation_audit (org_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_org_created
    ON biz_bid_simulation_audit (org_id, created_at);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_job
    ON biz_bid_simulation_audit (job_id);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_tender_package
    ON biz_bid_simulation_audit (tender_package_id);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_bid_package
    ON biz_bid_simulation_audit (bid_package_id);

COMMENT ON TABLE biz_bid_simulation_audit IS '投标模拟审核主表：独立上传一个招标包和一个投标包后进行模拟响应审查';
COMMENT ON COLUMN biz_bid_simulation_audit.id IS '主键，即 auditId';
COMMENT ON COLUMN biz_bid_simulation_audit.org_id IS '所属组织 ID，按登录用户组织隔离';
COMMENT ON COLUMN biz_bid_simulation_audit.name IS '模拟审核任务名称；不传时默认取第一个投标文件的文件名';
COMMENT ON COLUMN biz_bid_simulation_audit.status IS '审核状态：BUILDING 审核中 / SUCCESS 成功 / FAILED 失败 / OBSOLETE 已作废';
COMMENT ON COLUMN biz_bid_simulation_audit.job_id IS '对应 BID_SIMULATION_AUDIT 异步任务 ID，关联 biz_job.id（应用层校验）';
COMMENT ON COLUMN biz_bid_simulation_audit.tender_package_id IS '本次任务使用的内部招标包 ID，关联 biz_package.id（应用层校验）';
COMMENT ON COLUMN biz_bid_simulation_audit.bid_package_id IS '本次任务使用的内部投标包 ID，关联 biz_package.id（应用层校验）';
COMMENT ON COLUMN biz_bid_simulation_audit.tender_document_ids_json IS '参与解析的招标文档 ID JSON 数组';
COMMENT ON COLUMN biz_bid_simulation_audit.bid_document_ids_json IS '参与审核的投标文档 ID JSON 数组';
COMMENT ON COLUMN biz_bid_simulation_audit.qualification_total IS '资格要求总数';
COMMENT ON COLUMN biz_bid_simulation_audit.qualification_pass_count IS '资格要求符合数量';
COMMENT ON COLUMN biz_bid_simulation_audit.qualification_fail_count IS '资格要求不符合数量';
COMMENT ON COLUMN biz_bid_simulation_audit.qualification_manual_count IS '资格要求待人工确认数量';
COMMENT ON COLUMN biz_bid_simulation_audit.qualification_pass_rate IS '资格要求通过率百分比，例如 70.00 表示 70.00%';
COMMENT ON COLUMN biz_bid_simulation_audit.score_suggested IS '评分项建议总分';
COMMENT ON COLUMN biz_bid_simulation_audit.score_total IS '评分项满分总分';
COMMENT ON COLUMN biz_bid_simulation_audit.logic_issue_count IS '投标文件逻辑问题数量';
COMMENT ON COLUMN biz_bid_simulation_audit.typo_count IS '投标文件错别字数量';
COMMENT ON COLUMN biz_bid_simulation_audit.error_message IS '模拟审核失败原因';
COMMENT ON COLUMN biz_bid_simulation_audit.created_by IS '创建人用户 ID';
COMMENT ON COLUMN biz_bid_simulation_audit.created_at IS '创建时间';
COMMENT ON COLUMN biz_bid_simulation_audit.updated_at IS '更新时间';

CREATE TABLE IF NOT EXISTS biz_bid_simulation_audit_item (
    id                      BIGSERIAL PRIMARY KEY,
    audit_id                BIGINT       NOT NULL,
    item_type               VARCHAR(32)  NOT NULL,
    requirement_code        VARCHAR(32)  NOT NULL,
    title                   VARCHAR(256),
    content                 TEXT         NOT NULL,
    result                  VARCHAR(32),
    reason                  TEXT,
    suggested_score         NUMERIC(12, 2),
    full_score              NUMERIC(12, 2),
    sub_type                VARCHAR(64),
    source_excerpt          TEXT,
    page_no                 INT,
    chunk_ids_json          TEXT,
    evidence_json           TEXT,
    raw_json                TEXT,
    sort_order              INT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_bid_simulation_audit_item_audit
        FOREIGN KEY (audit_id) REFERENCES biz_bid_simulation_audit (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_item_audit
    ON biz_bid_simulation_audit_item (audit_id, sort_order, id);

CREATE INDEX IF NOT EXISTS idx_biz_bid_simulation_audit_item_audit_type
    ON biz_bid_simulation_audit_item (audit_id, item_type, sort_order, id);

COMMENT ON TABLE biz_bid_simulation_audit_item IS '投标模拟审核条目表：资格响应、评分响应、逻辑问题和错别字识别结果';
COMMENT ON COLUMN biz_bid_simulation_audit_item.id IS '主键';
COMMENT ON COLUMN biz_bid_simulation_audit_item.audit_id IS '所属投标模拟审核 ID，关联 biz_bid_simulation_audit.id';
COMMENT ON COLUMN biz_bid_simulation_audit_item.item_type IS '条目类型：QUALIFICATION 资格要求响应 / SCORING 评分项响应 / LOGIC_ISSUE 逻辑问题 / TYPO 错别字识别';
COMMENT ON COLUMN biz_bid_simulation_audit_item.requirement_code IS '展示编号，如 Q1 / S1 / 逻辑问题-1 / 错别字-1';
COMMENT ON COLUMN biz_bid_simulation_audit_item.title IS '条目标题';
COMMENT ON COLUMN biz_bid_simulation_audit_item.content IS '条目正文；资格/评分为要求内容，逻辑问题/错别字为问题说明';
COMMENT ON COLUMN biz_bid_simulation_audit_item.result IS '审核结果：PASS 符合 / FAIL 不符合 / MANUAL 待人工确认；逻辑问题和错别字可为空';
COMMENT ON COLUMN biz_bid_simulation_audit_item.reason IS '判断理由或得分理由';
COMMENT ON COLUMN biz_bid_simulation_audit_item.suggested_score IS '建议得分，评分项使用';
COMMENT ON COLUMN biz_bid_simulation_audit_item.full_score IS '满分，评分项使用';
COMMENT ON COLUMN biz_bid_simulation_audit_item.sub_type IS '子类型 code，如 CERTIFICATE / TECH_SCORE / TERM_INCONSISTENCY';
COMMENT ON COLUMN biz_bid_simulation_audit_item.source_excerpt IS '投标文件或招标文件原文摘录';
COMMENT ON COLUMN biz_bid_simulation_audit_item.page_no IS '来源页码';
COMMENT ON COLUMN biz_bid_simulation_audit_item.chunk_ids_json IS '来源 chunk ID JSON 数组';
COMMENT ON COLUMN biz_bid_simulation_audit_item.evidence_json IS '原文证据 JSON 数组，用于前端查看原文片段';
COMMENT ON COLUMN biz_bid_simulation_audit_item.raw_json IS '单条原始结果 JSON，用于保留 LLM 或审查 pipeline 原始输出';
COMMENT ON COLUMN biz_bid_simulation_audit_item.sort_order IS '展示排序，从 0 起';
COMMENT ON COLUMN biz_bid_simulation_audit_item.created_at IS '创建时间';
