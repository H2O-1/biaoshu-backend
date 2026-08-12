# 招投标后端 · API（招标解析）

> 本册范围：**独立招标文件上传解析**。  
> 用户上传一个或多个招标文件，后端自动解析基础信息、资格要求、评分标准；不创建项目、不上传投标文件、不发起审查。  
> 文件 / 标包通用能力见：[文件与标包](./API-文件与标包.md)  
> 任务进度见：[任务进度 SSE](./API-任务进度SSE.md)  
> 账号 / 组织 / 项目见：[账号组织项目](./API-账号组织项目.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-08-11

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
| 查看原文片段 | 返回 evidence，前端可通过 chunk / page / quote 定位原文片段 |

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

`GET /api/tender-analyses?page=1&size=20&keyword=`

查询当前登录招标方组织下的招标解析历史记录。按创建时间倒序返回。

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `keyword` | string | 否 | `""` | 按任务名称 / 招标人 / 招标代理模糊搜 |
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
        "tenderer": "上海智慧园区发展有限公司",
        "agency": "中建咨询（招标代理）",
        "budgetAmountText": "￥12,800,000.00（含税最高限价）",
        "bidDeadlineText": "2026-08-15 17:00",
        "bidDeadlineAt": "2026-08-15T09:00:00Z",
        "errorMessage": null,
        "createdBy": 10,
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
| `jobId` | 解析任务 ID；`BUILDING` 时可用于查询进度 |

**常见错误**

| code | 场景 |
|------|------|
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
| `items[].chunkIds` | 来源 chunk ID 数组 |
| `items[].evidence` | 条目原文证据数组，用于“查看原文片段” |
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
- 前端“查看原文片段”依赖 evidence 中的 chunk / page / quote 信息。
- 资格要求 / 评分标准复用建清单拆分链路：资格和评分分开抽取，再生成条目检索词与招标侧原文 evidence；本接口不发起投标文件审查。
