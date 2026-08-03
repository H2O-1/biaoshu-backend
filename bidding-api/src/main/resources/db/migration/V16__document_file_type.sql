-- 文档业务类型（前端上传时传入，后端只存不校验枚举）
ALTER TABLE biz_document
    ADD COLUMN IF NOT EXISTS file_type VARCHAR(128) NOT NULL DEFAULT '';

COMMENT ON COLUMN biz_document.file_type IS '业务文件类型（前端传入自由字符串；扩展名仍用 file_ext）';
