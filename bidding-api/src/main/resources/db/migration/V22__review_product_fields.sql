-- 产品向审查：清单 requirementId（Q1/S1）+ 审查条目结构化字段

ALTER TABLE biz_requirement_item
    ADD COLUMN IF NOT EXISTS requirement_code VARCHAR(16);

COMMENT ON COLUMN biz_requirement_item.requirement_code IS '产品编号，如 Q1 / S1';

ALTER TABLE biz_review_item
    ADD COLUMN IF NOT EXISTS requirement_code VARCHAR(16),
    ADD COLUMN IF NOT EXISTS title VARCHAR(128),
    ADD COLUMN IF NOT EXISTS main_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS review_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS veto_risk BOOLEAN,
    ADD COLUMN IF NOT EXISTS judgement TEXT,
    ADD COLUMN IF NOT EXISTS bid_evidence_json TEXT,
    ADD COLUMN IF NOT EXISTS note TEXT,
    ADD COLUMN IF NOT EXISTS suggested_score NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS full_score NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS deduction_reason TEXT;

COMMENT ON COLUMN biz_review_item.requirement_code IS '清单条目产品编号快照，如 Q1 / S1';
COMMENT ON COLUMN biz_review_item.title IS '条目标题快照';
COMMENT ON COLUMN biz_review_item.main_type IS 'QUALIFICATION / SCORING';
COMMENT ON COLUMN biz_review_item.review_type IS '响应性审查 / 废标风险 / 评分建议 等';
COMMENT ON COLUMN biz_review_item.status IS '资格：符合/不符合/无法判断；评分项为空';
COMMENT ON COLUMN biz_review_item.veto_risk IS '是否废标风险';
COMMENT ON COLUMN biz_review_item.judgement IS '判定说明（先结论后依据）';
COMMENT ON COLUMN biz_review_item.bid_evidence_json IS '投标证据 JSON 数组 [{fragmentId,page,quote}]';
COMMENT ON COLUMN biz_review_item.note IS '文档内不一致等备注';
COMMENT ON COLUMN biz_review_item.suggested_score IS '评分建议分；资格项为空';
COMMENT ON COLUMN biz_review_item.full_score IS '该条目满分快照';
COMMENT ON COLUMN biz_review_item.deduction_reason IS '扣分理由';
