# 一键 AI 审查（AI_REVIEW_PIPELINE）

> 面向前端：一个按钮跑完整条链路。  
> 详细 API 字段见 [API-检索重排与LLM.md](./API-检索重排与LLM.md)。  
> 更新：2026-07-31

## 前端怎么用

1. 上传招标 / 投标文件（普通上传即可）
2. 点击「开始 AI 审查」→ 只调一次：

```http
POST /api/projects/{projectId}/ai-reviews/run
```

成功返回（此时还没有 `reviewId`，只有总任务 id）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "jobId": 501
  }
}
```

同项目重复点按钮 → **409**：

```json
{
  "code": 409,
  "message": "该项目已有进行中的一键审查任务，请勿重复提交",
  "data": null
}
```

3. 用上一步的 `jobId` 轮询总任务：

```http
GET /api/jobs/501
```

**进行中**（`reviewId` / `checklistId` 还是 `null`）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 501,
    "orgId": 2,
    "projectId": 10,
    "type": "AI_REVIEW_PIPELINE",
    "status": "RUNNING",
    "progress": 70,
    "message": "AI审查中",
    "payloadJson": "{\"projectId\":10,\"phase\":\"INDEX\",\"createdBy\":1}",
    "errorMessage": null,
    "retryCount": 0,
    "createdBy": 1,
    "checklistId": null,
    "reviewId": null,
    "createdAt": "2026-07-30T06:00:00Z",
    "startedAt": "2026-07-30T06:00:01Z",
    "finishedAt": null,
    "updatedAt": "2026-07-30T06:05:00Z"
  }
}
```

**成功**（`reviewId` / `checklistId` 就在这里，直接取 `data.reviewId`）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 501,
    "orgId": 2,
    "projectId": 10,
    "type": "AI_REVIEW_PIPELINE",
    "status": "SUCCESS",
    "progress": 100,
    "message": "审查完成 reviewId=201 checklistId=88",
    "payloadJson": "{\"projectId\":10,\"phase\":\"DONE\",\"checklistId\":88,\"reviewId\":201,\"reusedChecklist\":true}",
    "errorMessage": null,
    "retryCount": 0,
    "createdBy": 1,
    "checklistId": 88,
    "reviewId": 201,
    "createdAt": "2026-07-30T06:00:00Z",
    "startedAt": "2026-07-30T06:00:01Z",
    "finishedAt": "2026-07-30T06:12:00Z",
    "updatedAt": "2026-07-30T06:12:00Z"
  }
}
```

前端取值约定：

| 字段                                | 何时有值         | 说明                        |
| ----------------------------------- | ---------------- | --------------------------- |
| `data.jobId`（发起接口）            | 立刻             | 用来轮询                    |
| `data.aiReviewJobId`（项目详情/列表） | 有过一键审查时   | 退出再进后从此字段续轮询    |
| `data.reviewId`（轮询接口）         | `status=SUCCESS` | **审查详情 id，优先用这个** |
| `data.checklistId`（轮询接口）      | `status=SUCCESS` | 清单 id（可选展示）         |
| `data.message` / `data.payloadJson` | 成功后也有       | 兜底；一般不用自己解析      |

**失败**（读 `errorMessage`，含阶段与失败文件名）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 501,
    "orgId": 2,
    "projectId": 10,
    "type": "AI_REVIEW_PIPELINE",
    "status": "FAILED",
    "progress": 10,
    "message": null,
    "payloadJson": "{\"projectId\":10,\"phase\":\"INDEX\",\"createdBy\":1}",
    "errorMessage": "[INDEX] 文件索引失败: 投标文件A.pdf (documentId=15)",
    "retryCount": 0,
    "createdBy": 1,
    "checklistId": null,
    "reviewId": null,
    "createdAt": "2026-07-30T06:00:00Z",
    "startedAt": "2026-07-30T06:00:01Z",
    "finishedAt": "2026-07-30T06:03:00Z",
    "updatedAt": "2026-07-30T06:03:00Z"
  }
}
```

