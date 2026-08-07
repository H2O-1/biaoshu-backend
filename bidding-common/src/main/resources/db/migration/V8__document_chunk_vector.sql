-- M3：文档切块与向量（Fake 索引可先落库；真 embedding 后再迁 pgvector）
-- embedding 暂存 JSON 数组文本，避免本阶段强依赖 pgvector 扩展。

CREATE TABLE IF NOT EXISTS biz_document_chunk (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL,
    project_id      BIGINT,
    package_id      BIGINT       NOT NULL,
    document_id     BIGINT       NOT NULL,
    chunk_index     INT          NOT NULL,
    page_no         INT,
    content         TEXT         NOT NULL,
    token_estimate  INT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_document_chunk_doc_idx UNIQUE (document_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_biz_document_chunk_org_doc ON biz_document_chunk (org_id, document_id);
CREATE INDEX IF NOT EXISTS idx_biz_document_chunk_document ON biz_document_chunk (document_id);

CREATE TABLE IF NOT EXISTS biz_document_vector (
    id              BIGSERIAL PRIMARY KEY,
    org_id          BIGINT       NOT NULL,
    project_id      BIGINT,
    document_id     BIGINT       NOT NULL,
    chunk_id        BIGINT       NOT NULL,
    embedding_dim   INT          NOT NULL,
    embedding_json  TEXT         NOT NULL,
    model_name      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_biz_document_vector_chunk UNIQUE (chunk_id)
);

CREATE INDEX IF NOT EXISTS idx_biz_document_vector_org_doc ON biz_document_vector (org_id, document_id);
CREATE INDEX IF NOT EXISTS idx_biz_document_vector_document ON biz_document_vector (document_id);

COMMENT ON TABLE biz_document_chunk IS '文档切块（INDEX 产物）';
COMMENT ON COLUMN biz_document_chunk.chunk_index IS '文档内切块序号，从 0 起';
COMMENT ON COLUMN biz_document_chunk.page_no IS '页码，Fake 阶段可空或占位';
COMMENT ON COLUMN biz_document_chunk.content IS '切块正文';
COMMENT ON COLUMN biz_document_chunk.token_estimate IS '粗估 token 数';

COMMENT ON TABLE biz_document_vector IS '切块向量；embedding_json 为临时存储，后续可迁 pgvector';
COMMENT ON COLUMN biz_document_vector.embedding_json IS '向量 JSON 数组，如 [0.1,0.2,...]';
COMMENT ON COLUMN biz_document_vector.model_name IS '模型名；Fake 为 fake-embedding';
