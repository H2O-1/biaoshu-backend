-- 投标包扩展：公司地址与报价（招标包这几列为空；不含税价由前端算好传入）
ALTER TABLE biz_package ADD COLUMN company_address VARCHAR(256);
ALTER TABLE biz_package ADD COLUMN price_with_tax NUMERIC(18, 2);
ALTER TABLE biz_package ADD COLUMN tax_rate NUMERIC(8, 4);
ALTER TABLE biz_package ADD COLUMN price_without_tax NUMERIC(18, 2);

COMMENT ON COLUMN biz_package.company_address IS '公司地址（仅投标包使用；招标包为空）';
COMMENT ON COLUMN biz_package.price_with_tax IS '含税价格（仅投标包；可选）';
COMMENT ON COLUMN biz_package.tax_rate IS '税率，如 13 表示 13%（仅投标包；可选）';
COMMENT ON COLUMN biz_package.price_without_tax IS '不含税价（前端计算后传入；仅投标包；可选）';
