-- 合同 RAG 维度种子（优化稿 v2）
-- 删除原招投标种子维 QUALIFICATION / REJECT / SCHEDULE / COMMERCIAL
-- P0 默认勾选 8 维；P1 可选 4 维（default_selected=false）

-- 先删旧维及其检索词
DELETE FROM biz_review_dimension_query
WHERE dimension_id IN (
    SELECT id FROM biz_review_dimension
    WHERE code IN ('QUALIFICATION', 'REJECT', 'SCHEDULE', 'COMMERCIAL')
);

DELETE FROM biz_review_dimension
WHERE code IN ('QUALIFICATION', 'REJECT', 'SCHEDULE', 'COMMERCIAL');

INSERT INTO biz_review_dimension (code, name, default_selected, enabled, sort_order)
VALUES
    ('FEE',           '费用与价款',     TRUE,  TRUE, 100),
    ('SETTLEMENT',    '验收与结算',     TRUE,  TRUE, 110),
    ('LIABILITY',     '保证与责任',     TRUE,  TRUE, 120),
    ('PERSONNEL',     '人员与资质',     TRUE,  TRUE, 130),
    ('TIME_PLACE',    '地点与期限',     TRUE,  TRUE, 140),
    ('EFFECT',        '生效与解除',     TRUE,  TRUE, 150),
    ('COMPLIANCE',    '法规与争议',     TRUE,  TRUE, 160),
    ('CHANGE_NOTICE', '变更与通知',     TRUE,  TRUE, 170),
    ('SCOPE',         '范围与边界',     FALSE, TRUE, 200),
    ('DATA',          '数据与信息安全', FALSE, TRUE, 210),
    ('DOC',           '文件效力与归档', FALSE, TRUE, 220),
    ('OTHER',         '通用兜底',       FALSE, TRUE, 230)
ON CONFLICT (code) DO UPDATE SET
    name = EXCLUDED.name,
    default_selected = EXCLUDED.default_selected,
    enabled = EXCLUDED.enabled,
    sort_order = EXCLUDED.sort_order,
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM biz_review_dimension_query
WHERE dimension_id IN (
    SELECT id FROM biz_review_dimension
    WHERE code IN (
        'FEE', 'SETTLEMENT', 'LIABILITY', 'PERSONNEL', 'TIME_PLACE', 'EFFECT',
        'COMPLIANCE', 'CHANGE_NOTICE', 'SCOPE', 'DATA', 'DOC', 'OTHER'
    )
);

INSERT INTO biz_review_dimension_query (dimension_id, query_text, sort_order)
SELECT d.id, v.query_text, v.sort_order
FROM (VALUES
    ('FEE', '合同总价 分项价格 价款金额', 10),
    ('FEE', '付款节点 付款比例 付款条件', 20),
    ('FEE', '币种 税费 含税 专票', 30),
    ('FEE', '逾期付款 违约金利息 调价机制', 40),
    ('FEE', '收款账户 履约保证金 质量保证金退还', 50),
    ('SETTLEMENT', '验收标准 验收流程 验收合格', 10),
    ('SETTLEMENT', '结算方式 竣工结算 最终审计', 20),
    ('SETTLEMENT', '开票要求 发票 尾款支付条件', 30),
    ('LIABILITY', '履约担保 质量保证 质保期', 10),
    ('LIABILITY', '违约责任 赔偿 责任上限', 20),
    ('LIABILITY', '知识产权保证 第三方索赔 保密义务', 30),
    ('PERSONNEL', '关键人员 项目经理 人员资质', 10),
    ('PERSONNEL', '学历 从业年限 专业证书', 20),
    ('PERSONNEL', '分包资质 人员保险', 30),
    ('TIME_PLACE', '履行地点 交付地点 服务地点', 10),
    ('TIME_PLACE', '合同期限 开工竣工 交付日期', 20),
    ('TIME_PLACE', '工期 里程碑 延期顺延条件', 30),
    ('EFFECT', '合同生效条件 生效日期', 10),
    ('EFFECT', '解除权 合同终止 终止后果', 20),
    ('EFFECT', '不可抗力', 30),
    ('COMPLIANCE', '适用法律 管辖 争议解决 仲裁', 10),
    ('COMPLIANCE', '行业合规 环保安全要求', 20),
    ('COMPLIANCE', '反腐败 反贿赂 廉洁条款', 30),
    ('CHANGE_NOTICE', '合同变更 变更程序 变更生效', 10),
    ('CHANGE_NOTICE', '书面通知 送达地址 联系人 回复时限', 20),
    ('SCOPE', '工作范围 服务内容 交付物', 10),
    ('SCOPE', '不包含 排除范围 工作边界', 20),
    ('DATA', '数据安全 数据权属 个人信息保护', 10),
    ('DATA', '数据传输 备份 容灾', 20),
    ('DOC', '附件清单 文件优先顺序 文本效力', 10),
    ('DOC', '文件保管期限 正本份数 文件交付', 20),
    ('OTHER', '完整协议 可分割条款 弃权', 10),
    ('OTHER', '权利转让限制 合同转让', 20)
) AS v(code, query_text, sort_order)
INNER JOIN biz_review_dimension d ON d.code = v.code;

COMMENT ON TABLE biz_review_dimension IS '全局审查维度字典（平台级；合同 RAG 维度种子）';
