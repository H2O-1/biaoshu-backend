ALTER TABLE biz_tender_analysis
    ADD COLUMN IF NOT EXISTS tender_no VARCHAR(128);

ALTER TABLE biz_bid_simulation_audit
    ADD COLUMN IF NOT EXISTS tender_no VARCHAR(128);

COMMENT ON COLUMN biz_tender_analysis.tender_no IS '招标编号';
COMMENT ON COLUMN biz_bid_simulation_audit.tender_no IS '招标编号';
