-- 项目基本信息：代理机构 / 项目类型 / 负责人改为可空（创建、更新均可不填；空白存 null）
ALTER TABLE biz_project ALTER COLUMN agency DROP NOT NULL;
ALTER TABLE biz_project ALTER COLUMN project_type DROP NOT NULL;
ALTER TABLE biz_project ALTER COLUMN leader_name DROP NOT NULL;

COMMENT ON COLUMN biz_project.agency IS '代理机构（可选，空为 null）';
COMMENT ON COLUMN biz_project.project_type IS '项目类型（可选，空为 null）';
COMMENT ON COLUMN biz_project.leader_name IS '项目负责人（可选，空为 null）';
