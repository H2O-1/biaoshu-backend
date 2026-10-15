-- 组织类型：一个组织固定为招标方或投标方；系统组织单独标记

ALTER TABLE sys_org
    ADD COLUMN IF NOT EXISTS org_type VARCHAR(32);

UPDATE sys_org
SET org_type = CASE WHEN code = 'SYSTEM' THEN 'SYSTEM' ELSE 'TENDERER' END
WHERE org_type IS NULL;

ALTER TABLE sys_org
    ALTER COLUMN org_type SET NOT NULL;

COMMENT ON COLUMN sys_org.org_type IS '组织类型：SYSTEM 系统组织 / TENDERER 招标方 / BIDDER 投标方';
