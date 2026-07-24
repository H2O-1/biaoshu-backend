-- 文档元数据（文件内容在 MinIO；本期支持上传/列表/删除）
CREATE TABLE biz_document (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL,
    project_id      BIGINT,
    package_id      BIGINT       NOT NULL,
    file_name       VARCHAR(256) NOT NULL,
    content_type    VARCHAR(128),
    file_ext        VARCHAR(16)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    object_key      VARCHAR(512) NOT NULL,
    index_status    VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_biz_document_package_status ON biz_document (package_id, status);
CREATE INDEX idx_biz_document_org_id ON biz_document (org_id, status);
CREATE INDEX idx_biz_document_project_id ON biz_document (project_id, status);

COMMENT ON TABLE biz_document IS '标包下文档元数据（实体文件存对象存储）';
COMMENT ON COLUMN biz_document.id IS '主键';
COMMENT ON COLUMN biz_document.org_id IS '所属组织 ID，关联 sys_org.id';
COMMENT ON COLUMN biz_document.project_id IS '所属项目 ID（可空；包未绑项目时为空），关联 biz_project.id';
COMMENT ON COLUMN biz_document.package_id IS '所属标包 ID，关联 biz_package.id';
COMMENT ON COLUMN biz_document.file_name IS '原始文件名';
COMMENT ON COLUMN biz_document.content_type IS 'MIME 类型';
COMMENT ON COLUMN biz_document.file_ext IS '扩展名：pdf / doc / docx / xls / xlsx';
COMMENT ON COLUMN biz_document.size_bytes IS '文件大小（字节）';
COMMENT ON COLUMN biz_document.object_key IS '对象存储中的 key';
COMMENT ON COLUMN biz_document.index_status IS '解析/向量化状态占位：PENDING 待处理 / PROCESSING 处理中 / SUCCESS 成功 / FAILED 失败（本期仅占位）';
COMMENT ON COLUMN biz_document.status IS '状态：ACTIVE 正常 / DELETED 已删除';
COMMENT ON COLUMN biz_document.created_by IS '上传人用户 ID，关联 sys_user.id';
COMMENT ON COLUMN biz_document.created_at IS '创建时间';
COMMENT ON COLUMN biz_document.updated_at IS '更新时间';
