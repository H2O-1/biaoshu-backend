-- 用户删除改为物理删除：释放 username / phone / email 唯一值，允许重新创建同名账号。

ALTER TABLE sys_user_role
    DROP CONSTRAINT IF EXISTS sys_user_role_user_id_fkey;

ALTER TABLE sys_user_role
    ADD CONSTRAINT fk_sys_user_role_user
        FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE;

ALTER TABLE sys_user
    DROP CONSTRAINT IF EXISTS sys_user_created_by_fkey;

ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL;

DELETE FROM sys_user
WHERE status = 'DELETED';

COMMENT ON COLUMN sys_user.status IS '账号状态：ACTIVE 正常 / DISABLED 停用；删除为物理删除';
