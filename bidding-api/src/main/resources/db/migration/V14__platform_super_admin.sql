-- 平台超管角色；组织支持逻辑删除状态说明
INSERT INTO sys_role (code, name)
VALUES ('PLATFORM_SUPER_ADMIN', '平台超级管理员')
ON CONFLICT (code) DO NOTHING;

COMMENT ON COLUMN sys_org.status IS '状态：ACTIVE 正常 / DISABLED 停用 / DELETED 逻辑删除';
