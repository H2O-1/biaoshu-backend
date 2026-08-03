-- BUILD_CHECKLIST：招标侧要求清单（项目多版本）

CREATE TABLE IF NOT EXISTS biz_requirement_checklist (
    id                    BIGSERIAL PRIMARY KEY,
    org_id                BIGINT       NOT NULL,
    project_id            BIGINT       NOT NULL,
    version               INT          NOT NULL,
    status                VARCHAR(32)  NOT NULL,
    dimension_codes       TEXT         NOT NULL,
    tender_document_ids   TEXT         NOT NULL,
    prompt_version        VARCHAR(64)  NOT NULL,
    recall_top_k          INT          NOT NULL,
    rerank_top_n          INT          NOT NULL,
    error_message         TEXT,
    created_by            BIGINT       NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_requirement_checklist_project_version UNIQUE (project_id, version)
);

CREATE INDEX IF NOT EXISTS idx_biz_requirement_checklist_org_project
    ON biz_requirement_checklist (org_id, project_id);

CREATE TABLE IF NOT EXISTS biz_requirement_item (
    id                BIGSERIAL PRIMARY KEY,
    checklist_id      BIGINT       NOT NULL,
    dimension_code    VARCHAR(64)  NOT NULL,
    text              TEXT         NOT NULL,
    source_excerpt    TEXT,
    page_no           INT,
    chunk_ids         TEXT,
    must_flag         BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_biz_requirement_item_checklist
        FOREIGN KEY (checklist_id) REFERENCES biz_requirement_checklist (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_requirement_item_checklist
    ON biz_requirement_item (checklist_id, sort_order, id);

COMMENT ON TABLE biz_requirement_checklist IS '招标要求清单主表（项目多版本；BUILDING/SUCCESS/FAILED）';
COMMENT ON COLUMN biz_requirement_checklist.dimension_codes IS '本次勾选维度 code JSON 数组快照';
COMMENT ON COLUMN biz_requirement_checklist.tender_document_ids IS '参与建清单的招标文档 id JSON 数组';
COMMENT ON COLUMN biz_requirement_checklist.prompt_version IS '抽取提示词版本号';
COMMENT ON TABLE biz_requirement_item IS '清单条目（由 LLM 抽取后落库）';
COMMENT ON COLUMN biz_requirement_item.chunk_ids IS '溯源 chunk id JSON 数组';
COMMENT ON COLUMN biz_requirement_item.must_flag IS '是否必须响应';
