-- 组织主数据扩展：城市、创建人；公司名称唯一
ALTER TABLE sys_org ADD COLUMN city VARCHAR(64) NULL;
ALTER TABLE sys_org ADD COLUMN created_by BIGINT NULL;

UPDATE sys_org SET city = '系统' WHERE code = 'SYSTEM' AND city IS NULL;

CREATE UNIQUE INDEX uk_sys_org_name ON sys_org (name);

COMMENT ON COLUMN sys_org.name IS '公司名称（创建后不可修改）';
COMMENT ON COLUMN sys_org.city IS '所在城市';
COMMENT ON COLUMN sys_org.status IS '状态：ACTIVE 正常 / DISABLED 停用';
COMMENT ON COLUMN sys_org.created_by IS '创建人用户 ID，关联 sys_user.id；种子组织可为空';
COMMENT ON COLUMN sys_org.code IS '组织编码，唯一，创建后不可修改';
