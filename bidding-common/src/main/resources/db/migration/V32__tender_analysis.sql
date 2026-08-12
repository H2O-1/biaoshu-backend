-- 招标解析：独立上传招标文件并抽取基础信息、资格要求、评分标准
-- job 类型约定：TENDER_ANALYSIS（biz_job.type 为 VARCHAR，无需枚举表迁移）

CREATE TABLE IF NOT EXISTS biz_tender_analysis (
    id                      BIGSERIAL PRIMARY KEY,
    org_id                  BIGINT       NOT NULL,
    package_id              BIGINT       NOT NULL,
    name                    VARCHAR(256) NOT NULL,
    status                  VARCHAR(32)  NOT NULL DEFAULT 'BUILDING',
    job_id                  BIGINT,
    document_ids_json       TEXT         NOT NULL,
    tenderer                VARCHAR(256),
    agency                  VARCHAR(256),
    budget_amount_text      VARCHAR(128),
    bid_deadline_text       VARCHAR(128),
    bid_deadline_at         TIMESTAMP,
    project_intro           TEXT,
    project_background      TEXT,
    project_overview        TEXT,
    evidence_json           TEXT,
    basic_info_raw_json     TEXT,
    checklist_raw_json      TEXT,
    error_message           TEXT,
    created_by              BIGINT       NOT NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_biz_tender_analysis_org_status_created
    ON biz_tender_analysis (org_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_biz_tender_analysis_org_created
    ON biz_tender_analysis (org_id, created_at);

CREATE INDEX IF NOT EXISTS idx_biz_tender_analysis_package
    ON biz_tender_analysis (package_id);

CREATE INDEX IF NOT EXISTS idx_biz_tender_analysis_job
    ON biz_tender_analysis (job_id);

COMMENT ON TABLE biz_tender_analysis IS '招标解析主表：独立上传招标文件后抽取基础信息、资格要求、评分标准';
COMMENT ON COLUMN biz_tender_analysis.id IS '主键，即 analysisId';
COMMENT ON COLUMN biz_tender_analysis.org_id IS '所属组织 ID，按登录用户组织隔离';
COMMENT ON COLUMN biz_tender_analysis.package_id IS '本次解析使用的内部招标包 ID，关联 biz_package.id（应用层校验）';
COMMENT ON COLUMN biz_tender_analysis.name IS '解析任务名称；不传时默认取第一个上传文件的文件名';
COMMENT ON COLUMN biz_tender_analysis.status IS '解析状态：BUILDING 解析中 / SUCCESS 成功 / FAILED 失败 / OBSOLETE 已作废；后续可扩展 DELETED';
COMMENT ON COLUMN biz_tender_analysis.job_id IS '对应 TENDER_ANALYSIS 异步任务 ID，关联 biz_job.id（应用层校验）';
COMMENT ON COLUMN biz_tender_analysis.document_ids_json IS '参与解析的招标文档 ID JSON 数组';
COMMENT ON COLUMN biz_tender_analysis.tenderer IS '招标人';
COMMENT ON COLUMN biz_tender_analysis.agency IS '招标代理';
COMMENT ON COLUMN biz_tender_analysis.budget_amount_text IS '预算金额原文，不拆分最高限价';
COMMENT ON COLUMN biz_tender_analysis.bid_deadline_text IS '投标截止时间原文';
COMMENT ON COLUMN biz_tender_analysis.bid_deadline_at IS '投标截止时间标准时间；无法可靠解析时为空';
COMMENT ON COLUMN biz_tender_analysis.project_intro IS '项目简介';
COMMENT ON COLUMN biz_tender_analysis.project_background IS '项目背景';
COMMENT ON COLUMN biz_tender_analysis.project_overview IS '项目概况';
COMMENT ON COLUMN biz_tender_analysis.evidence_json IS '基础信息证据 JSON，用于前端查看原文片段';
COMMENT ON COLUMN biz_tender_analysis.basic_info_raw_json IS '基础信息抽取 LLM 原始输出';
COMMENT ON COLUMN biz_tender_analysis.checklist_raw_json IS '资格要求与评分标准抽取 LLM 原始输出';
COMMENT ON COLUMN biz_tender_analysis.error_message IS '解析失败原因';
COMMENT ON COLUMN biz_tender_analysis.created_by IS '创建人用户 ID';
COMMENT ON COLUMN biz_tender_analysis.created_at IS '创建时间';
COMMENT ON COLUMN biz_tender_analysis.updated_at IS '更新时间';

CREATE TABLE IF NOT EXISTS biz_tender_analysis_item (
    id                      BIGSERIAL PRIMARY KEY,
    analysis_id             BIGINT       NOT NULL,
    item_type               VARCHAR(32)  NOT NULL,
    requirement_code        VARCHAR(32)  NOT NULL,
    title                   VARCHAR(128),
    content                 TEXT         NOT NULL,
    sub_type                VARCHAR(64),
    source_excerpt          TEXT,
    page_no                 INT,
    chunk_ids_json          TEXT,
    evidence_json           TEXT,
    score_total             NUMERIC(12, 2),
    score_parts_json        TEXT,
    score_sum_matches       BOOLEAN,
    sort_order              INT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_tender_analysis_item_analysis
        FOREIGN KEY (analysis_id) REFERENCES biz_tender_analysis (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_tender_analysis_item_analysis
    ON biz_tender_analysis_item (analysis_id, sort_order, id);

COMMENT ON TABLE biz_tender_analysis_item IS '招标解析条目表：资格要求与评分标准';
COMMENT ON COLUMN biz_tender_analysis_item.id IS '主键';
COMMENT ON COLUMN biz_tender_analysis_item.analysis_id IS '所属招标解析 ID，关联 biz_tender_analysis.id';
COMMENT ON COLUMN biz_tender_analysis_item.item_type IS '条目类型：QUALIFICATION 资格要求 / SCORING 评分标准';
COMMENT ON COLUMN biz_tender_analysis_item.requirement_code IS '展示编号，如 Q1 / S1';
COMMENT ON COLUMN biz_tender_analysis_item.title IS '条目标题';
COMMENT ON COLUMN biz_tender_analysis_item.content IS '条目正文';
COMMENT ON COLUMN biz_tender_analysis_item.sub_type IS '子类型 code，如 CERTIFICATE / TECH_SCORE';
COMMENT ON COLUMN biz_tender_analysis_item.source_excerpt IS '招标文件原文摘录';
COMMENT ON COLUMN biz_tender_analysis_item.page_no IS '来源页码';
COMMENT ON COLUMN biz_tender_analysis_item.chunk_ids_json IS '来源 chunk ID JSON 数组';
COMMENT ON COLUMN biz_tender_analysis_item.evidence_json IS '原文证据 JSON 数组，用于前端查看原文片段';
COMMENT ON COLUMN biz_tender_analysis_item.score_total IS '评分项满分；资格要求为空';
COMMENT ON COLUMN biz_tender_analysis_item.score_parts_json IS '评分分项 JSON 数组';
COMMENT ON COLUMN biz_tender_analysis_item.score_sum_matches IS '评分分项加总是否等于满分；不确定时为空';
COMMENT ON COLUMN biz_tender_analysis_item.sort_order IS '展示排序，从 0 起';
COMMENT ON COLUMN biz_tender_analysis_item.created_at IS '创建时间';
