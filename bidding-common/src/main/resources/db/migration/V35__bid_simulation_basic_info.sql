-- 投标模拟审核详情头部基础信息：复用招标解析基础信息抽取结果

ALTER TABLE biz_bid_simulation_audit
    ADD COLUMN IF NOT EXISTS tenderer VARCHAR(256),
    ADD COLUMN IF NOT EXISTS agency VARCHAR(256),
    ADD COLUMN IF NOT EXISTS budget_amount_text VARCHAR(128),
    ADD COLUMN IF NOT EXISTS bid_deadline_text VARCHAR(128),
    ADD COLUMN IF NOT EXISTS bid_deadline_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS basic_info_evidence_json TEXT,
    ADD COLUMN IF NOT EXISTS basic_info_raw_json TEXT;

COMMENT ON COLUMN biz_bid_simulation_audit.tenderer IS '招标人';
COMMENT ON COLUMN biz_bid_simulation_audit.agency IS '招标代理';
COMMENT ON COLUMN biz_bid_simulation_audit.budget_amount_text IS '预算金额原文，不拆分最高限价';
COMMENT ON COLUMN biz_bid_simulation_audit.bid_deadline_text IS '投标截止时间原文';
COMMENT ON COLUMN biz_bid_simulation_audit.bid_deadline_at IS '投标截止时间标准时间；无法可靠解析时为空';
COMMENT ON COLUMN biz_bid_simulation_audit.basic_info_evidence_json IS '基础信息证据 JSON 数组，用于前端查看原文片段';
COMMENT ON COLUMN biz_bid_simulation_audit.basic_info_raw_json IS '基础信息抽取 LLM 原始输出';
