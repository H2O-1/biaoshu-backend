-- 产品口径：建清单固定使用全部启用维度；13 维全部 default_selected=true

UPDATE biz_review_dimension
SET default_selected = TRUE,
    updated_at = CURRENT_TIMESTAMP
WHERE enabled = TRUE;
