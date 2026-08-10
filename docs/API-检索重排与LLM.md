# 招投标后端 · API（检索 / 重排 / LLM）

> 本册范围：**文档索引、向量召回、重排、审查维度、建清单、智能审查**及调试接口。  
> 文件上传与标包 CRUD 见：[文件与标包](./API-文件与标包.md)  
> 账号 / 组织 / 项目见：[账号组织项目](./API-账号组织项目.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-08-07

## 通用约定（摘要）

- 统一响应：`{ code, message, data }`；成功 `code=0`。
- 业务接口角色：多为 **`TENDERER`**；审查维度管理接口需平台管理员。
- 跨组织 → **40400**。
- 长任务一律进 `biz_job`；进度：**短轮询** `GET /api/jobs/{jobId}`（保留），或 **SSE** `GET /api/jobs/{jobId}/events`（推荐）。完整约定见专册 [API-任务进度SSE.md](./API-任务进度SSE.md)。

## 能力总览

| 能力 | 说明 |
|------|------|
| 表 `biz_job` | 统一异步任务；INDEX 的 payload 含 `documentId` |
| 表 `biz_document_chunk` / `biz_document_vector` | INDEX 产物；向量暂存 `embedding_json`（后续可迁 pgvector） |
| 任务状态 | `PENDING` → `RUNNING` → `SUCCESS` / `FAILED` / `CANCELLED` |
| 文档 `indexStatus` | 创建任务时 `PROCESSING`；Worker 成功/失败回写 `SUCCESS` / `FAILED` |
| 执行器（当前） | 同进程定时认领；`INDEX_MODE=real` 时真抽文本/切块 + 百炼向量 |
| 真索引 | 百炼 `text-embedding-v4`；PDF/docx 抽文本；章节切块 |
| 召回 / 重排列表 | **不入库**；开发期可用 debug 接口 |
| 审查维度 | 全局表；招标文件 RAG 13 维全选（V26/V27）；建清单不提供前端勾选 |
| 建清单 | `BUILD_CHECKLIST`：招标侧多维召回→综合 query 重排→LLM→落库 |
| 智能审查 | `REVIEW`：按包×条目；召回**仅** `bidSearch`（无则跳过召回）；重排/对照用 `text` |

角色：仅 `TENDERER`（维度管理除外）；跨组织资源一律 `40400`。  
配置（可选）：`INDEX_MODE`、`DASHSCOPE_API_KEY`、`EMBEDDING_*`、`RECALL_*`、`RERANK_*`、`CHECKLIST_*`、`LLM_*`、`REVIEW_*`。

---

## 1. 文档索引（INDEX）

#### 发起文档索引

`POST /api/packages/{packageId}/files/{documentId}/index`

无请求体。校验：标包/文件属本组织，且文件属于该标包。  
若文档 `indexStatus` 已是 `PROCESSING` → `40900`「索引任务进行中，请勿重复提交」。  
成功：写入 `biz_job`（`PENDING`），文档置 `PROCESSING`，返回任务摘要。  
同进程 Worker 认领后按 `INDEX_MODE` 执行索引（`real` 为真抽文本/切块/向量），任务变为 `SUCCESS`（或 `FAILED`），文档 `indexStatus` 同步回写。

**成功 `data` 示例**

```json
{
  "id": 55,
  "orgId": 2,
  "projectId": 10,
  "type": "INDEX",
  "status": "PENDING",
  "progress": 0,
  "message": null,
  "payloadJson": "{\"documentId\":9}",
  "errorMessage": null,
  "retryCount": 0,
  "createdBy": 10,
  "createdAt": "2026-07-27T06:00:00Z",
  "startedAt": null,
  "finishedAt": null,
  "updatedAt": "2026-07-27T06:00:00Z"
}
```

#### 8.0.2 查询任务（短轮询，保留）

`GET /api/jobs/{jobId}`

成功 `data` 字段同 8.0.1。不存在或非本组织 → `40400`。  
**保留**：创建任务后前端可定时轮询本接口，直至 `status` 为 `SUCCESS` / `FAILED` / `CANCELLED`。  
索引场景：轮询至 `status=SUCCESS` 且文件列表里该文档 `indexStatus=SUCCESS` 即索引完成。  
断线、刷新、SSE 不可用时仍用本接口拉快照。

#### 8.0.2b 任务进度 SSE

`GET /api/jobs/{jobId}/events`

事件流、鉴权（Bearer + fetch，勿用原生 EventSource）、配置与前端接入流程见专册：

→ **[API-任务进度SSE.md](./API-任务进度SSE.md)**

---

## 2. 召回 / 重排 / 维度 / 建清单 / 审查

#### 8.0.3 建清单召回调试（开发期）

`POST /api/projects/{projectId}/checklist-recall-debug`

仅当 `bidding.recall.debug-enabled=true`（环境变量 `RECALL_DEBUG`）时注册；关闭后接口不存在（404）。  
仅 `TENDERER`；项目须属本组织。结果**不入库**。

用途：按正式建清单链路自动查看“招标侧多维召回后片段”。调用方不传 query，后端会自动使用数据库里全部启用审查维度的检索词。

**请求体**

无。

**处理范围**

| 项 | 说明 |
|----|------|
| 项目 | 路径参数 `projectId` |
| 招标包 | 自动查项目绑定的 `TENDER` 标包 |
| 招标文件 | 自动使用该招标包下所有 `indexStatus=SUCCESS` 的未删除文档 |
| 检索词 | 自动使用 `biz_review_dimension` / `biz_review_dimension_query` 中全部启用维度的非空 query |
| 召回逻辑 | 与建清单一致：多 query 向量召回；开启 hybrid 时再做 BM25，并用 RRF 融合 |
| 多文件输出 | 多个招标文件的片段会跨文件合并排序；每条 hit 通过 `documentId` / `fileName` / `pageNo` 区分来源 |

**成功 `data` 示例**

```json
{
  "projectId": 10,
  "tenderPackageId": 50,
  "tenderPackageName": "招标文件",
  "documents": [
    {
      "documentId": 101,
      "fileName": "招标文件.pdf",
      "fileExt": "pdf",
      "indexStatus": "SUCCESS"
    },
    {
      "documentId": 102,
      "fileName": "采购需求.docx",
      "fileExt": "docx",
      "indexStatus": "SUCCESS"
    }
  ],
  "dimensionCount": 15,
  "queryCount": 470,
  "queryTexts": [
    "找出所有关于信息安全服务资质的规定",
    "CCRC信息安全服务资质要求"
  ],
  "topK": 40,
  "hitCount": 40,
  "hits": [
    {
      "rank": 1,
      "chunkId": 9001,
      "chunkIndex": 12,
      "pageNo": 8,
      "content": "供应商须具备信息安全服务资质...",
      "documentId": 101,
      "fileName": "招标文件.pdf",
      "packageId": 50,
      "packageType": "TENDER",
      "packageName": "招标文件",
      "score": 0.0327
    }
  ]
}
```

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于本组织 |
| 40000 | 项目未绑定招标包、没有已索引成功的招标文档、没有可用审查维度 |

### 8.0.4 审查维度（全局）

全局一份配置，供建清单勾选与多 query 召回。  
表：`biz_review_dimension`（**id 主键**，`code` 唯一业务键） / `biz_review_dimension_query`（挂 **`dimension_id`**）。

种子维度（V26/V27 建维；**V29** 检索词=产品原文）：
- **13 维全部启用且默认勾选**；建清单时**固定使用全部启用维度**，前端不提供勾选，请求体 `dimensionCodes` 忽略。
- 对比用：V26 优化短词版备份在 `db/seed/restore_dimension_queries_optimized.sql`，可手动换回。

列表以 `id` + 中文 `name` 为主，`code` 给系统用。  
说明：清单条目主类仍为 `QUALIFICATION` / `SCORING`（LLM 归纳）；维度表只决定建清单搜哪些词。

#### 只读（登录即可）

`GET /api/review-dimensions`

返回**启用中**的维度及 `queries`。

**成功 `data` 元素示例**

```json
{
  "id": 1,
  "code": "QUALIFICATION",
  "name": "资格要求类",
  "defaultSelected": true,
  "enabled": true,
  "sortOrder": 10,
  "queries": [
    "投标人资格条件 资格规定",
    "投标人必须具备的资质要求"
  ]
}
```

#### 管理（仅 PLATFORM_ADMIN）

`GET /api/admin/review-dimensions`  
返回全部维度（含停用）。

`PUT /api/admin/review-dimensions/{id}`  
按数字 **id** 更新；**queries 全量覆盖**（空数组=清空检索词）。本期不支持新增维度。

**请求体**

```json
{
  "name": "资格与资质",
  "defaultSelected": true,
  "enabled": true,
  "sortOrder": 10,
  "queries": [
    "投标人资格条件与资质等级要求",
    "类似项目业绩与项目负责人资格"
  ]
}
```

| 字段 | 说明 |
|------|------|
| name | 必填 |
| defaultSelected / enabled / sortOrder | 可选；不传则保留原值 |
| queries | 必填数组，全量替换 |

#### 8.0.5 建清单重排调试（开发期）

`POST /api/projects/{projectId}/checklist-rerank-debug`

仅当 `bidding.rerank.debug-enabled=true`（环境变量 `RERANK_DEBUG`）时注册；关闭后接口不存在（404）。  
仅 `TENDERER`；项目须属本组织。结果**不入库**。

用途：按正式建清单链路自动查看“招标侧多维召回 → 综合重排”后的 topN 片段。调用方不传 query，后端会自动使用数据库里全部启用审查维度的检索词，并使用正式建清单的综合重排 query。

**请求体**

无。

**处理范围**

| 项 | 说明 |
|----|------|
| 项目 | 路径参数 `projectId` |
| 招标包 / 招标文件 | 同 `checklist-recall-debug`，使用项目 `TENDER` 标包下所有 `indexStatus=SUCCESS` 文档 |
| 召回 query | 自动使用全部启用审查维度的非空 query |
| 重排 query | 自动拼接维度名与检索词：`维度名：query1；query2。维度名：query1` |
| 输出顺序 | 与正式建清单一致：先跨文件合并召回候选，再由 `qwen3-rerank` 重排输出 topN |
| 多文件输出 | 多个招标文件的片段会跨文件统一排序；每条 hit 通过 `documentId` / `fileName` / `pageNo` 区分来源 |

**成功 `data` 示例**

```json
{
  "projectId": 10,
  "tenderPackageId": 50,
  "tenderPackageName": "招标文件",
  "documents": [
    {
      "documentId": 101,
      "fileName": "招标文件.pdf",
      "fileExt": "pdf",
      "indexStatus": "SUCCESS"
    }
  ],
  "dimensionCount": 15,
  "queryCount": 470,
  "queryTexts": [
    "找出所有关于信息安全服务资质的规定",
    "CCRC信息安全服务资质要求"
  ],
  "rerankQuery": "安全资质与认证体系：找出所有关于信息安全服务资质的规定；CCRC信息安全服务资质要求",
  "recallTopK": 40,
  "recallHitCount": 40,
  "rerankTopN": 15,
  "rerankHitCount": 15,
  "hits": [
    {
      "rank": 1,
      "chunkId": 9001,
      "chunkIndex": 12,
      "pageNo": 8,
      "content": "供应商须具备信息安全服务资质...",
      "documentId": 101,
      "fileName": "招标文件.pdf",
      "packageId": 50,
      "packageType": "TENDER",
      "packageName": "招标文件",
      "recallScore": 0.0327,
      "rerankScore": 0.91
    }
  ]
}
```

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于本组织 |
| 40000 | 项目未绑定招标包、没有已索引成功的招标文档、没有可用审查维度 |
| 50000 | `DASHSCOPE_API_KEY` 未配置或重排服务调用失败 |

#### 8.0.6 建清单（BUILD_CHECKLIST）

异步：按勾选审查维度，在**招标包**已 INDEX 成功的文档上多 query 召回并合并 → **综合 query 重排一次** → **按产物拆分 LLM**（资格条目 A ∥ 评分条目 B → 程序编号 Q/S → bidSearch C → evidence D；版本记 `extract-artifact-v1`）→ 落库新版本清单。  
**A/B/C/D 任一步 LLM 调用失败 → 整单建清单 FAILED**（job + checklist 均 FAILED）。编号后 **C∥D 并行**（墙钟 ≈ max(C,D)）。无 `chunkIds` 的条目跳过 D、`evidenceJson=[]`。首条 evidence 的 quote 写入 `sourceExcerpt`（审查 `tenderQuote`）。  
不做人工确认；`SUCCESS` 即可被后续 REVIEW 引用。条目 `mainType`=`QUALIFICATION`/`SCORING`；`dimensionCode` 写入主类。

`POST /api/projects/{projectId}/checklists`

**请求体（均可选）**

```json
{
  "dimensionCodes": ["QUALIFICATION", "REJECT"],
  "documentIds": [7]
}
```

| 字段 | 说明 |
|------|------|
| dimensionCodes | 已忽略；后端固定用全部启用维度（兼容旧客户端仍可传） |
| documentIds | 可选；须属本项目招标包且 `indexStatus=SUCCESS`；不传则用该包下全部已成功索引文档 |

**成功 `data`**

```json
{ "checklistId": 88, "jobId": 99, "version": 1 }
```

前置：项目已绑招标包；至少 1 份招标文档 INDEX 成功。  
进度：短轮询 `GET /api/jobs/{jobId}`，或 SSE `GET /api/jobs/{jobId}/events`；清单状态 `BUILDING` → `SUCCESS` / `FAILED`。

`GET /api/projects/{projectId}/checklists` — 列表（按 version 降序）

`GET /api/projects/{projectId}/checklists/{checklistId}` — 详情（含 `items`；成功时含 `llmRawJson` 为建清单 LLM 原始 `message.content`；列表不返回）

配置：`CHECKLIST_*`（含 `CHECKLIST_BID_SEARCH_BATCH_SIZE`，默认 10）、`LLM_BASE_URL`、`LLM_MODEL`（默认 `qwen3.7-plus`）、`LLM_PROMPT_VERSION`（默认 `extract-artifact-v1`；复用 `DASHSCOPE_API_KEY`）。

#### 8.0.7 智能审查（REVIEW）

异步：基于某版 **SUCCESS** 清单，对选定 **投标包逐个**、对清单条目逐条：  
投标侧召回（**仅 `bidSearchJson` 多词合并；无 bidSearch 则不召回**，资格→无法判断 / 评分→0）→ 用要求正文 **`text` 重排** → LLM 对照 → 落库。  
`items` 数量 ≈ **投标包数 × 清单条目数**，每条必有 `packageId`。  
**无项目级总判定**（`overallVerdict` 为 `null`）；符合/不符合由前端按包统计。无召回 → 该条 `FAIL`。

`POST /api/projects/{projectId}/reviews`

**请求体（均可选）**

```json
{
  "checklistId": 88,
  "packageIds": [80, 81]
}
```

| 字段 | 说明 |
|------|------|
| checklistId | 可选；不传则用项目下最新 SUCCESS 清单 |
| packageIds | 可选；须为本项目有效投标包；不传则用项目下全部投标包 |

**成功 `data`**

```json
{ "reviewId": 201, "jobId": 301, "version": 1 }
```

前置：至少 1 份 SUCCESS 清单且有条目；选定投标包下至少 1 份文档 INDEX 成功。  
进度：短轮询 `GET /api/jobs/{jobId}`，或 SSE `GET /api/jobs/{jobId}/events`；审查状态 `BUILDING` → `SUCCESS` / `FAILED`。

`GET /api/projects/{projectId}/reviews` — 列表（按 version 降序）

`GET /api/projects/{projectId}/reviews/{reviewId}` — 详情按投标包嵌套：`packages[]` 含 `supplier`、**整包**`suggestedScoreTotal`/`fullScoreTotal`、`qualificationResponses`、`scoringResponses`（含 `mainType`/`mainTypeLabel`、`title`/`content`/`subType`/`status` 或建议分、`judgement`、`bidEvidence`、`llmRawJson`）

配置：`REVIEW_*`、`LLM_REVIEW_PROMPT_VERSION`（默认 `review-v2`）；对照模型复用 `LLM_MODEL`。

#### 8.0.8 一键 AI 审查（AI_REVIEW_PIPELINE）

> 白话说明见 [ai.md](./ai.md)。  
> 前端一个按钮：后端自动 **INDEX → 建清单 → REVIEW**。

`POST /api/projects/{projectId}/ai-reviews/run`

无请求体。角色：`TENDERER`。

**前置**

- 已绑招标包、至少一个投标包
- 招标包 / 投标包下至少各有 1 个未删除文件
- 同项目无进行中的一键审查任务（否则 **409**）

**成功 `data`**

```json
{ "jobId": 501 }
```

**进度** 短轮询 `GET /api/jobs/{jobId}` 或 SSE `GET /api/jobs/{jobId}/events`（`type=AI_REVIEW_PIPELINE`）

| 字段 | 说明 |
|------|------|
| status | `PENDING` → `RUNNING` → `SUCCESS` / `FAILED` |
| progress / message | 进度文案（向量化中 / 建清单中 / AI审查中 …） |
| checklistId / reviewId | 成功后填充（亦在 `payloadJson` 中；`message` 仅用于展示进度文案） |
| errorMessage | 失败原因；索引失败会带文件名 |

**规则摘要**

- 向量化：`SUCCESS` 跳过；`PROCESSING` 等待不重复入队；`PENDING`/`FAILED` 入队；任一失败整单失败
- 清单：有 SUCCESS 且有条目则复用，否则新建
- 审查：全部投标包 × 清单条目全量审完；`items` 带 `packageId`；无项目级总判定
- 流程日志：`logs/ai.log`（Logger `ai.pipeline`）

---

## 隐藏逻辑（本册必读）

### 索引流水线（INDEX）
- 同进程 Worker 定时认领 `PENDING` 任务 → `RUNNING` → `SUCCESS`/`FAILED`。
- `INDEX_MODE=real`：读对象存储 → 抽文本（PDF/docx）→ 章节切块 → 百炼 embedding → 写 chunk/vector；空文本/扫描件常见失败原因。
- `INDEX_MODE` 非 real 时可能走 stub/降级（以环境配置为准）。
- 同一文档 `indexStatus=PROCESSING` 时禁止重复提交（409）。
- 向量当前存 `embedding_json`（应用层余弦），默认约 1024 维、batch≤10。

### 召回（Recall）
- 建清单召回调试固定复用真实建清单范围：项目 `TENDER` 标包下所有 `indexStatus=SUCCESS` 招标文档。
- **结果不入库**，只返回/打日志；生产勿依赖 debug 接口。
- `checklist-recall-debug` 仅 `RECALL_DEBUG=true` 时注册，关闭后路由不存在（HTTP 404）。

### 重排（Rerank）
- 典型链路：向量召回 topK → 百炼 `qwen3-rerank` → 截断 topN；**结果不入库**。
- 重排 API 路径为业务空间 `compatible-api/v1/reranks`（与 embedding 的 `compatible-mode` 不同）。
- 建清单重排调试固定复用真实建清单综合 `rerankQuery`，不再支持手动传 query。
- `checklist-rerank-debug` 仅 `RERANK_DEBUG=true` 时可用。

### 审查维度
- 全局一份配置，非按组织隔离；招标文件 RAG 13 维（V26）。
- 建清单固定用全部 `enabled=true` 的维度；请求 `dimensionCodes` 忽略。
- 管理接口 `queries` **全量覆盖**（空数组=清空）；本期不支持新增维度行。

### 建清单（BUILD_CHECKLIST）
- 只在**招标包**已 INDEX 成功文档上跑。
- 仍用管理员配置的审查维度 queries 做多路召回；合并后综合重排一次；**LLM 按产物拆分**（A 资格 ∥ B 评分 → 编号 → C bidSearch → D evidence，`extract-artifact-v1`）。
- 条目含：`title`、`mainType`、`subType`、`text`(content)、`bidSearchJson`、`evidenceJson`、`sourceExcerpt`、评分字段；保留 `chunkIds`；`dimension_code` 写主类。
- 成功时主表写入 `llm_raw_json`（含 A/B/C/D 原文拼接）；详情 API 返回 `llmRawJson`。
- 状态 `BUILDING` → `SUCCESS`/`FAILED`；成功后即可被 REVIEW 引用，无人工确认步。
- LLM 默认模型 `qwen3.7-plus`（`LLM_MODEL`），复用 `DASHSCOPE_API_KEY`。

### 智能审查（REVIEW）
- 基于某版 **SUCCESS** 清单；对每个投标包单独做召回→重排→LLM 对照（N×P）。
- **召回**：仅用条目 `bidSearchJson`（多词各自召回后按 chunk 合并）；**无 bidSearch 不召回、不调对照 LLM**（走无证据规则）。
- **重排 / 对照**：query 与招标要求正文均用条目 **`text`**（对照 LLM 为 **review-v2** 产品输出）。
- `bidEvidence` 落库前清洗：占位文案或不在候选原文中 → 丢弃；资格无证据 → `无法判断`；评分无证据 → 建议分 0。
- 详情 API 按包嵌套返回 `qualificationResponses` / `scoringResponses`；`overall_verdict` 置空。
- 有对照的条目写入 `llm_raw_json`；详情 `items[].llmRawJson` 返回；无召回未调 LLM 则为空。
- 无召回 / 无可用证据 → 该条 `FAIL`。
- `needHumanReview` 默认 `true`。
- 不传 `checklistId` → 取项目下最新 SUCCESS 清单；不传 `packageIds` → 该项目全部投标包。
- 详情扁平返回，前端按 `packageId` → `dimensionCode`/`mainType` 分组展示。

### 一键审查（AI_REVIEW_PIPELINE）
- 总任务编排 INDEX → 建清单 → REVIEW；防双开；细节见 [ai.md](./ai.md)。
- 关键日志写入 `logs/ai.log`。

### 配置键（环境变量）
- `INDEX_MODE`、`DASHSCOPE_API_KEY`
- `EMBEDDING_*`、`RECALL_*` / `RECALL_DEBUG`
- `RERANK_*` / `RERANK_DEBUG`
- `CHECKLIST_*`、`LLM_*`、`LLM_REVIEW_PROMPT_VERSION`
- `REVIEW_*`
