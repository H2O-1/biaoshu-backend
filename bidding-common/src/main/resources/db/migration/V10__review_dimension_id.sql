-- 维度改用数字 id 主键；query 子表挂 dimension_id（保留 code 唯一业务键）

DROP TABLE IF EXISTS biz_review_dimension_query;
DROP TABLE IF EXISTS biz_review_dimension;

CREATE TABLE biz_review_dimension (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    default_selected  BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_review_dimension_code UNIQUE (code)
);

CREATE TABLE biz_review_dimension_query (
    id               BIGSERIAL PRIMARY KEY,
    dimension_id     BIGINT       NOT NULL,
    query_text       VARCHAR(512) NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_review_dim_query_dim
        FOREIGN KEY (dimension_id) REFERENCES biz_review_dimension (id)
);

CREATE INDEX IF NOT EXISTS idx_biz_review_dimension_query_dim
    ON biz_review_dimension_query (dimension_id, sort_order);

COMMENT ON TABLE biz_review_dimension IS '全局审查维度字典（平台级；id 主键，code 业务键）';
COMMENT ON COLUMN biz_review_dimension.id IS '主键';
COMMENT ON COLUMN biz_review_dimension.code IS '维度业务编码，唯一，如 QUALIFICATION';
COMMENT ON COLUMN biz_review_dimension.name IS '显示名';
COMMENT ON COLUMN biz_review_dimension_query.dimension_id IS '所属维度 id';

INSERT INTO biz_review_dimension (code, name, default_selected, enabled, sort_order)
VALUES
    ('QUALIFICATION', '资格与资质', TRUE, TRUE, 10),
    ('REJECT', '废标与否决', TRUE, TRUE, 20),
    ('SCHEDULE', '工期与交付', TRUE, TRUE, 30),
    ('COMMERCIAL', '商务条款', TRUE, TRUE, 40);

INSERT INTO biz_review_dimension_query (dimension_id, query_text, sort_order)
SELECT d.id, v.query_text, v.sort_order
FROM (VALUES
    ('QUALIFICATION', '投标人资格条件与资质等级要求', 10),
    ('QUALIFICATION', '类似项目业绩与项目负责人资格', 20),
    ('REJECT', '废标条件 否决情形 无效投标', 10),
    ('REJECT', '实质性响应要求与否决条款', 20),
    ('SCHEDULE', '工期 交货期 里程碑 进度要求', 10),
    ('COMMERCIAL', '投标保证金 履约担保 付款方式 收款条件', 10)
) AS v(code, query_text, sort_order)
INNER JOIN biz_review_dimension d ON d.code = v.code;
