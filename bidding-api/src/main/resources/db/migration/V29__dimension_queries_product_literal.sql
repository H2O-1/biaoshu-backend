-- 维度分类不动；仅替换检索词为产品原文（便于与 V26 优化短词版对比）
-- 换回优化版：执行 db/seed/restore_dimension_queries_optimized.sql（或另加迁移）

DELETE FROM biz_review_dimension_query;

INSERT INTO biz_review_dimension_query (dimension_id, query_text, sort_order)
SELECT d.id, v.query_text, v.sort_order
FROM (VALUES
    -- 资格要求类
    ('QUALIFICATION', '找出所有关于投标人资格的规定', 10),
    ('QUALIFICATION', '投标人资格条件是什么', 20),
    ('QUALIFICATION', '投标人必须具备哪些资质', 30),
    ('QUALIFICATION', '投标人注册资本要求', 40),
    ('QUALIFICATION', '投标人业绩要求', 50),
    ('QUALIFICATION', '投标人需要提供哪些业绩证明', 60),
    ('QUALIFICATION', '投标人近三年内不得有重大违法记录', 70),
    ('QUALIFICATION', '联合体投标规定', 80),
    ('QUALIFICATION', '联合体投标有哪些规定', 90),

    -- 资质证书类
    ('CERTIFICATE', '找出所有关于企业资质证书的规定', 10),
    ('CERTIFICATE', '招标文件要求提供哪些资质证书', 20),
    ('CERTIFICATE', '资质证书有效期要求', 30),
    ('CERTIFICATE', '资质证书需要提供原件还是扫描件', 40),
    ('CERTIFICATE', '检测报告要求', 50),
    ('CERTIFICATE', '是否要求国家级检测报告', 60),
    ('CERTIFICATE', '安全生产许可证要求', 70),
    ('CERTIFICATE', '营业执照要求', 80),

    -- 人员要求类
    ('PERSONNEL', '找出所有关于项目负责人的规定', 10),
    ('PERSONNEL', '项目负责人资格要求', 20),
    ('PERSONNEL', '项目负责人需要什么资格', 30),
    ('PERSONNEL', '项目经理证书要求', 40),
    ('PERSONNEL', '项目经理需要什么证书', 50),
    ('PERSONNEL', '技术负责人资格要求', 60),
    ('PERSONNEL', '安全员配备要求', 70),
    ('PERSONNEL', '项目团队成员资格要求', 80),
    ('PERSONNEL', '人员社保缴纳要求', 90),
    ('PERSONNEL', '人员社保缴纳证明有什么要求', 100),
    ('PERSONNEL', '建造师注册要求', 110),
    ('PERSONNEL', '人员业绩要求', 120),
    ('PERSONNEL', '售后服务团队要求', 130),
    ('PERSONNEL', '监理要求', 140),

    -- 废标否决类
    ('REJECT', '找出所有关于废标的规定', 10),
    ('REJECT', '哪些情况会导致废标', 20),
    ('REJECT', '投标无效的条件是什么', 30),
    ('REJECT', '招标文件规定哪些情形按废标处理', 40),
    ('REJECT', '哪些情形会被否决投标', 50),
    ('REJECT', '资格审查不合格的情形有哪些', 60),
    ('REJECT', '符合性审查不合格的情形有哪些', 70),
    ('REJECT', '重大偏差和细微偏差如何认定', 80),
    ('REJECT', '取消投标资格的情形', 90),
    ('REJECT', '★☆●■▲号条款不满足有什么后果', 100),

    -- 商务条款类
    ('COMMERCIAL', '找出所有关于投标保证金的规定', 10),
    ('COMMERCIAL', '投标保证金有什么要求', 20),
    ('COMMERCIAL', '投标保证金金额是多少', 30),
    ('COMMERCIAL', '投标保证金缴纳方式是什么', 40),
    ('COMMERCIAL', '投标有效期是多久', 50),
    ('COMMERCIAL', '工期质保期和付款方式要求', 60),
    ('COMMERCIAL', '工期要求是多少天', 70),
    ('COMMERCIAL', '质保期是多久', 80),
    ('COMMERCIAL', '付款方式是怎么规定的', 90),
    ('COMMERCIAL', '合同履行期限是多久', 100),
    ('COMMERCIAL', '违约责任规定', 110),
    ('COMMERCIAL', '商务偏离表', 120),
    ('COMMERCIAL', '点对点应答内容', 130),

    -- 技术要求类
    ('TECHNICAL', '找出所有关于技术参数的规定', 10),
    ('TECHNICAL', '招标文件的技术参数要求是什么', 20),
    ('TECHNICAL', '实质性技术参数有哪些', 30),
    ('TECHNICAL', '技术偏离有什么规定', 40),
    ('TECHNICAL', '技术方案编制要求', 50),
    ('TECHNICAL', '售后服务要求', 60),
    ('TECHNICAL', '技术培训要求', 70),
    ('TECHNICAL', '关键技术要求是什么', 80),
    ('TECHNICAL', '设备配置要求', 90),
    ('TECHNICAL', '验收条款是什么', 100),
    ('TECHNICAL', '技术偏离表', 110),
    ('TECHNICAL', '点对点应答内容', 120),

    -- 报价要求类
    ('PRICE', '找出所有关于报价的规定', 10),
    ('PRICE', '报价编制有什么要求', 20),
    ('PRICE', '投标报价有效期是多久', 30),
    ('PRICE', '报价金额需要保留几位小数', 40),
    ('PRICE', '投标函总价有什么要求', 50),
    ('PRICE', '分项报价表填写要求', 60),
    ('PRICE', '暂列金额规费税金要求', 70),
    ('PRICE', '下浮率折扣率要求', 80),
    ('PRICE', '找出所有关于下浮率折扣率的规定', 90),
    ('PRICE', '当前报价税率', 100),
    ('PRICE', '优惠政策内容', 110),

    -- 格式封装类
    ('FORMAT', '投标文件格式要求', 10),
    ('FORMAT', '标书装订打印和页码要求', 20),
    ('FORMAT', '标书装订有什么要求', 30),
    ('FORMAT', '标书打印有什么要求', 40),
    ('FORMAT', '页码设置有什么要求', 50),
    ('FORMAT', '封面和脊背有什么要求', 60),
    ('FORMAT', '签字盖章有什么要求', 70),
    ('FORMAT', '骑缝章要求', 80),
    ('FORMAT', '密封封装要求', 90),
    ('FORMAT', '开标一览表需要单独封装吗', 100),
    ('FORMAT', '正副本需要几份', 110),

    -- 证明材料类
    ('EVIDENCE', '必须提交哪些证明材料', 10),
    ('EVIDENCE', '业绩证明材料要求', 20),
    ('EVIDENCE', '社保缴纳证明有什么要求', 30),
    ('EVIDENCE', '纳税证明要求', 40),
    ('EVIDENCE', '财务审计报告要求', 50),
    ('EVIDENCE', '法定代表人身份证明要求', 60),
    ('EVIDENCE', '授权委托书有什么要求', 70),
    ('EVIDENCE', '中小企业声明函要求', 80),
    ('EVIDENCE', '联合体还是非联合体投标的声明要求', 90),
    ('EVIDENCE', '小微企业声明', 100),
    ('EVIDENCE', '投标单位下属单位或集团属性声明', 110),

    -- 电子标流程类
    ('E_BID', '电子标上传有什么要求', 10),
    ('E_BID', '电子标解密有什么要求', 20),
    ('E_BID', '投标截止时间是什么时候', 30),
    ('E_BID', '开标时间和地点在哪里', 40),
    ('E_BID', '投标文件递交方式', 50),
    ('E_BID', 'CA证书和电子签章要求', 60),
    ('E_BID', '找出所有关于电子标的规定', 70),

    -- 合规风险类
    ('COMPLIANCE', '招标文件中是否有排他性条款', 10),
    ('COMPLIANCE', '找出所有歧视性条款', 20),
    ('COMPLIANCE', '是否指定品牌或厂家', 30),
    ('COMPLIANCE', '是否有限制性条件', 40),
    ('COMPLIANCE', '串通投标如何判定', 50),
    ('COMPLIANCE', '失信投标人限制要求', 60),
    ('COMPLIANCE', '政府采购严重违法失信行为记录要求', 70),
    ('COMPLIANCE', '找出所有关于信用信息的规定', 80),

    -- 评分评审类
    ('SCORING', '评分标准和打分办法是什么', 10),
    ('SCORING', '评标办法', 20),
    ('SCORING', '技术评分标准是什么', 30),
    ('SCORING', '商务评分标准是什么', 40),
    ('SCORING', '价格评分标准', 50),
    ('SCORING', '加分项和扣分项', 60),
    ('SCORING', '找出所有加分项和扣分项的规定', 70),
    ('SCORING', '评标委员会如何组成', 80),
    ('SCORING', '评标委员会组成要求', 90),
    ('SCORING', '技术方案评分项', 100),
    ('SCORING', '施工组织设计评分标准', 110),
    ('SCORING', '项目管理机构评分标准', 120),
    ('SCORING', '业绩评分标准', 130),
    ('SCORING', '人员配备评分标准', 140),
    ('SCORING', '售后服务评分标准', 150),
    ('SCORING', '培训方案评分标准', 160),
    ('SCORING', '质保期评分标准', 170),
    ('SCORING', '投标报价评分方法', 180),
    ('SCORING', '价格分计算公式', 190),
    ('SCORING', '低价加分规则', 200),
    ('SCORING', '小型企业价格扣除', 210),
    ('SCORING', '节能环保产品加分', 220),
    ('SCORING', '自主知识产权加分', 230),
    ('SCORING', '业绩数量评分规则', 240),
    ('SCORING', '项目经理业绩评分', 250),
    ('SCORING', '技术人员职称评分', 260),
    ('SCORING', '设备配置评分标准', 270),
    ('SCORING', '质量保障措施评分', 280),
    ('SCORING', '安全保障措施评分', 290),
    ('SCORING', '工期保障措施评分', 300),
    ('SCORING', '应急预案评分标准', 310),
    ('SCORING', '技术创新加分项', 320),
    ('SCORING', '合理化建议加分', 330),
    ('SCORING', '财务状况评分标准', 340),
    ('SCORING', '信誉信用评分标准', 350),
    ('SCORING', '履约能力评分标准', 360),

    -- 通用扫描类（本次产品清单未改；保留模版原 6 条，分类仍在）
    ('GENERAL_SCAN', '找出所有关于格式要求的规定', 10),
    ('GENERAL_SCAN', '找出所有关于商务条款的规定', 20),
    ('GENERAL_SCAN', '找出所有关于技术方案的规定', 30),
    ('GENERAL_SCAN', '找出所有关于人员资格的规定', 40),
    ('GENERAL_SCAN', '找出所有关于证明材料的规定', 50),
    ('GENERAL_SCAN', '找出所有关于评分评审的规定', 60)
) AS v(code, query_text, sort_order)
INNER JOIN biz_review_dimension d ON d.code = v.code;

COMMENT ON TABLE biz_review_dimension IS '全局审查维度字典（平台级；检索词=产品RAG原文 V29）';