4. 拿到 `reviewId` 后拉审查详情（**展示 AI 审查结果用这个接口**）：

### 4.1 入参

```http
GET /api/projects/{projectId}/reviews/{reviewId}
Authorization: Bearer <accessToken>
```

| 位置         | 参数        | 类型 | 必填 | 说明                                                              |
| ------------ | ----------- | ---- | ---- | ----------------------------------------------------------------- |
| path         | `projectId` | long | 是   | 项目 id                                                           |
| path         | `reviewId`  | long | 是   | 一键审查成功后的 `data.reviewId`，或分步创建审查返回的 `reviewId` |
| body / query | —           | —    | —    | **无请求体、无 query**                                            |

角色：`TENDERER`；跨组织 / 审查不存在 → `40400`。

> 若走分步接口自行创建审查（非一键）：`POST /api/projects/{projectId}/reviews`，body 可选  
> `{ "checklistId": 88, "packageIds": [80, 81] }`（均省略则用最新成功清单 + 项目下全部投标包）。

### 4.2 出参总览

统一包装：`{ "code": 0, "message": "ok", "data": { ... } }`。

**`data` 顶层**

| 字段                              | 类型         | 说明                                                |
| --------------------------------- | ------------ | --------------------------------------------------- |
| id                                | long         | 即 reviewId                                         |
| orgId / projectId                 | long         | 组织 / 项目                                         |
| checklistId                       | long         | 所用清单版本                                        |
| version                           | int          | 本项目审查版本号                                    |
| status                            | string       | `BUILDING` / `SUCCESS` / `FAILED`                   |
| packageIdsJson                    | string       | 对照的投标包 id 列表 JSON，如 `"[80,81]"`           |
| overallVerdict                    | string\|null | **已废弃，恒为 `null`**；不要用                     |
| needHumanReview                   | boolean      | 是否需人工复核（默认 true）                         |
| promptVersion                     | string       | 如 `review-v2`                                      |
| recallTopK / rerankTopN           | int          | 本次召回/重排参数快照                               |
| errorMessage                      | string\|null | 失败原因；成功多为 null                             |
| createdBy / createdAt / updatedAt | —            | 创建人与时间                                        |
| packages                          | array        | **按投标包嵌套**的产品向结果（多投标包 = 多个元素） |

**`packages[]`**

| 字段                   | 类型          | 说明                                       |
| ---------------------- | ------------- | ------------------------------------------ |
| packageId              | long          | 投标包 id                                  |
| supplier               | string        | 投标单位名称（包 `name`；缺省为 `包{id}`） |
| suggestedScoreTotal    | number\|null  | **整包**评分建议分合计（各条之和）         |
| fullScoreTotal         | number\|null  | **整包**评分满分合计（各条之和）           |
| qualificationResponses | array         | 该包资格类条目结果                         |
| scoringResponses       | array         | 该包评分类条目结果                         |

卡片「建议分 x/y」用包上的 `suggestedScoreTotal` / `fullScoreTotal`；单项字段只用于点进某一评分项。

**`qualificationResponses[]`（资格）**

| 字段              | 类型          | 说明                                           |
| ----------------- | ------------- | ---------------------------------------------- |
| id                | long          | 审查条目主键                                   |
| requirementItemId | long          | 清单条目主键                                   |
| requirementId     | string        | 产品编号，如 `Q1`                              |
| title             | string        | 左栏标题（来自清单，**各包相同**）             |
| content           | string        | 招标要求正文（来自清单，**各包相同**）         |
| mainType          | string        | 主类 code：`QUALIFICATION`                     |
| mainTypeLabel     | string        | 主类中文：`资格要求`                           |
| subType           | string        | 子类中文标签，左栏小字（**各包相同**）         |
| reviewType        | string\|null  | 如 `响应性审查`                                |
| status            | string        | **`符合` / `不符合` / `无法判断`**（按包不同） |
| vetoRisk          | boolean\|null | 否决风险                                       |
| judgement         | string\|null  | 判定说明（≤约 200 字）                         |
| bidEvidence       | array         | 投标证据；元素见下                             |
| note              | string\|null  | 备注                                           |
| llmRawJson        | string\|null  | LLM 原文（调试用，前端可忽略）                 |

