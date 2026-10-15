ALTER TABLE biz_tender_analysis
    ADD COLUMN IF NOT EXISTS source_analysis_id BIGINT;

ALTER TABLE biz_tender_analysis
    ADD COLUMN IF NOT EXISTS original_created_at TIMESTAMP;

UPDATE biz_tender_analysis
SET original_created_at = created_at
WHERE original_created_at IS NULL;

ALTER TABLE biz_tender_analysis
    ALTER COLUMN original_created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE biz_tender_analysis
    ALTER COLUMN original_created_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_biz_tender_analysis_source
    ON biz_tender_analysis (source_analysis_id);

COMMENT ON COLUMN biz_tender_analysis.source_analysis_id IS
    '重新解析来源记录 ID；首次创建为空';
COMMENT ON COLUMN biz_tender_analysis.original_created_at IS
    '首次创建时间；重新解析记录继承来源记录的首次创建时间';
