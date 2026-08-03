-- 补全建清单相关表字段注释（V11 仅部分注释；本版只 COMMENT，不改结构）

COMMENT ON TABLE biz_requirement_checklist IS '招标要求清单主表：按项目多版本；每次建清单新建一行，不覆盖旧版';
COMMENT ON COLUMN biz_requirement_checklist.id IS '主键，即 checklistId';
COMMENT ON COLUMN biz_requirement_checklist.org_id IS '所属组织 ID（多租户过滤）';
COMMENT ON COLUMN biz_requirement_checklist.project_id IS '所属项目 ID，关联 biz_project.id';
COMMENT ON COLUMN biz_requirement_checklist.version IS '项目内版本号，从 1 递增；与 project_id 唯一';
COMMENT ON COLUMN biz_requirement_checklist.status IS 'BUILDING 构建中 / SUCCESS 成功可引用 / FAILED 失败';
COMMENT ON COLUMN biz_requirement_checklist.dimension_codes IS '本次勾选的审查维度 code 快照，JSON 数组，如 ["QUALIFICATION","REJECT"]';
COMMENT ON COLUMN biz_requirement_checklist.tender_document_ids IS '参与建清单的招标文档 id 快照，JSON 数组';
COMMENT ON COLUMN biz_requirement_checklist.prompt_version IS '抽取提示词版本号，便于复现，如 extract-v1';
COMMENT ON COLUMN biz_requirement_checklist.recall_top_k IS '建清单时每条 query 向量召回条数快照';
COMMENT ON COLUMN biz_requirement_checklist.rerank_top_n IS '建清单时重排保留段数快照（喂给 LLM）';
COMMENT ON COLUMN biz_requirement_checklist.error_message IS '失败原因；成功时为空';
COMMENT ON COLUMN biz_requirement_checklist.created_by IS '发起建清单的用户 ID';
COMMENT ON COLUMN biz_requirement_checklist.created_at IS '创建时间（进入 BUILDING）';
COMMENT ON COLUMN biz_requirement_checklist.updated_at IS '最后更新时间（成功/失败回写）';

COMMENT ON TABLE biz_requirement_item IS '清单条目：LLM 从招标原文抽取的可核对要求；归属某一版 checklist';
COMMENT ON COLUMN biz_requirement_item.id IS '主键，即 requirement item id';
COMMENT ON COLUMN biz_requirement_item.checklist_id IS '所属清单 ID，外键 → biz_requirement_checklist.id';
COMMENT ON COLUMN biz_requirement_item.dimension_code IS '所属审查维度 code，如 QUALIFICATION / REJECT';
COMMENT ON COLUMN biz_requirement_item.text IS '要求正文（供后续 REVIEW 作对照 query）';
COMMENT ON COLUMN biz_requirement_item.source_excerpt IS '招标原文摘录（溯源展示）';
COMMENT ON COLUMN biz_requirement_item.page_no IS '摘录对应页码，可空';
COMMENT ON COLUMN biz_requirement_item.chunk_ids IS '溯源切块 id 列表，JSON 数组，对应 biz_document_chunk.id';
COMMENT ON COLUMN biz_requirement_item.must_flag IS '是否必须响应：true 必须 / false 非必须';
COMMENT ON COLUMN biz_requirement_item.sort_order IS '展示与审查顺序，从 0 起';
COMMENT ON COLUMN biz_requirement_item.created_at IS '条目写入时间';

COMMENT ON COLUMN biz_job.payload_json IS '输入参数 JSON：INDEX 含 documentId；BUILD_CHECKLIST 含 checklistId/projectId/packageId';
