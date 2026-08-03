-- REVIEW：投标对照审查主表与条目

CREATE TABLE IF NOT EXISTS biz_review (
    id                    BIGSERIAL PRIMARY KEY,
    org_id                BIGINT       NOT NULL,
    project_id            BIGINT       NOT NULL,
    checklist_id          BIGINT       NOT NULL,
    version               INT          NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    package_ids           TEXT         NOT NULL,
    overall_verdict       VARCHAR(16),
    need_human_review     BOOLEAN      NOT NULL DEFAULT TRUE,
    prompt_version        VARCHAR(64)  NOT NULL,
    recall_top_k          INT          NOT NULL,
    rerank_top_n          INT          NOT NULL,
    error_message         TEXT,
    created_by            BIGINT       NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_review_project_version UNIQUE (project_id, version),
    CONSTRAINT fk_biz_review_checklist
        FOREIGN KEY (checklist_id) REFERENCES biz_requirement_checklist (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_review_org_project
    ON biz_review (org_id, project_id);

CREATE TABLE IF NOT EXISTS biz_review_item (
    id                      BIGSERIAL PRIMARY KEY,
    review_id               BIGINT       NOT NULL,
    requirement_item_id     BIGINT       NOT NULL,
    dimension_code          VARCHAR(64)  NOT NULL,
    requirement_text        TEXT         NOT NULL,
    must_flag               BOOLEAN      NOT NULL DEFAULT TRUE,
    verdict                 VARCHAR(16)  NOT NULL,
    reason                  TEXT,
    bid_excerpt             TEXT,
    page_no                 INT,
    chunk_id                BIGINT,
    document_id             BIGINT,
    file_name               VARCHAR(512),
    package_id              BIGINT,
    sort_order              INT          NOT NULL DEFAULT 0,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_review_item_review
        FOREIGN KEY (review_id) REFERENCES biz_review (id),
    CONSTRAINT fk_biz_review_item_requirement
        FOREIGN KEY (requirement_item_id) REFERENCES biz_requirement_item (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_review_item_review
    ON biz_review_item (review_id, sort_order, id);

COMMENT ON TABLE biz_review IS '审查主表：按项目多版本；基于某版清单对投标包对照';
COMMENT ON COLUMN biz_review.id IS '主键，即 reviewId';
COMMENT ON COLUMN biz_review.org_id IS '所属组织 ID';
COMMENT ON COLUMN biz_review.project_id IS '所属项目 ID';
COMMENT ON COLUMN biz_review.checklist_id IS '基于的清单版本，→ biz_requirement_checklist.id';
COMMENT ON COLUMN biz_review.version IS '项目内审查版本号，从 1 递增';
COMMENT ON COLUMN biz_review.status IS 'BUILDING / SUCCESS / FAILED';
COMMENT ON COLUMN biz_review.package_ids IS '参与对照的投标包 id JSON 数组';
COMMENT ON COLUMN biz_review.overall_verdict IS '总判定 PASS / PARTIAL / FAIL；未完成时为空';
COMMENT ON COLUMN biz_review.need_human_review IS '是否建议人工复核，默认 true';
COMMENT ON COLUMN biz_review.prompt_version IS '对照提示词③版本号快照';
COMMENT ON COLUMN biz_review.recall_top_k IS '投标侧召回条数快照';
COMMENT ON COLUMN biz_review.rerank_top_n IS '投标侧重排保留段数快照';
COMMENT ON COLUMN biz_review.error_message IS '失败原因';
COMMENT ON COLUMN biz_review.created_by IS '发起人用户 ID';
COMMENT ON COLUMN biz_review.created_at IS '创建时间';
COMMENT ON COLUMN biz_review.updated_at IS '更新时间';

COMMENT ON TABLE biz_review_item IS '审查条目：清单每条要求的对照结果与投标证据';
COMMENT ON COLUMN biz_review_item.id IS '主键';
COMMENT ON COLUMN biz_review_item.review_id IS '所属审查，→ biz_review.id';
COMMENT ON COLUMN biz_review_item.requirement_item_id IS '对应清单条目，→ biz_requirement_item.id';
COMMENT ON COLUMN biz_review_item.dimension_code IS '维度 code 快照';
COMMENT ON COLUMN biz_review_item.requirement_text IS '招标要求正文快照';
COMMENT ON COLUMN biz_review_item.must_flag IS '是否必须响应（来自清单）';
COMMENT ON COLUMN biz_review_item.verdict IS 'PASS / PARTIAL / FAIL';
COMMENT ON COLUMN biz_review_item.reason IS '判定理由';
COMMENT ON COLUMN biz_review_item.bid_excerpt IS '投标原文摘录';
COMMENT ON COLUMN biz_review_item.page_no IS '投标摘录页码';
COMMENT ON COLUMN biz_review_item.chunk_id IS '证据切块 id';
COMMENT ON COLUMN biz_review_item.document_id IS '证据所属文档 id';
COMMENT ON COLUMN biz_review_item.file_name IS '证据文件名';
COMMENT ON COLUMN biz_review_item.package_id IS '证据所属投标包 id';
COMMENT ON COLUMN biz_review_item.sort_order IS '展示顺序';
COMMENT ON COLUMN biz_review_item.created_at IS '写入时间';
