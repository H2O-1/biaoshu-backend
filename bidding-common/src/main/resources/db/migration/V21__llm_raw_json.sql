-- 存 LLM 原始输出（message.content）

ALTER TABLE biz_requirement_checklist
    ADD COLUMN IF NOT EXISTS llm_raw_json TEXT;

ALTER TABLE biz_review_item
    ADD COLUMN IF NOT EXISTS llm_raw_json TEXT;

COMMENT ON COLUMN biz_requirement_checklist.llm_raw_json IS '建清单 LLM 原始输出（message.content）';
COMMENT ON COLUMN biz_review_item.llm_raw_json IS '该条对照 LLM 原始输出（message.content）；未调 LLM 时为空';
