-- 基础模板 + 网安行业模板 RAG 检索词（13 个维度，51 条 query）
-- 手动执行用 seed，不会被 Flyway 自动执行。
-- 说明：
-- 1. 本脚本只新增/更新 TPL_* 维度，不清空现有维度。
-- 2. 建清单会使用全部 enabled=true 的维度；如果只想使用本模板，请先停用其他维度。

BEGIN;

WITH dim_seed(code, name, sort_order) AS (
    VALUES
        ('TPL_BID_QUALIFICATION', '投标资格', 10),
        ('TPL_REJECT_REDLINE', '废标红线', 20),
        ('TPL_RESPONSIVE_REVIEW', '响应性审查', 30),
        ('TPL_FILE_FORMAT', '文件格式', 40),
        ('TPL_BUSINESS_REVIEW', '商务与评审', 50),
        ('TPL_CYBER_QUALIFICATION', '安全资质', 110),
        ('TPL_CYBER_PERSONNEL', '安全人员', 120),
        ('TPL_CYBER_TECHNOLOGY', '安全技术', 130),
        ('TPL_CYBER_PRODUCT', '安全产品', 140),
        ('TPL_CYBER_OPERATION', '安全运维', 150),
        ('TPL_CYBER_DATA', '数据安全', 160),
        ('TPL_CYBER_PENTEST_EMERGENCY', '渗透应急', 170),
        ('TPL_CYBER_GRADE_COMPLIANCE', '等保合规', 180)
)
INSERT INTO biz_review_dimension (code, name, default_selected, enabled, sort_order)
SELECT code, name, TRUE, TRUE, sort_order
FROM dim_seed
ON CONFLICT (code) DO UPDATE
SET name = EXCLUDED.name,
    default_selected = TRUE,
    enabled = TRUE,
    sort_order = EXCLUDED.sort_order;

DELETE FROM biz_review_dimension_query
WHERE dimension_id IN (
    SELECT id
    FROM biz_review_dimension
    WHERE code IN (
        'TPL_BID_QUALIFICATION',
        'TPL_REJECT_REDLINE',
        'TPL_RESPONSIVE_REVIEW',
        'TPL_FILE_FORMAT',
        'TPL_BUSINESS_REVIEW',
        'TPL_CYBER_QUALIFICATION',
        'TPL_CYBER_PERSONNEL',
        'TPL_CYBER_TECHNOLOGY',
        'TPL_CYBER_PRODUCT',
        'TPL_CYBER_OPERATION',
        'TPL_CYBER_DATA',
        'TPL_CYBER_PENTEST_EMERGENCY',
        'TPL_CYBER_GRADE_COMPLIANCE'
    )
);

