-- Deleted projects should release their bid package company names.
-- Keep tender packages bound under the existing project deletion rule.

DELETE FROM biz_document_vector
WHERE document_id IN (
    SELECT d.id
    FROM biz_document d
    INNER JOIN biz_package p ON p.id = d.package_id
    INNER JOIN biz_project pr ON pr.id = p.project_id
    WHERE pr.status = 'DELETED'
      AND p.type = 'BID'
      AND p.status <> 'DELETED'
);

DELETE FROM biz_document_chunk
WHERE document_id IN (
    SELECT d.id
    FROM biz_document d
    INNER JOIN biz_package p ON p.id = d.package_id
    INNER JOIN biz_project pr ON pr.id = p.project_id
    WHERE pr.status = 'DELETED'
      AND p.type = 'BID'
      AND p.status <> 'DELETED'
);

UPDATE biz_document
SET status = 'DELETED',
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT d.id
    FROM biz_document d
    INNER JOIN biz_package p ON p.id = d.package_id
    INNER JOIN biz_project pr ON pr.id = p.project_id
    WHERE pr.status = 'DELETED'
      AND p.type = 'BID'
      AND p.status <> 'DELETED'
      AND d.status <> 'DELETED'
);

UPDATE biz_package
SET status = 'DELETED',
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT p.id
    FROM biz_package p
    INNER JOIN biz_project pr ON pr.id = p.project_id
    WHERE pr.status = 'DELETED'
      AND p.type = 'BID'
      AND p.status <> 'DELETED'
);
