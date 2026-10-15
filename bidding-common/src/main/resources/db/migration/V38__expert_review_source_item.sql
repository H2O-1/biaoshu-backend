-- 专家评审：评审明细关联来源 AI 审查条目

ALTER TABLE biz_expert_review_item
    ADD COLUMN IF NOT EXISTS source_review_item_id BIGINT;

UPDATE biz_expert_review_item
SET source_review_item_id = source.id
FROM (
    SELECT DISTINCT ON (r.project_id, ri.package_id, ri.requirement_item_id)
        r.project_id,
        ri.package_id,
        ri.requirement_item_id,
        ri.id
    FROM biz_review_item ri
    INNER JOIN biz_review r ON r.id = ri.review_id
    WHERE r.status = 'SUCCESS'
    ORDER BY r.project_id, ri.package_id, ri.requirement_item_id,
             r.version DESC, r.id DESC, ri.id DESC
) source
WHERE biz_expert_review_item.source_review_item_id IS NULL
  AND biz_expert_review_item.project_id = source.project_id
  AND biz_expert_review_item.bid_package_id = source.package_id
  AND biz_expert_review_item.requirement_item_id = source.requirement_item_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM biz_expert_review_item
        WHERE source_review_item_id IS NULL
    ) THEN
        RAISE EXCEPTION 'biz_expert_review_item.source_review_item_id cannot be backfilled from biz_review_item';
    END IF;
END $$;

ALTER TABLE biz_expert_review_item
    ALTER COLUMN source_review_item_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_biz_expert_review_item_source_review_item'
          AND conrelid = 'biz_expert_review_item'::regclass
    ) THEN
        ALTER TABLE biz_expert_review_item
            ADD CONSTRAINT fk_biz_expert_review_item_source_review_item
                FOREIGN KEY (source_review_item_id) REFERENCES biz_review_item (id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_biz_expert_review_item_source
    ON biz_expert_review_item (source_review_item_id);

COMMENT ON COLUMN biz_expert_review_item.source_review_item_id IS '来源 AI 审查条目 ID，关联 biz_review_item.id';
