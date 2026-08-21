# 招投标后端 · API（招标解析）

> 本册范围：**独立招标文件上传解析**。  
> 用户上传一个或多个招标文件，后端自动解析基础信息、资格要求、评分标准、逻辑问题、错别字识别；不创建项目、不上传投标文件、不发起审查。  
> 文件 / 标包通用能力见：[文件与标包](./API-文件与标包.md)  
> 任务进度见：[任务进度 SSE](./API-任务进度SSE.md)  
> 账号 / 组织 / 项目见：[账号组织项目](./API-账号组织项目.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-08-20

## 通用约定（摘要）

- 统一响应：`{ code, message, data }`，成功 `code=0`。
- 鉴权：`Authorization: Bearer <accessToken>`。
- 本册业务接口角色：**仅 `TENDERER`**；平台管理员无招标方角色 → **403**。
- 跨组织资源一律 **40400**（不暴露是否存在）。
- 任务进度：复用 `GET /api/jobs/{jobId}` 短轮询，或 `GET /api/jobs/{jobId}/events` SSE。
- 文件解析：复用现有 INDEX；扫描版 PDF 无文本时可能失败，本期不做 OCR。

---

## 1. 能力总览

| 能力 | 说明 |
|------|------|
| 内部招标包 | 每次招标解析会创建一个内部 `TENDER` 包，落 `biz_package` |
| 上传文件 | 支持一个或多个 PDF / Word / Excel 招标文件；限制沿用文件模块 |
| INDEX | 上传后自动解析 / 切块 / 向量化 |
| TENDER_ANALYSIS | 招标解析异步任务；`biz_job.type=TENDER_ANALYSIS` |
| 基础信息 | 招标人、招标代理、预算金额、投标截止时间、项目简介、项目背景、项目概况 |
| 资格要求 | 沿用 `Q1` / `Q2` 编号 |
| 评分标准 | 沿用 `S1` / `S2` 编号 |
| 逻辑问题 | 全文 chunk 分批扫描后合并，沿用 `逻辑问题-1` / `逻辑问题-2` 编号 |
| 错别字识别 | 全文 chunk 分批扫描后合并，沿用 `错别字-1` / `错别字-2` 编号 |
| 查看原文片段 | 返回 evidence，前端可通过 chunk / page / quote 定位原文片段 |
| 重试链路追踪 | 重试解析生成新记录，并返回来源记录与首次创建时间 |
| 导出结果 | 成功解析后可导出 xlsx 结果文件 |

---

## 2. 数据状态

### 2.1 解析状态

| status | 说明 |
|--------|------|
| `BUILDING` | 解析中 |
| `SUCCESS` | 解析成功 |
| `FAILED` | 解析失败 |
| `OBSOLETE` | 已作废，历史列表和详情默认不可见 |

### 2.2 条目类型

| itemType | 说明 |
|----------|------|
| `QUALIFICATION` | 资格要求 |
| `SCORING` | 评分标准 |
| `LOGIC_ISSUE` | 逻辑问题 |
| `TYPO` | 错别字识别 |

---

## 3. 招标解析

### 3.1 创建解析任务

`POST /api/tender-analyses`

`Content-Type: multipart/form-data`

创建一次招标解析任务。后端会创建内部招标包、上传文件、为每个文件排队 INDEX，并创建 `TENDER_ANALYSIS` 总任务。

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 否 | 任务名称；不填默认取第一个上传文件的文件名 |
| `files` | file[] | 是 | 一个或多个招标文件；multipart 同名多 part |
| `fileTypes` | string[] | 是 | 每个文件的业务类型；与 `files` 按下标一一对应，长度必须一致，单项最长 128 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "analysisId": 1,
    "jobId": 101,
    "packageId": 50,
    "documentIds": [7, 8],
    "indexJobIds": [201, 202],
    "files": [
      {
        "documentId": 7,
        "indexJobId": 201,
        "fileName": "智慧园区一体化平台建设项目.docx",
        "fileType": "招标正文"
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| `analysisId` | 招标解析记录 ID，用于进入详情页 |
| `jobId` | `TENDER_ANALYSIS` 总任务 ID；后续解析进度查询用 |
| `packageId` | 本次解析内部招标包 ID |
| `documentIds` | 上传后的招标文件 ID 数组 |
| `indexJobIds` | 每个文件对应的 INDEX 子任务 ID |
| `files` | 单文件上传与入队结果 |
| `files[].fileType` | 前端传入并保存到 `biz_document.file_type` 的业务文件类型 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 未选择文件 / 文件为空 / 文件名无效 / 文件扩展名不支持 / 单文件超限 / 未传 `fileTypes` / `fileTypes` 数量与 `files` 不一致 / 文件类型为空或过长 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40900 | 超过每个标包最多文件数限制 |
| 50000 | 对象存储写入失败或任务创建失败 |

### 3.2 历史记录

`GET /api/tender-analyses?page=1&size=20&keyword=&status=`

查询当前登录招标方组织下的招标解析历史记录。按创建时间倒序返回。

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `keyword` | string | 否 | `""` | 按任务名称 / 招标人 / 招标代理模糊搜 |
| `status` | string | 否 | `""` | 解析状态过滤；空=全部，支持 `BUILDING` / `SUCCESS` / `FAILED` |
| `page` | number | 否 | 1 | 页码，从 1 起 |
| `size` | number | 否 | 20 | 每页条数，最大 100 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 1,
        "orgId": 2,
        "packageId": 50,
        "name": "智慧园区一体化平台建设项目.docx",
        "status": "SUCCESS",
        "jobId": 101,
        "documentIds": [7, 8],
        "fileCount": 2,
        "requirementCount": 12,
        "issueCount": 3,
        "tenderer": "上海智慧园区发展有限公司",
        "agency": "中建咨询（招标代理）",
        "budgetAmountText": "￥12,800,000.00（含税最高限价）",
        "bidDeadlineText": "2026-08-15 17:00",
        "bidDeadlineAt": "2026-08-15T09:00:00Z",
        "errorMessage": null,
        "createdBy": 10,
        "sourceAnalysisId": null,
        "originalCreatedAt": "2026-08-11T08:30:00Z",
        "createdAt": "2026-08-11T08:30:00Z",
        "updatedAt": "2026-08-11T08:35:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

| 字段 | 说明 |
|------|------|
| `documentIds` | 参与解析的招标文档 ID 数组 |
| `fileCount` | 由 `documentIds` 数组长度派生 |
| `requirementCount` | 招标要求数量；`QUALIFICATION` + `SCORING` 条目数 |
| `issueCount` | 发现问题数量；`LOGIC_ISSUE` + `TYPO` 条目数 |
| `status` | 解析状态：`BUILDING` 解析中 / `SUCCESS` 解析完成 / `FAILED` 解析失败 |
| `jobId` | 解析任务 ID；`BUILDING` 时可用于查询进度 |
| `sourceAnalysisId` | 重试解析来源记录 ID；首次创建为空 |
| `originalCreatedAt` | 首次创建时间；首次创建时等于 `createdAt`，重试解析记录继承来源记录的首次创建时间 |
| `createdAt` | 当前这次解析任务的创建时间；重试解析会生成新记录，因此该字段会变成本次重试时间 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | `status` 不在 `BUILDING` / `SUCCESS` / `FAILED` 范围内 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |

### 3.3 解析详情

`GET /api/tender-analyses/{analysisId}`

查询单条招标解析详情。跨组织或不存在 → `40400`。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1,
    "name": "智慧园区一体化平台建设项目.docx",
    "status": "SUCCESS",
    "jobId": 101,
    "packageId": 50,
    "documentIds": [7, 8],
    "fileCount": 2,
    "sourceAnalysisId": null,
    "originalCreatedAt": "2026-08-11T08:30:00Z",
    "createdAt": "2026-08-11T08:30:00Z",
    "updatedAt": "2026-08-11T08:35:00Z",
    "basicInfo": {
      "tenderer": "上海智慧园区发展有限公司",
      "agency": "中建咨询（招标代理）",
      "budgetAmountText": "￥12,800,000.00（含税最高限价）",
      "bidDeadlineText": "2026-08-15 17:00",
      "bidDeadlineAt": "2026-08-15T09:00:00Z",
      "projectIntro": "为提升园区数字化管理水平...",
      "projectBackground": "为落实城市数字化转型战略...",
      "projectOverview": "本项目建设智慧园区一体化平台...",
      "evidence": []
    },
    "qualificationItems": [
      {
        "id": 10,
        "analysisId": 1,
        "itemType": "QUALIFICATION",
        "requirementCode": "Q1",
        "title": "信息系统建设能力",
        "content": "投标人须具有有效的信息系统建设和服务能力等级证书。",
        "subType": "CERTIFICATE",
        "sourceExcerpt": "投标人须具有有效的信息系统建设和服务能力等级证书。",
        "pageNo": 8,
        "chunkIds": [9001],
        "evidence": [],
        "sortOrder": 0,
        "createdAt": "2026-08-11T08:35:00Z"
      }
    ],
    "scoringItems": [
      {
        "id": 20,
        "analysisId": 1,
        "itemType": "SCORING",
        "requirementCode": "S1",
        "title": "技术方案",
        "content": "根据技术方案完整性进行评分。",
        "subType": "TECH_SCORE",
        "sourceExcerpt": "根据技术方案完整性进行评分。",
        "pageNo": 15,
        "chunkIds": [9020],
        "evidence": [],
        "scoreTotal": 10,
        "scoreParts": [],
        "scoreSumMatches": true,
        "sortOrder": 0,
        "createdAt": "2026-08-11T08:35:00Z"
      }
    ],
    "logicIssueItems": [
      {
        "id": 30,
        "analysisId": 1,
        "itemType": "LOGIC_ISSUE",
        "requirementCode": "逻辑问题-1",
        "title": "业绩门槛与项目预算规模不匹配",
        "content": "项目预算金额为1280万元，但类似业绩要求达到5000万元，门槛明显高于项目规模。建议补充设置依据，或调整为与项目规模相称的合理区间。",
        "subType": "THRESHOLD_INCONSISTENCY",
        "severity": "MEDIUM",
        "severityLabel": "中风险",
        "issueType": "THRESHOLD_INCONSISTENCY",
        "issueTypeLabel": "门槛条件逻辑",
        "suggestion": "建议补充设置依据，或调整为与项目规模相称的合理区间。",
        "sourceExcerpt": "预算金额为人民币 1280 万元；近三年需具有 5000 万元以上类似项目业绩",
        "pageNo": 5,
        "chunkIds": [9001, 9010],
        "evidence": [
          {
            "chunkId": 9001,
            "pageNo": 1,
            "quote": "预算金额为人民币 1280 万元",
            "severity": "MEDIUM",
            "issueType": "THRESHOLD_INCONSISTENCY",
            "suggestion": "建议补充设置依据，或调整为与项目规模相称的合理区间。"
          },
          {
            "chunkId": 9010,
            "pageNo": 5,
            "quote": "近三年需具有 5000 万元以上类似项目业绩",
            "severity": "MEDIUM",
            "issueType": "THRESHOLD_INCONSISTENCY",
            "suggestion": "建议补充设置依据，或调整为与项目规模相称的合理区间。"
          }
        ],
        "sortOrder": 0,
        "createdAt": "2026-08-11T08:35:00Z"
      }
    ],
    "typoItems": [
      {
        "id": 40,
        "analysisId": 1,
        "itemType": "TYPO",
        "requirementCode": "错别字-1",
        "title": "“按装调试”应为“安装调试”",
        "content": "招标范围条款存在错别字。建议：更正为“安装调试”。",
        "subType": null,
        "severity": "LOW",
        "severityLabel": "低风险",
        "issueType": "TYPO",
        "issueTypeLabel": "错别字",
        "suggestion": "更正为“安装调试”。",
        "wrongText": "按装调试",
        "suggestedText": "安装调试",
        "confidence": "HIGH",
        "confidenceLabel": "高置信",
        "sourceExcerpt": "招标范围条款“按装调试”存在错别字。",
        "pageNo": 12,
        "chunkIds": [9015],
        "evidence": [
          {
            "chunkId": 9015,
            "pageNo": 12,
            "quote": "招标范围条款“按装调试”存在错别字。",
            "wrongText": "按装调试",
            "suggestedText": "安装调试",
            "confidence": "HIGH",
            "suggestion": "更正为“安装调试”。"
          }
        ],
        "sortOrder": 0,
        "createdAt": "2026-08-11T08:35:00Z"
      }
    ],
    "basicInfoRawJson": "{}",
    "checklistRawJson": "{}"
  }
}
```

| 字段 | 说明 |
|------|------|
| `basicInfo.evidence` | 基础信息证据数组，用于“查看原文片段” |
| `qualificationItems` | 资格要求列表，按 `sortOrder/id` 升序 |
| `scoringItems` | 评分标准列表，按 `sortOrder/id` 升序 |
| `logicIssueItems` | 逻辑问题列表，按 `sortOrder/id` 升序；由全文 chunk 分批扫描候选合并生成 |
| `typoItems` | 错别字识别列表，按 `sortOrder/id` 升序；由全文 chunk 分批扫描候选合并生成 |
| `items[].chunkIds` | 来源 chunk ID 数组 |
| `items[].evidence` | 条目原文证据数组，用于“查看原文片段” |
| `items[].severity` | 风险等级：`HIGH` / `MEDIUM` / `LOW`；逻辑问题从 evidence 派生，错别字默认 `LOW` |
| `items[].severityLabel` | 风险等级中文：`高风险` / `中风险` / `低风险` |
| `items[].issueType` | 问题类型；逻辑问题为 LLM 输出的类型，错别字为 `TYPO` |
| `items[].issueTypeLabel` | 问题类型中文，如 `资格与废标逻辑` / `门槛条件逻辑` / `错别字` |
| `items[].suggestion` | 修改建议；从 evidence 中的 `suggestion` 派生 |
| `items[].wrongText` | 错别字原文，仅 `TYPO` 返回 |
| `items[].suggestedText` | 建议修改文字，仅 `TYPO` 返回 |
| `items[].confidence` | 错别字置信度：`HIGH` / `MEDIUM` / `LOW`，仅 `TYPO` 返回 |
| `items[].confidenceLabel` | 错别字置信度中文：`高置信` / `中置信` / `低置信`，仅 `TYPO` 返回 |
| `basicInfoRawJson` | 基础信息抽取 LLM 原始输出，仅详情返回 |
| `checklistRawJson` | 资格/评分抽取 LLM 原始输出，仅详情返回 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 解析记录不存在或不属于本组织 |

### 3.4 删除历史记录

`DELETE /api/tender-analyses/{analysisId}`

逻辑删除一条招标解析历史记录。删除后历史列表不再返回，详情查询返回 `40400`。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": null
}
```

**删除策略**

- 仅将 `biz_tender_analysis.status` 标记为 `OBSOLETE`。
- 不删除内部 `biz_package`。
- 不删除源文件 `biz_document`。
- 不删除 chunk/vector 索引产物。

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 解析记录不存在、不属于本组织或已删除 |

### 3.5 重试解析

`POST /api/tender-analyses/{analysisId}/rerun`

重试一条招标解析记录。后端会将旧记录标记为 `OBSOLETE`，复用原包与原文件重新创建一条 `BUILDING` 记录，并重新创建 `TENDER_ANALYSIS` 总任务。

新记录的 `createdAt` 表示本次重试任务创建时间；列表 / 详情会额外返回 `sourceAnalysisId` 和 `originalCreatedAt`，用于前端展示来源记录与首次创建时间。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "analysisId": 2,
    "jobId": 102,
    "packageId": 50,
    "documentIds": [7, 8],
    "indexJobIds": [],
    "files": []
  }
}
```

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 原记录缺少文档信息 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 解析记录不存在、不属于本组织 |
| 40900 | 原记录仍处于解析中 |

### 3.6 导出解析结果

`GET /api/tender-analyses/{analysisId}/export`

导出一条招标解析结果。仅 `SUCCESS` 状态可导出，响应为 xlsx 二进制附件，不使用统一 JSON 包装。

**响应头**

```http
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename*=UTF-8''...xlsx
```

**文件名**

`{解析名称}-招标解析结果.xlsx`

**工作表**

| sheet | 内容 |
|------|------|
| 概览 | 解析 ID、任务名称、状态、文件数量、资格要求数量、评分标准数量、问题数量、创建 / 更新时间 |
| 源文件 | 文档 ID、标包 ID、文件名、扩展名、索引状态 |
| 基础信息 | 招标人、招标代理、预算金额、投标截止时间、项目简介、项目背景、项目概况及 evidence |
| 资格要求 | 编号、标题、内容、分类、页码、chunkIds、原文片段 |
| 评分标准 | 编号、标题、内容、满分、分项合计一致性、分类、页码、chunkIds、原文片段 |
| 逻辑问题 | 编号、标题、内容、严重程度、问题类型、修改建议、页码、chunkIds、原文片段 |
| 错别字 | 编号、错误文本、建议文本、置信度、修改建议、页码、chunkIds、原文片段 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 解析记录不存在、不属于本组织或已删除 |
| 40900 | 解析未成功，不能导出 |

---

## 隐藏逻辑（本册必读）

### 内部招标包

- 每次解析会创建一个内部 `TENDER` 包，用于复用现有文件上传、索引、对象存储、chunk/vector 能力。
- 内部包不绑定项目，`biz_package.project_id = null`。
- 任务名称不传时，默认取第一个上传文件的文件名。
- 创建解析时，前端必须传 `fileTypes`；后端按 `files[i] -> fileTypes[i]` 保存到 `biz_document.file_type`。

### 任务与进度

- 创建解析任务后，后端先为文件创建 INDEX 子任务，再由 `TENDER_ANALYSIS` 任务汇总执行。
- 进度查询复用现有 job 接口，不新增专用进度接口。
- 任一文件 INDEX 失败，本次招标解析整体失败。
- 当前 `TENDER_ANALYSIS` Worker 已接入完整解析主流程：全部文件索引成功后，读取本次文档 chunk，调用 LLM 抽取基础信息、资格要求、评分标准，并保存基础信息 evidence 与条目 evidence。
- Worker 配置：`bidding.tender-analysis.worker-enabled`、`bidding.tender-analysis.poll-ms`、`bidding.tender-analysis.worker-id`。

### 抽取结果

- `预算金额` 不拆分为预算金额 / 最高限价，本期统一返回 `budgetAmountText`。
- `投标截止时间` 返回原文 `bidDeadlineText`；`bidDeadlineAt` 只接受 LLM 返回的 ISO-8601 UTC 时间，无法解析则为 `null`。
- 资格要求和评分标准沿用现有 `Q1` / `S1` 编号规则。
- 逻辑问题沿用 `逻辑问题-1` / `逻辑问题-2` 编号规则。
- 错别字识别沿用 `错别字-1` / `错别字-2` 编号规则。
- 前端“查看原文片段”依赖 evidence 中的 chunk / page / quote 信息；`pageNo` 由后端根据 `chunkId` 从文档切块表回填，不使用 LLM 返回的页码。
- PDF 切块页码为真实 PDF 页序号；DOCX 无法稳定取得 Word 自动分页，`pageNo` 只能代表后端抽取到的逻辑页，要求精确页码时建议上传 PDF。
- 资格要求 / 评分标准复用建清单拆分链路：资格和评分分开抽取，再生成条目检索词与招标侧原文 evidence；本接口不发起投标文件审查。
- 逻辑问题 / 错别字识别不复用建清单召回链路；文件 INDEX 成功后读取本次文档 chunk，按批次交给 LLM 扫描候选问题，再由后端合并去重后返回。
- 本期不做法律风险识别，不接入法律库。
- 导出解析结果复用详情聚合数据与内部包文件列表，只允许 `SUCCESS` 记录导出。
- 重试解析不会改旧记录 `createdAt`；旧记录标记为 `OBSOLETE`，新记录使用新的 `createdAt`，并通过 `sourceAnalysisId` / `originalCreatedAt` 关联首次创建信息。

## 更新记录

| 日期 | 说明 |
|------|------|
| 2026-08-21 | evidence 页码统一由后端根据 `chunkId` 从文档切块回填，避免 LLM 将 chunkId 误写为 pageNo |
