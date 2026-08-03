-- 全局审查维度字典（平台一份；仅平台管理员维护）
-- 一维多条检索 query，供 BUILD_CHECKLIST 召回使用。

CREATE TABLE IF NOT EXISTS biz_review_dimension (
    code              VARCHAR(64)  PRIMARY KEY,
    name              VARCHAR(128) NOT NULL,
    default_selected  BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS biz_review_dimension_query (
    id               BIGSERIAL PRIMARY KEY,
    dimension_code   VARCHAR(64)  NOT NULL,
    query_text       VARCHAR(512) NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_review_dim_query_dim
        FOREIGN KEY (dimension_code) REFERENCES biz_review_dimension (code)
);

CREATE INDEX IF NOT EXISTS idx_biz_review_dimension_query_code
    ON biz_review_dimension_query (dimension_code, sort_order);

COMMENT ON TABLE biz_review_dimension IS '全局审查维度字典（平台级）';
COMMENT ON COLUMN biz_review_dimension.code IS '维度编码，如 QUALIFICATION';
COMMENT ON COLUMN biz_review_dimension.name IS '显示名';
COMMENT ON COLUMN biz_review_dimension.default_selected IS '建清单未传维度时是否默认勾选';
COMMENT ON COLUMN biz_review_dimension.enabled IS '是否启用；停用后前端不可选';
COMMENT ON COLUMN biz_review_dimension.sort_order IS '展示排序，越小越靠前';

COMMENT ON TABLE biz_review_dimension_query IS '维度检索 query（一维多条）';
COMMENT ON COLUMN biz_review_dimension_query.dimension_code IS '所属维度 code';
COMMENT ON COLUMN biz_review_dimension_query.query_text IS '向量召回用检索词';
COMMENT ON COLUMN biz_review_dimension_query.sort_order IS '同维内顺序';

-- 种子：方案初值 4 维
INSERT INTO biz_review_dimension (code, name, default_selected, enabled, sort_order)
VALUES
    ('QUALIFICATION', '资格与资质', TRUE, TRUE, 10),
    ('REJECT', '废标与否决', TRUE, TRUE, 20),
    ('SCHEDULE', '工期与交付', TRUE, TRUE, 30),
    ('COMMERCIAL', '商务条款', TRUE, TRUE, 40)
ON CONFLICT (code) DO NOTHING;

INSERT INTO biz_review_dimension_query (dimension_code, query_text, sort_order)
SELECT v.dimension_code, v.query_text, v.sort_order
FROM (VALUES
    ('QUALIFICATION', '投标人资格条件与资质等级要求', 10),
    ('QUALIFICATION', '类似项目业绩与项目负责人资格', 20),
    ('REJECT', '废标条件 否决情形 无效投标', 10),
    ('REJECT', '实质性响应要求与否决条款', 20),
    ('SCHEDULE', '工期 交货期 里程碑 进度要求', 10),
    ('COMMERCIAL', '投标保证金 履约担保 付款方式 收款条件', 10)
) AS v(dimension_code, query_text, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM biz_review_dimension_query q
    WHERE q.dimension_code = v.dimension_code AND q.query_text = v.query_text
);
