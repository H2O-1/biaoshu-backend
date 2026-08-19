# 招投标后端 · API（投标模拟审核）

> 本册范围：**独立投标模拟审核**。  
> 用户上传一个招标包和一个投标包，每个包可包含多个文件；后端解析招标文件中的资格要求和评分标准，再用投标文件进行模拟响应审查，同时只针对投标文件识别逻辑问题和错别字。  
> 文件 / 标包通用能力见：[文件与标包](./API-文件与标包.md)  
> 招标解析见：[招标解析](./API-招标解析.md)  
> 任务进度见：[任务进度 SSE](./API-任务进度SSE.md)  
> 账号 / 组织 / 项目见：[账号组织项目](./API-账号组织项目.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-08-18

## 通用约定（摘要）

- 统一响应：`{ code, message, data }`，成功 `code=0`。
- 鉴权：`Authorization: Bearer <accessToken>`。
- 本册业务接口角色：仅 `BIDDER` 可访问。
- 跨组织资源一律 **40400**（不暴露是否存在）。
- 任务进度：复用 `GET /api/jobs/{jobId}` 短轮询，或 `GET /api/jobs/{jobId}/events` SSE；`BID_SIMULATION_AUDIT` 任务的 `JobSummary.auditId` 为模拟审核记录 ID。
- 文件解析：复用现有 INDEX；扫描版 PDF 无文本时可能失败，本期不做 OCR。
- 本册为接口文档；当前已实现创建任务、历史列表、详情查询、删除、重新审核、导出结果接口。

---

## 1. 能力总览

| 能力 | 说明 |
|------|------|
| 内部招标包 | 每次模拟审核会创建一个内部 `TENDER` 包，落 `biz_package` |
| 内部投标包 | 每次模拟审核会创建一个内部 `BID` 包，落 `biz_package` |
| 上传文件 | 招标包和投标包均支持多个文件；限制沿用文件模块 |
| INDEX | 上传后自动解析 / 切块 / 向量化 |
| BID_SIMULATION_AUDIT | 投标模拟审核异步任务；`biz_job.type=BID_SIMULATION_AUDIT` |
| 资格要求响应 | 招标文件抽取要求，投标文件逐项响应 |
| 评分项响应 | 招标文件抽取评分项，投标文件逐项给出建议得分 |
| 逻辑问题 | 只扫描投标文件全文 chunk，沿用 `逻辑问题-1` / `逻辑问题-2` 编号 |
| 错别字识别 | 只扫描投标文件全文 chunk，沿用 `错别字-1` / `错别字-2` 编号 |
| 查看原文片段 | 返回 evidence，前端可通过 chunk / page / quote 定位原文片段 |
| 导出结果 | 成功审核后可导出 xlsx 结果文件 |

---

## 2. 数据状态

### 2.1 模拟审核状态

| status | 说明 |
|--------|------|
| `BUILDING` | 审核中 |
| `SUCCESS` | 审核成功 |
| `FAILED` | 审核失败 |
| `OBSOLETE` | 已作废，历史列表和详情默认不可见 |

### 2.2 条目类型

| itemType | 说明 |
|----------|------|
| `QUALIFICATION` | 资格要求响应结果 |
| `SCORING` | 评分项响应结果 |
| `LOGIC_ISSUE` | 逻辑问题 |
| `TYPO` | 错别字识别 |

### 2.3 资格要求结果

| result | 说明 |
|--------|------|
| `PASS` | 符合 |
| `FAIL` | 不符合 |
| `MANUAL` | 待人工确认 |

---

## 3. 投标模拟审核

### 3.1 创建模拟审核任务

`POST /api/bid-simulation-audits`

`Content-Type: multipart/form-data`

创建一次投标模拟审核任务。后端会创建内部招标包和内部投标包，上传两组文件，为每个文件排队 INDEX，并创建 `BID_SIMULATION_AUDIT` 总任务。

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 否 | 任务名称；不填默认取第一个投标文件的文件名 |
| `tenderFiles` | file[] | 是 | 一个或多个招标文件；multipart 同名多 part |
| `tenderFileTypes` | string[] | 是 | 每个招标文件的业务类型；与 `tenderFiles` 按下标一一对应 |
| `bidFiles` | file[] | 是 | 一个或多个投标文件；multipart 同名多 part |
| `bidFileTypes` | string[] | 是 | 每个投标文件的业务类型；与 `bidFiles` 按下标一一对应 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "auditId": 1,
    "jobId": 101,
    "tenderPackageId": 50,
    "bidPackageId": 51,
    "tenderDocumentIds": [7, 8],
    "bidDocumentIds": [9, 10],
    "indexJobIds": [201, 202, 203, 204],
    "tenderFiles": [
      {
        "documentId": 7,
        "indexJobId": 201,
        "fileName": "招标文件.docx",
        "fileType": "招标正文"
      }
    ],
    "bidFiles": [
      {
        "documentId": 9,
        "indexJobId": 203,
        "fileName": "投标文件.pdf",
        "fileType": "投标正文"
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| `auditId` | 投标模拟审核记录 ID，用于进入详情页 |
| `jobId` | `BID_SIMULATION_AUDIT` 总任务 ID；后续进度查询用 |
| `tenderPackageId` | 本次任务内部招标包 ID |
| `bidPackageId` | 本次任务内部投标包 ID |
| `tenderDocumentIds` | 上传后的招标文件 ID 数组 |
| `bidDocumentIds` | 上传后的投标文件 ID 数组 |
| `indexJobIds` | 每个文件对应的 INDEX 子任务 ID |
| `tenderFiles[].fileType` | 前端传入并保存到 `biz_document.file_type` 的招标文件业务类型 |
| `bidFiles[].fileType` | 前端传入并保存到 `biz_document.file_type` 的投标文件业务类型 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 未选择招标文件 / 未选择投标文件 / 文件为空 / 文件名无效 / 文件扩展名不支持 / 单文件超限 / 未传文件类型 / 文件类型数量与文件数量不一致 / 文件类型为空或过长 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户无权创建模拟审核 |
| 40900 | 超过每个标包最多文件数限制 |
| 50000 | 对象存储写入失败或任务创建失败 |

### 3.2 历史记录

`GET /api/bid-simulation-audits?page=1&size=20&keyword=&status=`

查询当前登录投标方组织下的投标模拟审核历史记录。按创建时间倒序返回。

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `keyword` | string | 否 | `""` | 按任务名称模糊搜 |
| `status` | string | 否 | `""` | 审核状态过滤；空=全部，支持 `BUILDING` / `SUCCESS` / `FAILED` |
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
        "name": "投标文件.pdf",
        "status": "SUCCESS",
        "jobId": 101,
        "tenderPackageId": 50,
        "bidPackageId": 51,
        "tenderDocumentIds": [7, 8],
        "bidDocumentIds": [9, 10],
        "tenderFileCount": 2,
        "bidFileCount": 2,
        "qualificationTotal": 10,
        "qualificationPassCount": 7,
        "qualificationFailCount": 2,
        "qualificationManualCount": 1,
        "qualificationPassRate": "70.00%",
        "scoreSuggested": 16,
        "scoreTotal": 23,
        "logicIssueCount": 2,
        "typoCount": 3,
        "errorMessage": null,
        "createdAt": "2026-08-14T08:30:00Z",
        "updatedAt": "2026-08-14T08:35:00Z"
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
| `tenderFileCount` | 由 `tenderDocumentIds` 数组长度派生 |
| `bidFileCount` | 由 `bidDocumentIds` 数组长度派生 |
| `qualificationPassRate` | 资格要求通过率；建议用符合数量 / 资格要求总数计算 |
| `scoreSuggested` | 评分项建议总分 |
| `scoreTotal` | 评分项满分 |
| `status` | 审核状态：`BUILDING` 审核中 / `SUCCESS` 审核完成 / `FAILED` 审核失败 |
| `jobId` | 模拟审核任务 ID；`BUILDING` 时可用于查询进度 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | `status` 不在 `BUILDING` / `SUCCESS` / `FAILED` 范围内 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户无权查看模拟审核 |

### 3.3 模拟审核详情

`GET /api/bid-simulation-audits/{auditId}`

查询单条投标模拟审核详情。跨组织或不存在 → `40400`。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1,
    "name": "投标文件.pdf",
    "status": "SUCCESS",
    "jobId": 101,
    "tenderPackageId": 50,
    "bidPackageId": 51,
    "tenderDocumentIds": [7, 8],
    "bidDocumentIds": [9, 10],
    "basicInfo": {
      "tenderer": "上海智慧园区发展有限公司",
      "agency": "中建咨询（招标代理）",
      "budgetAmountText": "￥12,800,000.00（含税最高限价）",
      "bidDeadlineText": "2026-08-15 17:00",
      "bidDeadlineAt": "2026-08-15T09:00:00Z",
      "evidence": [
        {
          "field": "tenderer",
          "value": "上海智慧园区发展有限公司",
          "quote": "招标人：上海智慧园区发展有限公司",
          "chunkId": 9001,
          "pageNo": 1
        }
      ]
    },
    "summary": {
      "qualificationTotal": 10,
      "qualificationPassCount": 7,
      "qualificationFailCount": 2,
      "qualificationManualCount": 1,
      "qualificationPassRate": "70.00%",
      "scoreSuggested": 16,
      "scoreTotal": 23,
      "logicIssueCount": 2,
      "typoCount": 3
    },
    "qualificationItems": [
      {
        "id": 10,
        "auditId": 1,
        "itemType": "QUALIFICATION",
        "requirementCode": "Q1",
        "title": "信息系统建设能力",
        "content": "投标人须具有有效的信息系统建设和服务能力等级证书。",
        "subType": "CERTIFICATE",
        "subTypeLabel": "资质证书",
        "result": "PASS",
        "resultLabel": "符合",
        "reason": "投标文件提供了有效证书扫描件，证书名称与要求一致。",
        "sourceExcerpt": "投标文件第 12 页提供信息系统建设和服务能力等级证书。",
        "pageNo": 12,
        "chunkIds": [9101],
        "evidence": [],
        "sortOrder": 0,
        "createdAt": "2026-08-14T08:35:00Z"
      }
    ],
    "scoringItems": [
      {
        "id": 20,
        "auditId": 1,
        "itemType": "SCORING",
        "requirementCode": "S1",
        "title": "技术方案",
        "content": "根据技术方案完整性进行评分。",
        "subType": "TECH_SCORE",
        "subTypeLabel": "技术评分",
        "suggestedScore": 8,
        "fullScore": 10,
        "reason": "方案覆盖总体架构、实施计划和运维保障，但缺少详细风险预案。",
        "sourceExcerpt": "投标文件提供总体架构、实施计划和运维保障内容。",
        "pageNo": 28,
        "chunkIds": [9201, 9202],
        "evidence": [],
        "sortOrder": 0,
        "createdAt": "2026-08-14T08:35:00Z"
      }
    ],
    "logicIssueItems": [
      {
        "id": 30,
        "auditId": 1,
        "itemType": "LOGIC_ISSUE",
        "requirementCode": "逻辑问题-1",
        "title": "服务周期前后不一致",
        "content": "投标文件正文写服务周期为12个月，实施计划中写服务周期为10个月，存在前后不一致。",
        "severity": "MEDIUM",
        "severityLabel": "中风险",
        "issueType": "TERM_INCONSISTENCY",
        "issueTypeLabel": "期限逻辑",
        "suggestion": "建议统一服务周期表述，并确认是否为12个月。",
        "sourceExcerpt": "服务周期为12个月；服务周期为10个月",
        "pageNo": 18,
        "chunkIds": [9301, 9340],
        "evidence": [],
        "sortOrder": 0,
        "createdAt": "2026-08-14T08:35:00Z"
      }
    ],
    "typoItems": [
      {
        "id": 40,
        "auditId": 1,
        "itemType": "TYPO",
        "requirementCode": "错别字-1",
        "title": "“按装调试”应为“安装调试”",
        "content": "投标文件存在疑似错别字。建议：更正为“安装调试”。",
        "severity": "LOW",
        "severityLabel": "低风险",
        "issueType": "TYPO",
        "issueTypeLabel": "错别字",
        "suggestion": "更正为“安装调试”。",
        "wrongText": "按装调试",
        "suggestedText": "安装调试",
        "confidence": "HIGH",
        "confidenceLabel": "高置信",
        "sourceExcerpt": "完成系统按装调试后进入试运行。",
        "pageNo": 36,
        "chunkIds": [9401],
        "evidence": [],
        "sortOrder": 0,
        "createdAt": "2026-08-14T08:35:00Z"
      }
    ],
    "tenderDocuments": [
      {
        "documentId": 7,
        "packageId": 50,
        "fileName": "招标文件.docx",
        "fileExt": "docx",
        "indexStatus": "SUCCESS"
      }
    ],
    "bidDocuments": [
      {
        "documentId": 9,
        "packageId": 51,
        "fileName": "投标文件.pdf",
        "fileExt": "pdf",
        "indexStatus": "SUCCESS"
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| `basicInfo` | 招标文件基础信息，用于详情页头部展示 |
| `basicInfo.tenderer` | 招标人 |
| `basicInfo.agency` | 招标代理 |
| `basicInfo.budgetAmountText` | 预算金额原文，不拆分最高限价 |
| `basicInfo.bidDeadlineText` | 投标截止时间原文 |
| `basicInfo.bidDeadlineAt` | 投标截止时间标准时间；无法可靠解析时为 `null` |
| `basicInfo.evidence` | 基础信息证据数组，用于“查看原文片段” |
| `summary` | 汇总指标，用于结果页顶部统计 |
| `qualificationItems` | 资格要求响应结果，按 `sortOrder/id` 升序 |
| `scoringItems` | 评分项响应结果，按 `sortOrder/id` 升序 |
| `qualificationItems[].subType` / `scoringItems[].subType` | 子类 code，如 `CERTIFICATE` / `REJECT` / `TECH` / `TECH_SCORE` |
| `qualificationItems[].subTypeLabel` / `scoringItems[].subTypeLabel` | 子类中文标签，如“资质证书”“废标否决”“技术要求”“技术评分” |
| `scoringItems[].suggestedScore` | 建议得分，沿用一键审查评分项结果 |
| `scoringItems[].fullScore` | 满分，沿用一键审查评分项结果 |
| `logicIssueItems` | 投标文件逻辑问题列表，按 `sortOrder/id` 升序 |
| `typoItems` | 投标文件错别字识别列表，按 `sortOrder/id` 升序 |
| `items[].chunkIds` | 来源 chunk ID 数组 |
| `items[].evidence` | 条目原文证据数组，用于“查看原文片段” |
| `tenderDocuments` | 本次任务招标文件列表；每项含 `documentId/packageId/fileName/fileExt/indexStatus` |
| `bidDocuments` | 本次任务投标文件列表；每项含 `documentId/packageId/fileName/fileExt/indexStatus` |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户无权查看模拟审核 |
| 40400 | 审核记录不存在或不属于本组织 |

### 3.4 删除历史记录

`DELETE /api/bid-simulation-audits/{auditId}`

逻辑删除一条投标模拟审核历史记录。删除后历史列表不再返回，详情查询返回 `40400`。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": null
}
```

**删除策略**

- 本期建议仅将 `biz_bid_simulation_audit.status` 标记为 `OBSOLETE`。
- 是否删除内部招标包、内部投标包、源文件、chunk/vector 索引产物，后续单独确认。

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户无权删除模拟审核 |
| 40400 | 审核记录不存在、不属于本组织或已删除 |

### 3.5 重新审核

`POST /api/bid-simulation-audits/{auditId}/rerun`

重新发起模拟审核。后端会将旧记录标记为 `OBSOLETE`，复用原内部招标包、内部投标包和文档 ID 新建一条 `BUILDING` 记录，并重新创建 `BID_SIMULATION_AUDIT` 总任务。

限制：

- 仅 `BIDDER` 可操作。
- 仅可重跑本组织未删除记录。
- 原记录为 `BUILDING` 时拒绝重跑，避免同一批文件并发重复审核。
- 重跑不重新上传文件，也不重新创建 INDEX 子任务；Worker 会复用已索引的原文档。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "auditId": 2,
    "jobId": 102,
    "tenderPackageId": 77,
    "bidPackageId": 88,
    "tenderDocumentIds": [7],
    "bidDocumentIds": [8],
    "indexJobIds": [],
    "tenderFiles": [],
    "bidFiles": []
  }
}
```

字段说明同创建接口；`auditId` 为新审核记录 ID，前端应使用新的 `jobId` 轮询进度，并在成功后进入新的 `auditId` 详情。

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户无权重跑模拟审核 |
| 40400 | 审核记录不存在、不属于本组织或已删除 |
| 40900 | 原审核仍在 `BUILDING` |

### 3.6 导出审核结果

`GET /api/bid-simulation-audits/{auditId}/export`

导出一条投标模拟审核结果。仅 `SUCCESS` 状态可导出，响应为 xlsx 二进制附件，不使用统一 JSON 包装。

**响应头**

```http
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename*=UTF-8''...xlsx
```

**文件名**

`{审核名称}-投标模拟审核结果.xlsx`

**工作表**

| sheet | 内容 |
|------|------|
| 概览 | 审核 ID、任务名称、状态、招标 / 投标文件数量、资格统计、建议得分、总分、问题数量、创建 / 更新时间 |
| 招标文件 | 文档 ID、标包 ID、文件名、扩展名、索引状态 |
| 投标文件 | 文档 ID、标包 ID、文件名、扩展名、索引状态 |
| 招标基础信息 | 招标人、招标代理、预算金额、投标截止时间及 evidence |
| 资格响应 | 编号、资格要求、响应结果、判断原因、分类、页码、chunkIds、原文片段 |
| 评分响应 | 编号、评分项、满分、建议得分、得分理由、分类、页码、chunkIds、原文片段 |
| 投标逻辑问题 | 编号、问题标题、问题内容、严重程度、问题类型、修改建议、页码、chunkIds、原文片段 |
| 投标错别字 | 编号、错误文本、建议文本、置信度、修改建议、页码、chunkIds、原文片段 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户无权导出模拟审核 |
| 40400 | 审核记录不存在、不属于本组织或已删除 |
| 40900 | 审核未成功，不能导出 |

---

## 隐藏逻辑（本册必读）

### 内部包

- 每次模拟审核会创建一个内部 `TENDER` 包和一个内部 `BID` 包，用于复用现有文件上传、索引、对象存储、chunk/vector 能力。
- 本模块不依赖项目，内部包不绑定项目。
- 任务名称不传时，默认取第一个投标文件的文件名。
- 创建任务时，前端必须分别传 `tenderFileTypes` 和 `bidFileTypes`；后端按文件下标一一保存到 `biz_document.file_type`。

### 任务与进度

- 创建模拟审核任务后，后端先为招标文件和投标文件创建 INDEX 子任务，再由 `BID_SIMULATION_AUDIT` 任务汇总执行。
- 进度查询复用现有 job 接口，不新增专用进度接口。
- 任一参与文件 INDEX 失败，本次投标模拟审核整体失败。
- 当前 `BID_SIMULATION_AUDIT` Worker 已接入执行器、轮询器、招标侧抽取、投标侧资格/评分响应审查、投标文件逻辑问题和错别字识别：等待招标文件和投标文件全部索引成功后，读取招标文件 chunk，复用建清单能力抽取 `QUALIFICATION` / `SCORING` 条目，再复用一键审查比较能力审查内部投标包；资格项返回 `PASS` / `FAIL` / `MANUAL`，评分项返回 `suggestedScore` / `fullScore`；随后读取投标文件 chunk，复用全文分批扫描能力生成 `LOGIC_ISSUE` / `TYPO` 条目。
- Worker 配置：`bidding.bid-simulation-audit.worker-enabled`、`bidding.bid-simulation-audit.poll-ms`、`bidding.bid-simulation-audit.worker-id`。

### 审核结果

- 资格要求和评分项沿用 AI 审核流程，需要使用数据库里的检索词。
- 评分项必须逐项返回 `suggestedScore` / `fullScore`。
- 逻辑问题和错别字识别只针对投标文件，不扫描招标文件。
- 逻辑问题和错别字识别不使用数据库检索词，沿用全文 chunk 分批扫描方案。
- 本期不做法律风险识别，不接入法律库。
- 导出审核结果复用详情聚合数据与内部包文件列表，只允许 `SUCCESS` 记录导出。
