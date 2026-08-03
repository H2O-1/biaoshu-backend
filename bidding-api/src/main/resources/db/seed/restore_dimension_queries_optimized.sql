-- 非 Flyway：手动执行可把检索词换回 V26「优化短词版」（维度分类不动）
-- 用法：在库里直接跑本文件；或复制内容做成新迁移 Vxx

DELETE FROM biz_review_dimension_query;

INSERT INTO biz_review_dimension_query (dimension_id, query_text, sort_order)
SELECT d.id, v.query_text, v.sort_order
FROM (VALUES
    ('QUALIFICATION', '投标人资格条件 资格规定', 10),
    ('QUALIFICATION', '投标人必须具备的资质要求', 20),
    ('QUALIFICATION', '投标人注册资本要求', 30),
    ('QUALIFICATION', '投标人业绩要求 业绩证明', 40),
    ('QUALIFICATION', '近三年重大违法记录限制', 50),
    ('QUALIFICATION', '联合体投标规定', 60),

    ('CERTIFICATE', '企业资质证书 招标要求提供的证书', 10),
    ('CERTIFICATE', '资质证书有效期 原件或扫描件', 20),
    ('CERTIFICATE', '检测报告 国家级检测报告要求', 30),
    ('CERTIFICATE', '安全生产许可证要求', 40),
    ('CERTIFICATE', '营业执照要求', 50),

    ('PERSONNEL', '项目负责人资格要求', 10),
    ('PERSONNEL', '项目经理证书 建造师注册要求', 20),
    ('PERSONNEL', '技术负责人资格要求', 30),
    ('PERSONNEL', '安全员配备要求', 40),
    ('PERSONNEL', '项目团队成员资格 人员业绩', 50),
    ('PERSONNEL', '人员社保缴纳及证明要求', 60),

    ('REJECT', '废标条件 投标无效情形', 10),
    ('REJECT', '否决投标 取消投标资格情形', 20),
    ('REJECT', '资格审查不合格 符合性审查不合格', 30),
    ('REJECT', '重大偏差 细微偏差认定', 40),
    ('REJECT', '星号条款 实质性条款不满足后果', 50),

    ('COMMERCIAL', '投标保证金金额 缴纳方式', 10),
    ('COMMERCIAL', '投标有效期要求', 20),
    ('COMMERCIAL', '工期要求 合同履行期限', 30),
    ('COMMERCIAL', '质保期要求', 40),
    ('COMMERCIAL', '付款方式 付款条件', 50),
    ('COMMERCIAL', '违约责任规定', 60),

    ('TECHNICAL', '技术参数要求 实质性技术参数', 10),
    ('TECHNICAL', '技术偏离规定', 20),
    ('TECHNICAL', '技术方案编制要求', 30),
    ('TECHNICAL', '关键技术要求 设备配置要求', 40),
    ('TECHNICAL', '售后服务要求 技术培训要求', 50),

    ('PRICE', '报价编制要求 投标函总价', 10),
    ('PRICE', '投标报价有效期 金额小数位', 20),
    ('PRICE', '分项报价表填写要求', 30),
    ('PRICE', '暂列金额 规费 税金要求', 40),
    ('PRICE', '下浮率 折扣率要求', 50),

    ('FORMAT', '投标文件格式 装订 打印 页码', 10),
    ('FORMAT', '封面脊背 签字盖章 骑缝章', 20),
    ('FORMAT', '密封封装 开标一览表单独封装', 30),
    ('FORMAT', '正副本份数要求', 40),

    ('EVIDENCE', '必须提交的证明材料清单', 10),
    ('EVIDENCE', '业绩证明材料要求', 20),
    ('EVIDENCE', '社保缴纳证明 纳税证明', 30),
    ('EVIDENCE', '财务审计报告要求', 40),
    ('EVIDENCE', '法定代表人身份证明 授权委托书', 50),
    ('EVIDENCE', '中小企业声明函要求', 60),

    ('E_BID', '电子标上传 解密要求', 10),
    ('E_BID', '投标截止时间 开标时间地点', 20),
    ('E_BID', '投标文件递交方式', 30),
    ('E_BID', 'CA证书 电子签章要求', 40),

    ('COMPLIANCE', '排他性条款 歧视性条款 指定品牌厂家', 10),
    ('COMPLIANCE', '限制性条件 串通投标判定', 20),
    ('COMPLIANCE', '失信投标人限制 严重违法失信记录', 30),
    ('COMPLIANCE', '信用信息相关规定', 40),

    ('SCORING', '评标办法 评分标准 打分办法', 10),
    ('SCORING', '价格评分标准 报价得分 价格分计算公式', 20),
    ('SCORING', '技术评分标准 技术方案 施工组织设计', 30),
    ('SCORING', '商务评分标准', 40),
    ('SCORING', '项目管理机构 人员配备 职称业绩评分', 50),
    ('SCORING', '售后服务 培训方案 质保期评分', 60),
    ('SCORING', '加分项 扣分项 低价加分 小型企业价格扣除', 70),
    ('SCORING', '节能环保 自主知识产权 技术创新加分', 80),
    ('SCORING', '质量安全 工期保障 应急预案评分', 90),
    ('SCORING', '评标委员会组成要求', 100),
    ('SCORING', '财务状况 信誉信用 履约能力评分', 110),

    ('GENERAL_SCAN', '招标文件格式要求相关规定', 10),
    ('GENERAL_SCAN', '商务条款相关规定', 20),
    ('GENERAL_SCAN', '技术方案相关规定', 30),
    ('GENERAL_SCAN', '人员资格相关规定', 40),
    ('GENERAL_SCAN', '证明材料相关规定', 50),
    ('GENERAL_SCAN', '评分评审相关规定', 60)
) AS v(code, query_text, sort_order)
INNER JOIN biz_review_dimension d ON d.code = v.code;

COMMENT ON TABLE biz_review_dimension IS '全局审查维度字典（平台级；检索词=V26优化短词版）';