**`scoringResponses[]`（评分）**：字段同资格，但 **`mainType`=`SCORING`、`mainTypeLabel`=`评分项`**，且**无 `status`**，另有：

| 字段            | 类型         | 说明                               |
| --------------- | ------------ | ---------------------------------- |
| suggestedScore  | number\|null | 建议得分（按包不同）               |
| fullScore       | number\|null | 满分；**空则前端显示「暂无评分」** |
| deductionReason | string\|null | 扣分说明                           |

**`bidEvidence[]`**

| 字段       | 类型         | 说明                 |
| ---------- | ------------ | -------------------- |
| fragmentId | long\|null   | 对应 chunk / 片段 id |
| page       | int\|null    | 页码                 |
| quote      | string\|null | 摘录原文             |

### 4.3 多投标包成功示例

两个投标包（华东智建、南方工程），共享同一套清单题干；资格状态与建议分按包不同。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 201,
    "orgId": 2,
    "projectId": 10,
    "checklistId": 88,
    "version": 3,
    "status": "SUCCESS",
    "packageIdsJson": "[80,81]",
    "overallVerdict": null,
    "needHumanReview": true,
    "promptVersion": "review-v2",
    "recallTopK": 30,
    "rerankTopN": 6,
    "errorMessage": null,
    "createdBy": 1,
    "createdAt": "2026-07-31T08:00:00Z",
    "updatedAt": "2026-07-31T08:15:00Z",
    "packages": [
      {
        "packageId": 80,
        "supplier": "华东智建科技有限公司",
        "suggestedScoreTotal": 9.0,
        "fullScoreTotal": 10.0,
        "qualificationResponses": [
          {
            "id": 1001,
            "requirementItemId": 501,
            "requirementId": "Q1",
            "title": "资质等级证书",
            "content": "投标人须具有有效的信息系统建设和服务能力等级证书（CS）二级及以上。",
            "mainType": "QUALIFICATION",
            "mainTypeLabel": "资格要求",
            "subType": "资质证书",
            "reviewType": "响应性审查",
            "status": "符合",
            "vetoRisk": false,
            "judgement": "投标文件提供有效二级证书，满足要求。",
            "bidEvidence": [
              {
                "fragmentId": 9001,
                "page": 12,
                "quote": "我公司具备信息系统建设和服务能力等级证书二级……"
              }
            ],
            "note": null,
            "llmRawJson": null
          },
          {
            "id": 1002,
            "requirementItemId": 502,
            "requirementId": "Q2",
            "title": "项目经理资格",
            "content": "拟派项目经理须具备相关专业中级及以上职称，并提供社保证明。",
            "mainType": "QUALIFICATION",
            "mainTypeLabel": "资格要求",
            "subType": "人员要求",
            "reviewType": "响应性审查",
            "status": "无法判断",
            "vetoRisk": false,
            "judgement": "未检索到相关响应证据，无法判断。",
            "bidEvidence": [],
            "note": null,
            "llmRawJson": null
          }
        ],
        "scoringResponses": [
          {
            "id": 1003,
            "requirementItemId": 601,
            "requirementId": "S1",
            "title": "技术方案完整性",
            "content": "技术方案应包含总体架构、实施计划、风险管控等内容。",
            "mainType": "SCORING",
            "mainTypeLabel": "评分项",
            "subType": "技术评分",
            "reviewType": "评分项审查",
            "vetoRisk": false,
            "judgement": "方案齐全，扣 1 分因风险章节偏简。",
            "suggestedScore": 9.0,
            "fullScore": 10.0,
            "deductionReason": "风险管控章节描述偏简",
            "bidEvidence": [
              {
                "fragmentId": 9101,
                "page": 28,
                "quote": "四、技术方案……总体架构……实施计划……"
              }
            ],
            "note": null,
            "llmRawJson": null
          },
          {
            "id": 1004,
            "requirementItemId": 602,
            "requirementId": "S2",
            "title": "类似业绩",
            "content": "近三年类似项目业绩，每项计分规则以评分表为准。",
            "mainType": "SCORING",
            "mainTypeLabel": "评分项",
            "subType": "业绩评分",
            "reviewType": "评分项审查",
            "vetoRisk": false,
            "judgement": "清单未给出满分，暂按证据酌定。",
            "suggestedScore": null,
            "fullScore": null,
            "deductionReason": null,
            "bidEvidence": [],
            "note": null,
            "llmRawJson": null
          }
        ]
      },
      {
        "packageId": 81,
        "supplier": "南方工程股份有限公司",
        "suggestedScoreTotal": 0.0,
        "fullScoreTotal": 10.0,
        "qualificationResponses": [
          {
            "id": 1101,
            "requirementItemId": 501,
            "requirementId": "Q1",
            "title": "资质等级证书",
            "content": "投标人须具有有效的信息系统建设和服务能力等级证书（CS）二级及以上。",
            "mainType": "QUALIFICATION",
            "mainTypeLabel": "资格要求",
            "subType": "资质证书",
            "reviewType": "响应性审查",
            "status": "不符合",
            "vetoRisk": true,
            "judgement": "仅提供一级以下或过期材料，不满足二级及以上。",
            "bidEvidence": [
              {
                "fragmentId": 9201,
                "page": 8,
                "quote": "资质证书有效期至 2024 年……"
              }
            ],
            "note": null,
            "llmRawJson": null
          },
          {
            "id": 1102,
            "requirementItemId": 502,
            "requirementId": "Q2",
            "title": "项目经理资格",
            "content": "拟派项目经理须具备相关专业中级及以上职称，并提供社保证明。",
            "mainType": "QUALIFICATION",
            "mainTypeLabel": "资格要求",
            "subType": "人员要求",
            "reviewType": "响应性审查",
            "status": "符合",
            "vetoRisk": false,
            "judgement": "项目经理职称与社保证明齐全。",
            "bidEvidence": [
              {
                "fragmentId": 9205,
                "page": 15,
                "quote": "项目经理张某，工程师职称……社保缴纳证明……"
              }
            ],
            "note": null,
            "llmRawJson": null
          }
        ],
        "scoringResponses": [
          {
            "id": 1103,
            "requirementItemId": 601,
            "requirementId": "S1",
            "title": "技术方案完整性",
            "content": "技术方案应包含总体架构、实施计划、风险管控等内容。",
            "mainType": "SCORING",
            "mainTypeLabel": "评分项",
            "subType": "技术评分",
            "reviewType": "评分项审查",
            "vetoRisk": false,
            "judgement": "未检索到相关响应证据，建议分 0。",
            "suggestedScore": 0.0,
            "fullScore": 10.0,
            "deductionReason": "无有效响应证据",
            "bidEvidence": [],
            "note": null,
            "llmRawJson": null
          },
          {
            "id": 1104,
            "requirementItemId": 602,
            "requirementId": "S2",
            "title": "类似业绩",
            "content": "近三年类似项目业绩，每项计分规则以评分表为准。",
            "mainType": "SCORING",
            "mainTypeLabel": "评分项",
            "subType": "业绩评分",
            "reviewType": "评分项审查",
            "vetoRisk": false,
            "judgement": "清单未给出满分，暂无评分。",
            "suggestedScore": null,
            "fullScore": null,
            "deductionReason": null,
            "bidEvidence": [],
            "note": null,
            "llmRawJson": null
          }
        ]
      }
    ]
  }
}
```

### 4.4 前端展示建议

1. 用 `packages[]` 渲染「投标单位」Tab/卡片（`supplier` + `packageId`；卡片建议分用 `suggestedScoreTotal`/`fullScoreTotal`）  
2. 左栏题干用同一条清单的 `title` / `content` / `mainTypeLabel` / `subType`（各包相同，可用第一包或按 `requirementId` 对齐）  
3. 右栏结论按包：资格看 `status`；评分看 `suggestedScore`（`fullScore == null` →「暂无评分」）  
4. 符合/不符合/无法判断数由前端按**当前包**的 `qualificationResponses` 自行统计（**不要用** `overallVerdict`）  
5. 点击证据：`bidEvidence[].fragmentId` / `page` / `quote` 跳转预览  
6. `status !== SUCCESS` 时勿当终态展示（`BUILDING` 轮询；`FAILED` 读 `errorMessage`）

## 后端自动跑什么

```text
① INDEX（向量化）
   - SUCCESS 跳过
   - PROCESSING 不重复入队，等待结束
   - PENDING / FAILED 入队解析
   - 任一文件 FAILED → 整单失败，error 带文件名