INSERT INTO biz_review_dimension_query (dimension_id, query_text, sort_order)
SELECT d.id, v.query_text, v.sort_order
FROM (VALUES
    -- 基础模板：投标资格
    ('TPL_BID_QUALIFICATION', '投标人必须具备哪些基本资格条件，包括独立法人、财务制度、纳税社保等', 10),
    ('TPL_BID_QUALIFICATION', '投标人需要具备哪些资质等级证书和专业许可', 20),
    ('TPL_BID_QUALIFICATION', '投标人类似项目业绩要求是什么，需要提供哪些业绩证明材料', 30),
    ('TPL_BID_QUALIFICATION', '联合体投标是否被允许，对联合体各方有什么具体要求', 40),
    ('TPL_BID_QUALIFICATION', '项目经理和技术负责人的资格要求是什么，是否需要特定证书', 50),
    ('TPL_BID_QUALIFICATION', '投标人不得存在哪些不良记录、违法记录或禁止投标情形', 60),

    -- 基础模板：废标红线
    ('TPL_REJECT_REDLINE', '投标报价超出采购预算或最高投标限价是否会导致废标', 10),
    ('TPL_REJECT_REDLINE', '★号标注的实质性条款不满足或不提供证明材料的后果', 20),
    ('TPL_REJECT_REDLINE', '投标文件逾期送达或未送达指定地点是否被拒绝接收', 30),
    ('TPL_REJECT_REDLINE', '投标保证金未按规定方式或金额缴纳，或者未按时到账的后果', 40),
    ('TPL_REJECT_REDLINE', '投标人提供虚假材料或虚假承诺谋取中标的后果', 50),
    ('TPL_REJECT_REDLINE', '投标文件未按要求签字盖章或加盖公章不符合规定的后果', 60),

    -- 基础模板：响应性审查
    ('TPL_RESPONSIVE_REVIEW', '投标文件的符合性审查或响应性审查标准是什么，审查哪些内容', 10),
    ('TPL_RESPONSIVE_REVIEW', '投标范围或服务内容是否可以偏离招标文件要求', 20),
    ('TPL_RESPONSIVE_REVIEW', '工期或交付时间的要求是什么，不满足工期要求有何后果', 30),
    ('TPL_RESPONSIVE_REVIEW', '质量标准和验收条件的要求是什么，是否必须完全响应', 40),
    ('TPL_RESPONSIVE_REVIEW', '合同条款中是否存在不可偏离的实质性要求', 50),

    -- 基础模板：文件格式
    ('TPL_FILE_FORMAT', '投标文件需要提交几份正本和几份副本', 10),
    ('TPL_FILE_FORMAT', '投标文件的装订方式、页码编排和目录编制要求', 20),
    ('TPL_FILE_FORMAT', '投标文件哪些位置必须签字盖章，骑缝章是否必须', 30),

    -- 基础模板：商务与评审
    ('TPL_BUSINESS_REVIEW', '是否允许提交多个报价方案或备选报价', 10),
    ('TPL_BUSINESS_REVIEW', '合同付款方式和结算条件是如何规定的', 20),
    ('TPL_BUSINESS_REVIEW', '是否需要缴纳履约保证金或提供履约担保，金额和形式是什么', 30),
    ('TPL_BUSINESS_REVIEW', '投标报价的有效期是多长时间', 40),
    ('TPL_BUSINESS_REVIEW', '评标方法和评审标准是什么，采用最低评标价法还是综合评分法', 50),
    ('TPL_BUSINESS_REVIEW', '有哪些加分项和扣分项，是否存在价格扣除或优惠政策', 60),

    -- 网安行业模板：安全资质
    ('TPL_CYBER_QUALIFICATION', '信息安全服务资质认证要求是什么，如CCRC资质需要什么级别', 10),
    ('TPL_CYBER_QUALIFICATION', '网络安全等级保护测评机构的资质要求', 20),
    ('TPL_CYBER_QUALIFICATION', 'ISO27001、ISO20000等信息安全管理体系认证要求', 30),
    ('TPL_CYBER_QUALIFICATION', '安全产品是否需要销售许可证、认证证书或检测报告', 40),

    -- 网安行业模板：安全人员
    ('TPL_CYBER_PERSONNEL', '安全服务团队需要具备哪些专业认证，如CISP、等保测评师等', 10),
    ('TPL_CYBER_PERSONNEL', '驻场安全人员的数量、值守安排和资质要求', 20),
    ('TPL_CYBER_PERSONNEL', '安全团队人员是否需要提供社保缴纳证明和劳动合同', 30),

    -- 网安行业模板：安全技术
    ('TPL_CYBER_TECHNOLOGY', '网络安全防护方案的编制要求是什么，需要包含哪些内容', 10),
    ('TPL_CYBER_TECHNOLOGY', '身份认证、访问控制和权限管理的技术要求是什么', 20),
    ('TPL_CYBER_TECHNOLOGY', '数据加密传输、国密算法和通信安全的要求是什么', 30),

    -- 网安行业模板：安全产品
    ('TPL_CYBER_PRODUCT', '要求提供哪些安全设备，如防火墙、入侵防御、漏洞扫描等的功能参数', 10),
    ('TPL_CYBER_PRODUCT', '安全设备的性能指标要求是什么，如吞吐量、并发连接数', 20),
    ('TPL_CYBER_PRODUCT', '安全产品的授权期限、特征库更新和升级维护要求', 30),

    -- 网安行业模板：安全运维
    ('TPL_CYBER_OPERATION', '安全运维服务的范围、频次和服务等级协议要求', 10),
    ('TPL_CYBER_OPERATION', '漏洞扫描频次、漏洞修复时限和补丁管理要求', 20),
    ('TPL_CYBER_OPERATION', '安全运维需交付哪些报告，安全日志保存期限和分析要求', 30),

    -- 网安行业模板：数据安全
    ('TPL_CYBER_DATA', '数据安全保护措施要求是什么，包括加密、脱敏、防泄漏等', 10),
    ('TPL_CYBER_DATA', '数据备份的频次、保留期限和灾难恢复的要求', 20),
    ('TPL_CYBER_DATA', '需要遵守哪些数据安全法律法规，个人信息保护有何要求', 30),

    -- 网安行业模板：渗透应急
    ('TPL_CYBER_PENTEST_EMERGENCY', '渗透测试的范围、方法、工具和报告要求是什么', 10),
    ('TPL_CYBER_PENTEST_EMERGENCY', '安全事件应急响应的流程、组织架构和响应时限要求', 20),
    ('TPL_CYBER_PENTEST_EMERGENCY', '安全事件的分级标准是什么，重大事件上报机制有何要求', 30),

    -- 网安行业模板：等保合规
    ('TPL_CYBER_GRADE_COMPLIANCE', '等级保护定级、测评和整改的具体要求是什么', 10),
    ('TPL_CYBER_GRADE_COMPLIANCE', '安全管理制度体系、安全组织架构和保密协议要求', 20),
    ('TPL_CYBER_GRADE_COMPLIANCE', '是否有信创产品或国产化替代要求，需要适配哪些国产平台', 30)
) AS v(code, query_text, sort_order)
INNER JOIN biz_review_dimension d ON d.code = v.code;

COMMIT;
