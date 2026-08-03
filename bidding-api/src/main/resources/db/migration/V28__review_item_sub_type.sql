-- 审查条目快照子类，供详情页左栏小字标签展示
ALTER TABLE biz_review_item
    ADD COLUMN IF NOT EXISTS sub_type VARCHAR(64);

COMMENT ON COLUMN biz_review_item.sub_type IS '清单子类快照 code，如 CERTIFICATE / TECH_SCORE';
