-- 建清单条目扩展：产品向资格/评分结构（title、主类/子类、bidSearch、证据、分值）

ALTER TABLE biz_requirement_item
    ADD COLUMN IF NOT EXISTS title VARCHAR(64),
    ADD COLUMN IF NOT EXISTS main_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS sub_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS bid_search_json TEXT,
    ADD COLUMN IF NOT EXISTS evidence_json TEXT,
    ADD COLUMN IF NOT EXISTS score_total NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS score_parts_json TEXT,
    ADD COLUMN IF NOT EXISTS score_sum_matches BOOLEAN;

COMMENT ON COLUMN biz_requirement_item.title IS '条目标题（展示用）';
COMMENT ON COLUMN biz_requirement_item.main_type IS '主类 code：QUALIFICATION / SCORING';
COMMENT ON COLUMN biz_requirement_item.sub_type IS '子类 code，如 CERTIFICATE / TECH_SCORE';
COMMENT ON COLUMN biz_requirement_item.bid_search_json IS '投标侧检索词 JSON 数组';
COMMENT ON COLUMN biz_requirement_item.evidence_json IS '招标侧证据 JSON 数组 [{chunkId,page,quote}]';
COMMENT ON COLUMN biz_requirement_item.score_total IS '评分项满分；资格项为空';
COMMENT ON COLUMN biz_requirement_item.score_parts_json IS '评分分项 JSON 数组 [{name,score}]';
COMMENT ON COLUMN biz_requirement_item.score_sum_matches IS '分项加总是否等于满分；不清则为 null';
COMMENT ON COLUMN biz_requirement_item.dimension_code IS '兼容字段：新流程写入 main_type；旧数据仍为审查维度 code';
COMMENT ON COLUMN biz_requirement_item.text IS '要求正文（对应产品 content）';