② 建清单 BUILD_CHECKLIST
   - 已有 SUCCESS 清单且有条目 → 复用
   - 否则新建并等待 SUCCESS（条目带 requirementId Q1/S1）

③ 智能审查 REVIEW（按包全量：投标包 × 清单条目）
   - 召回仅 bidSearch；重排/对照用 text；LLM=review-v2（产品规则）
   - 资格无证据 → 无法判断；评分无证据 → 建议分 0；fullScore 空 → 暂无评分
   - 详情按包嵌套返回（含 content、subType）；无项目级通过/不通过
   - 全部包审完后写回 reviewId
```

前端**不必**再单独调 index / checklists / reviews（仍保留原接口供调试或分步用）。

## 日志：`logs/ai.log`

专用 Logger：`ai.pipeline`。按项目记关键节点，例如：

```text
projectId=10 pipelineJobId=501 phase=START | 一键审查已入队 ...
projectId=10 pipelineJobId=501 phase=INDEX | 入队 documentId=9 fileName=招标文件.pdf
projectId=10 pipelineJobId=501 phase=INDEX | 全部解析成功 files=3
projectId=10 pipelineJobId=501 phase=CHECKLIST | 复用清单 checklistId=88 ...
projectId=10 pipelineJobId=501 phase=REVIEW | 已创建审查 reviewId=201 ...
projectId=10 pipelineJobId=501 phase=DONE | 审查完成 reviewId=201 checklistId=88
```

失败时 `phase=INDEX|CHECKLIST|REVIEW|ERROR`，便于按 `projectId` 排查。

## 配置（可选）

| 配置 / 环境变量                             | 默认    | 说明                |
| ------------------------------------------- | ------- | ------------------- |
| `bidding.ai-review-pipeline.worker-enabled` | true    | 总流程 Worker       |
| `AI_REVIEW_PIPELINE_POLL_MS`                | 5000    | 认领间隔            |
| `AI_REVIEW_PIPELINE_WAIT_POLL_MS`           | 3000    | 等待子任务轮询间隔  |
| `AI_REVIEW_PIPELINE_INDEX_WAIT_TIMEOUT_MS`  | 7200000 | 向量化超时（约 2h） |
| `AI_REVIEW_PIPELINE_CHILD_WAIT_TIMEOUT_MS`  | 7200000 | 建清单/审查超时     |

## 与项目列表状态

总任务 `PENDING`/`RUNNING` 时，项目 `workflowStatus` 为 `AI_REVIEWING`。

删投标包或删招标文件时：若有进行中的 INDEX/建清单/审查/一键审查任务会 **409**；否则将相关审查（及招标文件对应的清单）标为 `OBSOLETE`，`workflowStatus` 回到 `READY_FOR_AI_REVIEW`（或待补文件），需重新点一键审查。
