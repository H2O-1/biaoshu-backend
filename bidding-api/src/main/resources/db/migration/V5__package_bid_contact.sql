-- 投标包扩展字段：公司名仍用 name；另存负责人信息（招标包这两列为空）
ALTER TABLE biz_package ADD COLUMN contact_name VARCHAR(64);
ALTER TABLE biz_package ADD COLUMN contact_phone VARCHAR(32);

COMMENT ON COLUMN biz_package.name IS '名称：招标包为包名；投标包为投标公司名称（同组织/同项目唯一规则见应用层）';
COMMENT ON COLUMN biz_package.contact_name IS '负责人姓名（仅投标包使用；招标包为空）';
COMMENT ON COLUMN biz_package.contact_phone IS '负责人电话（仅投标包使用；招标包为空）';
