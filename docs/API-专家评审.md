# 招投标后端 · API（专家评审）

> 本册范围：**项目级专家评审**。  
> 项目完成 AI 审查后，招标方先维护专家名单，再推送专家评审；专家登录后直接进入当前唯一任务详情。  
> 账号 / 组织 / 项目见：[账号组织项目](./API-账号组织项目.md)  
> 文件 / 标包见：[文件与标包](./API-文件与标包.md)  
> AI 审查见：[检索 / 重排 / LLM](./API-检索重排与LLM.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-08-20

## 通用约定（摘要）

- 统一响应：`{ code, message, data }`，成功 `code=0`。
- 鉴权：`Authorization: Bearer <accessToken>`。
- 项目端专家评审接口角色：仅 `TENDERER` 可访问。
- 专家侧任务接口角色：仅 `EXPERT` 可访问。
- 跨组织资源一律 **40400**（不暴露是否存在）。
- 本册当前已实现：添加专家、推送前移除专家、推送专家评审、两步式追加专家、重置临时专家密码、项目专家评测聚合、专家当前任务详情、保存单条评审、专家评审附件上传 / 列表 / 预览 / 删除、提交专家评审、截止时间自动提交、评审完成后的临时专家账号禁用、项目删除联动清理、导出专家评审报告、管理端确认废标、管理端继续展示。

---

## 1. 能力总览

| 能力 | 说明 |
|------|------|
| 添加专家 | 招标方按专家姓名创建临时 `EXPERT` 账号，并一次性返回初始密码 |
| 推送前移除专家 | 尚未推送的 `BUILDING` 专家成员可移除，同时删除对应临时账号 |
| 推送评审 | 项目已有专家后，填写截止时间和备注，正式进入 `RUNNING` |
| 冻结待评项 | 推送时从最新成功 AI 审查中生成专家评审明细 |
| 追加专家 | 已推送后先生成待追加专家账号，确认追加时复用首次推送冻结出的待评项 |
| 专家当前任务 | 专家登录后直接取当前唯一 `RUNNING` 任务，不展示任务列表 |
| 按公司查看 | 当前任务详情按投标包 / 投标单位分组返回待评条目 |
| 保存评审 | 专家保存单条资格项或评分项 |
| 图片附件 | 专家在单条评审项下上传、浏览和删除图片附件 |
| 提交评审 | 专家提交整份任务；提交后进入只读 |
| 自动提交 | 到达截止时间后，后台自动收口未提交专家任务 |
| 只读态 | 服务端返回 `readOnly`，前端据此禁用保存 / 提交入口 |
| 重置密码 | 招标方重置项目内临时专家密码，并一次性返回新密码 |
| 完成禁用 | 所有专家提交或逾期自动提交完成后，自动禁用本次评审的临时专家账号 |
| 专家评测聚合 | 项目详情页专家评测标签页：专家提交情况、公司状态、评分对比、排行、待确认废标 |
| 确认废标 | 管理端确认待确认废标公司，状态进入 `VETOED` |
| 继续展示 | 管理端选择继续展示，后续同公司废标建议不再弹确认 |

---

## 2. 数据状态

### 2.1 专家评审状态

| status | 说明 |
|--------|------|
| `BUILDING` | 已创建评审主记录，尚未推送 |
| `RUNNING` | 专家评审中 |
| `SUCCESS` | 专家评审完成；所有专家提交后进入 |
| `FAILED` | 专家评审失败，后续实现 |
| `OBSOLETE` | 已作废，不作为当前评审 |

项目列表 / 详情的 `workflowStatus` 会把已推送专家评审映射为：

| workflowStatus | 前端文案 | 来源 |
|----------------|----------|------|
| `EXPERT_REVIEWING` | 专家评审中 | 最新已推送专家评审 `status=RUNNING` |
| `EXPERT_REVIEW_SUCCESS` | 专家评审完成 | 最新已推送专家评审 `status=SUCCESS` |

### 2.2 专家成员状态

| status | 说明 |
|--------|------|
| `ACTIVE` | 可评审 |
| `PENDING_APPEND` | 已生成追加专家账号，但尚未确认追加；不进入专家任务、统计和项目专家列表 |
| `READ_ONLY` | 只读；专家提交后使用 |
| `DISABLED` | 已禁用；所有专家提交完成后自动进入，专家账号不可再登录 |
| `DELETED` | 已删除 |

### 2.3 专家提交状态

| submitStatus | 说明 |
|--------------|------|
| `NOT_SUBMITTED` | 未提交 |
| `SUBMITTED` | 已提交 |
| `OVERDUE_SUBMITTED` | 超时自动提交 |

### 2.4 公司状态

| companyStatus | 说明 |
|---------------|------|
| `NORMAL` | 正常展示 |
| `PENDING_VETO` | 待确认废标 |
| `VETOED` | 已废标 |

| displayMode | 说明 |
|-------------|------|
| `NORMAL` | 正常展示 |
| `PENDING_CONFIRMATION` | 等待管理员确认 |
| `CONTINUE_DISPLAY` | 已选择继续展示 |

### 2.5 待评条目类型

| itemType | 说明 |
|----------|------|
| `QUALIFICATION` | 资格项；当前只取 AI 审查结果为 `无法判断` 的条目 |
| `SCORING` | 评分项；当前全部进入专家评审，专家分值初始为空 |

---

## 3. 项目端专家评审

以下接口均需 `TENDERER`。

### 3.1 添加专家

`POST /api/projects/{projectId}/expert-reviews/members`

按专家姓名创建临时专家账号，并加入当前项目的专家评审成员。若项目还没有 `BUILDING` 专家评审主记录，后端会自动创建。

**请求体**

```json
{
  "displayName": "张三"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `displayName` | string | 是 | 专家姓名；去首尾空格后不能为空，最长 128 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "memberId": 88,
    "userId": 77,
    "username": "expert_x",
    "displayName": "张三",
    "temporaryPassword": "Temp1234!",
    "appended": false
  }
}
```

| 字段 | 说明 |
|------|------|
| `reviewId` | 专家评审主记录 ID |
| `memberId` | 专家评审成员 ID |
| `userId` | 临时专家账号用户 ID |
| `username` | 临时专家登录账号 |
| `temporaryPassword` | 初始密码，只在创建接口返回一次 |
| `appended` | 是否为推送后的追加专家；添加阶段固定为 `false` |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 项目尚未完成 AI 审查 / 专家姓名为空或过长 / 专家评审已开始，应使用追加专家 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 |
| 40900 | 该专家已添加 / 项目专家评审已完成 |

---

### 3.1.1 推送前移除专家

`DELETE /api/projects/{projectId}/expert-reviews/members/{memberId}`

招标方移除尚未正式纳入评审的专家。支持两种场景：

- `BUILDING` 状态移除推送前添加的 `ACTIVE` 专家。
- `RUNNING` 状态移除追加弹窗中已生成账号、但尚未确认追加的 `PENDING_APPEND` 专家。

后端会将专家成员标记为 `DELETED`，删除对应临时 `EXPERT` 账号，并刷新正式专家人数。后续推送 / 确认追加时，只会纳入未删除专家。

**请求体**

无。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": null
}
```

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 / 专家评审不存在 / 专家成员不存在 |
| 40900 | 当前专家评审状态不能移除专家 / 追加专家已确认，不能移除 / 专家成员项目不匹配 / 专家成员状态不支持移除 / 状态已变化 |

---

### 3.2 推送专家评审

`POST /api/projects/{projectId}/expert-reviews/push`

正式启动专家评审。推送时后端会：

- 校验项目最新 AI 审查已成功。
- 校验已经添加至少一名专家。
- 读取项目下投标包，生成专家评审公司快照。
- 从最新成功 AI 审查中冻结待评项：
  - 资格项：仅 `mainType=QUALIFICATION` 且 `status=无法判断`
  - 评分项：全部 `mainType=SCORING`
- 为每位专家生成一份待评明细。

**请求体**

```json
{
  "deadlineAt": "2026-08-20T09:00:00Z",
  "pushMessage": "请按要求完成专家评审"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `deadlineAt` | string | 是 | ISO 时间；评审截止时间 |
| `pushMessage` | string | 否 | 推送备注；空字符串会按 `null` 保存 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "projectId": 100,
    "projectName": "投标项目A",
    "status": "RUNNING",
    "deadlineAt": "2026-08-20T09:00:00Z",
    "pushMessage": "请按要求完成专家评审",
    "expertCount": 2,
    "reviewItemCount": 12
  }
}
```

| 字段 | 说明 |
|------|------|
| `reviewId` | 专家评审主记录 ID |
| `status` | 推送后为 `RUNNING` |
| `expertCount` | 本次评审专家数 |
| `reviewItemCount` | 单个专家的待评条目模板数，不乘以专家数 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 未添加专家 / 项目下没有投标包 / AI 审查没有需要专家评审的条目 / 项目尚未完成 AI 审查 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 |
| 40900 | 专家评审已推送 / 专家评审状态已变化 |

---

### 3.3 生成追加专家账号

`POST /api/projects/{projectId}/expert-reviews/append`

项目已进入专家评审后，先按专家姓名创建临时专家账号。该账号状态为 `PENDING_APPEND`，不会进入专家任务、项目专家统计和项目专家列表；只有确认追加后才会正式进入评审。

**请求体**

```json
{
  "displayName": "李四"
}
```

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "memberId": 89,
    "userId": 78,
    "username": "expert_y",
    "displayName": "李四",
    "temporaryPassword": "Temp5678!",
    "appended": true
  }
}
```

字段说明同 [3.1 添加专家](#31-添加专家)。

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 请先推送专家评审 / 项目尚未完成 AI 审查 / 专家姓名为空或过长 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 |
| 40900 | 当前项目未处于专家评审中 / 该专家已添加 |

---

### 3.3.1 确认追加专家

`POST /api/projects/{projectId}/expert-reviews/append/push`

确认追加弹窗中已经生成账号的专家。后端会读取当前评审下全部 `PENDING_APPEND` 专家，复用首次推送冻结出的待评项，为这些专家生成评审明细，并将成员状态改为 `ACTIVE`。

**请求体**

无。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "projectId": 100,
    "status": "RUNNING",
    "appendedExpertCount": 2,
    "expertCount": 4,
    "reviewItemCount": 20
  }
}
```

| 字段 | 说明 |
|------|------|
| `appendedExpertCount` | 本次确认追加的专家数量 |
| `expertCount` | 确认追加后的正式专家总数，不包含未确认追加账号 |
| `reviewItemCount` | 本次为追加专家生成的评审明细数量 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 请先推送专家评审 / 项目尚未完成 AI 审查 / 请先生成追加专家账号 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 |
| 40900 | 当前项目未处于专家评审中 / 专家评审待评项尚未生成 / 追加专家评审明细生成数量不一致 / 追加专家状态已变化 / 专家评审状态已变化 |

---

### 3.4 重置临时专家密码

`POST /api/projects/{projectId}/expert-reviews/members/{memberId}/reset-password`

招标方重置当前项目内临时专家账号密码。后端会校验专家成员属于当前项目，并校验目标账号仍是可重置的专家账号。新密码只在本次响应返回一次；重置成功后旧 token 失效。

**请求体**

无。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "projectId": 100,
    "memberId": 88,
    "userId": 77,
    "username": "expert_77",
    "displayName": "张三",
    "temporaryPassword": "NewTemp123!"
  }
}
```

| 字段 | 说明 |
|------|------|
| `reviewId` | 专家评审主记录 ID |
| `projectId` | 项目 ID |
| `memberId` | 专家评审成员 ID |
| `userId` | 临时专家账号用户 ID |
| `username` | 临时专家登录账号 |
| `displayName` | 专家姓名 |
| `temporaryPassword` | 新临时密码，只在重置接口返回一次 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 / 专家评审不存在 / 专家成员不存在 / 专家账号不存在 |
| 40900 | 专家成员项目不匹配 / 当前账号不是可重置的专家账号 |

---

### 3.5 项目专家评测详情

`GET /api/projects/{projectId}/expert-reviews`

项目详情页“专家评测”标签页聚合数据。若项目还没有专家评审记录，成功返回 `data=null`；前端可据此展示未发起专家评审。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "projectId": 100,
    "projectName": "投标项目A",
    "projectStatus": "ACTIVE",
    "status": "RUNNING",
    "deadlineAt": "2026-08-20T09:00:00Z",
    "pushMessage": "请按要求完成专家评审",
    "expertCount": 2,
    "submittedExpertCount": 1,
    "submittedCompanyCount": 3,
    "hasAnyCompleteSubmission": true,
    "locked": true,
    "expertMembers": [
      {
        "reviewId": 501,
        "memberId": 88,
        "userId": 77,
        "username": "expert_77",
        "displayName": "张三",
        "status": "READ_ONLY",
        "submitStatus": "SUBMITTED",
        "submittedAt": "2026-08-18T02:00:00Z",
        "submitted": true
      }
    ],
    "companyTabs": [
      {
        "companyId": 301,
        "bidPackageId": 81,
        "supplierName": "投标单位A",
        "companyStatus": "NORMAL",
        "displayMode": "NORMAL",
        "vetoItemCount": 0,
        "vetoReasonSummary": null,
        "vetoExpertNames": null,
        "averageScore": 9.00,
        "rank": 1,
        "confirmedBy": null,
        "confirmedAt": null,
        "reviewItemCount": 1,
        "participated": true
      }
    ],
    "scoreCompareItems": [
      {
        "companyId": 301,
        "bidPackageId": 81,
        "supplierName": "投标单位A",
        "companyStatus": "NORMAL",
        "displayMode": "NORMAL",
        "averageScore": 9.00,
        "rank": 1,
        "reviewItemCount": 1,
        "participated": true,
        "expertScores": [
          {
            "memberId": 88,
            "username": "expert_77",
            "displayName": "张三",
            "score": 9.00
          }
        ]
      }
    ],
    "itemReviewGroups": [
      {
        "companyId": 301,
        "bidPackageId": 81,
        "supplierName": "投标单位A",
        "requirementItemId": 9002,
        "sourceReviewItemId": 701,
        "itemType": "SCORING",
        "requirementCode": "S1",
        "title": "技术方案评分",
        "content": "根据技术方案完整性进行评分",
        "aiStatus": "DONE",
        "aiVetoRisk": false,
        "aiJudgement": "方案完整性较好",
        "aiSuggestedScore": 9.5,
        "aiDeductionReason": "部分实施细节不够充分",
        "fullScore": 10,
        "averageScore": 8.75,
        "sortOrder": 20,
        "expertReviews": [
          {
            "memberId": 88,
            "userId": 77,
            "username": "expert_77",
            "displayName": "张三",
            "memberStatus": "READ_ONLY",
            "submitStatus": "SUBMITTED",
            "submitted": true,
            "itemId": 801,
            "result": "SCORE",
            "scoreValue": 9.5,
            "comment": "方案较完整，实施细节略少",
            "veto": false,
            "vetoReason": null,
            "submittedAt": "2026-08-18T02:00:00Z",
            "attachments": []
          }
        ]
      }
    ],
    "averageRanking": [
      {
        "rank": 1,
        "companyId": 301,
        "bidPackageId": 81,
        "supplierName": "投标单位A",
        "companyStatus": "NORMAL",
        "displayMode": "NORMAL",
        "averageScore": 9.00
      }
    ],
    "vetoPendingItems": [
      {
        "companyId": 302,
        "bidPackageId": 82,
        "supplierName": "投标单位B",
        "vetoItemCount": 1,
        "vetoReasonSummary": "资格不符合",
        "vetoExpertNames": "张三"
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| `submittedCompanyCount` | 已完成评审的参与投标单位数；只统计有专家待评项的投标单位 |
| `hasAnyCompleteSubmission` | 是否已有任一专家完成整份提交；为 `false` 时前端不展示评分对比、统计表格和综合排行 |
| `expertMembers` | 专家提交情况；包含姓名、账号、成员状态、提交状态、提交时间 |
| `companyTabs` | 投标单位标签页状态；包含正常、待确认废标、已废标、继续展示、是否参与专家评审 |
| `scoreCompareItems` | 评分对比数据；按投标单位展示已提交专家总分和是否参与专家评审 |
| `itemReviewGroups` | 逐项专家评审明细；按投标单位 + 资格 / 评分项分块，块内返回每位专家的结论、分值、建议和附件 |
| `averageRanking` | 平均分排行；已确认废标公司不进入排行 |
| `vetoPendingItems` | 待确认废标列表；只包含 `PENDING_VETO / PENDING_CONFIRMATION` 公司 |

**companyTabs[] / scoreCompareItems[] 参与评审字段**

| 字段 | 说明 |
|------|------|
| `reviewItemCount` | 当前投标单位实际生成的专家待评项数量，按资格 / 评分项去重后统计 |
| `participated` | 是否参与专家评审；`false` 表示该投标单位没有从 AI 审查生成资格要求或评分项，前端提交评审结果时不应要求专家填写该单位 |

**统计规则**

- 平均分 = 每位已提交专家对同一投标单位的所有 `SCORING` 分值求和，再对专家总分求平均，保留 2 位小数。
- `itemReviewGroups[].averageScore` = 同一评分项下已提交专家的 `scoreValue` 平均值，资格项为 `null`。
- 已确认废标公司保留在 `companyTabs` / `scoreCompareItems` 中，但 `averageScore`、`rank` 为空，且 `expertScores` 为空。
- 未参与专家评审的投标单位保留在 `companyTabs` / `scoreCompareItems` 中，`reviewItemCount=0`、`participated=false`，不进入逐项明细、平均分、排行和完成判断。
- 未提交专家不参与平均分、评分对比和排行。
- 已提交专家若因资格项废标或逾期提交导致部分评分项为空，明细中 `result` / `scoreValue` 保持 `null`，该专家不参与对应投标单位平均分、评分对比和排行。
- `itemReviewGroups[].expertReviews` 会返回每位专家一行；未提交专家保留行数据，`submitStatus=NOT_SUBMITTED`，评审结果字段按数据库当前值返回。

**itemReviewGroups[] 字段**

| 字段 | 说明 |
|------|------|
| `companyId` | 专家评审公司快照 ID |
| `bidPackageId` | 投标包 ID |
| `supplierName` | 投标单位名称 |
| `requirementItemId` | 招标要求条目 ID |
| `sourceReviewItemId` | 来源 AI 审查条目 ID |
| `itemType` | `QUALIFICATION` / `SCORING` |
| `requirementCode` | 条目标识 |
| `title` | 条目标题 |
| `content` | 条目正文 |
| `aiStatus` | AI 审查原始状态 |
| `aiVetoRisk` | AI 是否提示废标风险 |
| `aiJudgement` | AI 判断说明 |
| `aiSuggestedScore` | AI 建议分，仅供参考 |
| `aiDeductionReason` | AI 扣分原因，仅供参考 |
| `fullScore` | 满分；资格项可为 `null` |
| `averageScore` | 本评分项专家平均分；资格项为 `null` |
| `sortOrder` | 条目排序 |
| `expertReviews` | 该条目下每位专家的评审结果 |

**expertReviews[] 字段**

| 字段 | 说明 |
|------|------|
| `memberId` | 专家成员 ID |
| `userId` | 专家账号 ID |
| `username` | 专家账号 |
| `displayName` | 专家姓名 |
| `memberStatus` | 专家成员状态 |
| `submitStatus` | 专家提交状态 |
| `submitted` | 是否已提交或逾期自动提交 |
| `itemId` | 当前专家自己的评审明细 ID |
| `result` | 资格项为 `PASS` / `FAIL`，评分项为 `SCORE`；未填为 `null` |
| `scoreValue` | 专家评分；资格项或未填为 `null` |
| `comment` | 专家建议 |
| `veto` | 是否建议废标 |
| `vetoReason` | 废标原因 |
| `submittedAt` | 条目提交时间 |
| `attachments` | 当前专家在该评审项下上传的图片附件 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 |

---

### 3.5.1 项目端预览专家附件

`GET /api/projects/{projectId}/expert-reviews/items/{itemId}/attachments/{attachmentId}/preview`

招标方在项目详情“专家评测”标签页预览专家上传的图片附件。`itemId` 和 `attachmentId` 取自 `itemReviewGroups[].expertReviews[].attachments[]`。

返回图片二进制，响应不是统一 JSON：

```http
HTTP/1.1 200 OK
Content-Type: image/png
Content-Disposition: inline; filename*=UTF-8''%E7%8E%B0%E5%9C%BA%E7%85%A7%E7%89%87.png
```

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 / 专家评审不存在 / 专家评审条目不存在 / 专家评审附件不存在 |
| 40900 | 专家评审条目项目不匹配 |

---

### 3.6 管理端确认废标

`POST /api/projects/{projectId}/expert-reviews/companies/{companyId}/veto/confirm`

确认当前公司废标。只允许处理已经进入 `PENDING_VETO / PENDING_CONFIRMATION` 的公司；未产生专家废标建议、已确认废标、已选择继续展示的公司都会返回冲突。

**请求体**

无。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "projectId": 100,
    "companyId": 301,
    "bidPackageId": 81,
    "supplierName": "投标单位A",
    "companyStatus": "VETOED",
    "displayMode": "NORMAL",
    "vetoItemCount": 1,
    "vetoReasonSummary": "核心资格证明缺失",
    "vetoExpertNames": "张三",
    "confirmedBy": 9,
    "confirmedAt": "2026-08-18T02:00:00Z",
    "hasOtherPendingVeto": false
  }
}
```

| 字段 | 说明 |
|------|------|
| `companyStatus` | 确认废标后为 `VETOED` |
| `displayMode` | 确认废标后为 `NORMAL`，不再展示待确认入口 |
| `vetoItemCount` | 当前公司已提交的废标建议条目数 |
| `vetoReasonSummary` | 已提交废标原因摘要 |
| `vetoExpertNames` | 提出废标建议的专家姓名列表 |
| `hasOtherPendingVeto` | 当前评审下是否仍有其他待确认废标公司 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在或不属于当前组织 / 专家评审不存在 / 专家评审公司不存在 |
| 40900 | 专家评审未进入可处理状态 / 当前公司没有待确认废标 / 废标状态已变化 |

---

### 3.7 管理端继续展示

`POST /api/projects/{projectId}/expert-reviews/companies/{companyId}/veto/continue`

管理端选择继续展示当前公司。只允许处理 `PENDING_VETO / PENDING_CONFIRMATION` 的公司；成功后该公司进入 `NORMAL / CONTINUE_DISPLAY`，后续同公司出现新的废标建议时不再弹确认。

**请求体**

无。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "reviewId": 501,
    "projectId": 100,
    "companyId": 301,
    "bidPackageId": 81,
    "supplierName": "投标单位A",
    "companyStatus": "NORMAL",
    "displayMode": "CONTINUE_DISPLAY",
    "vetoItemCount": 1,
    "vetoReasonSummary": "核心资格证明缺失",
    "vetoExpertNames": "张三",
    "confirmedBy": 9,
    "confirmedAt": "2026-08-18T02:10:00Z",
    "hasOtherPendingVeto": true
  }
}
```

字段说明和常见错误同 [3.6 管理端确认废标](#36-管理端确认废标)。

---

## 4. 专家侧任务

以下接口均需 `EXPERT`。

### 4.1 当前任务详情

`GET /api/expert-review-tasks/current`

专家登录后直接返回当前唯一评审任务详情。当前实现返回最新一个 `RUNNING` 或 `SUCCESS` 专家任务，不提供任务列表。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskId": 88,
    "memberId": 88,
    "reviewId": 501,
    "projectId": 100,
    "projectName": "投标项目A",
    "reviewStatus": "RUNNING",
    "memberStatus": "ACTIVE",
    "submitStatus": "NOT_SUBMITTED",
    "deadlineAt": "2026-08-20T09:00:00Z",
    "remainingDays": 3,
    "readOnly": false,
    "submittedAt": null,
    "pushMessage": "请按要求完成专家评审",
    "pusherName": "陈明",
    "companies": [
      {
        "companyId": 301,
        "bidPackageId": 81,
        "supplierName": "投标单位A",
        "companyStatus": "NORMAL",
        "displayMode": "NORMAL",
        "vetoItemCount": 0,
        "averageScore": null,
        "items": [
          {
            "itemId": 801,
            "sourceReviewItemId": 701,
            "requirementItemId": 9002,
            "bidPackageId": 81,
            "itemType": "SCORING",
            "requirementCode": "S1",
            "title": "技术方案评分",
            "content": "根据技术方案完整性进行评分",
            "aiStatus": "DONE",
            "aiVetoRisk": false,
            "aiJudgement": "方案完整性较好",
            "aiSuggestedScore": 9.5,
            "aiDeductionReason": "部分实施细节不够充分",
            "fullScore": 10,
            "evidenceJson": "[{\"fragmentId\":30012,\"page\":18,\"quote\":\"投标技术方案包含总体架构、实施计划...\"}]",
            "tenderEvidence": [
              {
                "fragmentId": 27833,
                "page": 44,
                "quote": "技术方案的先进性、可行性、完整性 | 15分",
                "documentId": 551,
                "fileName": "招标文件.docx",
                "packageId": 424
              }
            ],
            "bidEvidence": [
              {
                "fragmentId": 30012,
                "page": 18,
                "quote": "投标技术方案包含总体架构、实施计划...",
                "documentId": 660,
                "fileName": "投标文件.docx",
                "packageId": 81
              }
            ],
            "result": null,
            "scoreValue": null,
            "comment": null,
            "veto": false,
            "vetoReason": null,
            "submittedAt": null,
            "attachments": [
              {
                "attachmentId": 901,
                "itemId": 801,
                "fileName": "现场照片.png",
                "contentType": "image/png",
                "sortOrder": 1,
                "createdAt": "2026-08-18T03:00:00Z"
              }
            ]
          }
        ]
      }
    ]
  }
}
```

**顶层字段**

| 字段 | 说明 |
|------|------|
| `taskId` | 当前专家任务 ID，当前等同 `memberId` |
| `memberId` | 专家评审成员 ID |
| `reviewId` | 专家评审主记录 ID |
| `projectId` | 项目 ID |
| `projectName` | 项目名称 |
| `reviewStatus` | 专家评审状态 |
| `memberStatus` | 当前专家成员状态 |
| `submitStatus` | 当前专家提交状态 |
| `deadlineAt` | 截止时间 |
| `remainingDays` | 剩余天数；已截止为 `0`，未设截止时间为 `null` |
| `readOnly` | 是否只读；前端保存 / 提交按钮以此为准 |
| `submittedAt` | 当前专家提交时间，未提交为 `null` |
| `pushMessage` | 招标方推送备注 |
| `pusherName` | 推送人姓名；取推送操作人的 `displayName` |
| `companies` | 投标单位 / 投标包列表 |

**companies[] 字段**

| 字段 | 说明 |
|------|------|
| `companyId` | 专家评审公司快照 ID |
| `bidPackageId` | 投标包 ID |
| `supplierName` | 投标单位名称；当前取投标包名称 |
| `companyStatus` | 公司状态 |
| `displayMode` | 展示模式 |
| `vetoItemCount` | 当前公司废标建议数 |
| `averageScore` | 当前公司平均分；提交统计后才会有值 |
| `reviewItemCount` | 当前公司下该专家的待评条目数量 |
| `participated` | 是否参与专家评审；`false` 时 `items=[]`，不阻塞专家提交 |
| `items` | 当前公司下该专家的待评条目 |

**items[] 字段**

| 字段 | 说明 |
|------|------|
| `itemId` | 专家评审明细 ID，后续保存 / 提交会使用 |
| `sourceReviewItemId` | 来源 AI 审查条目 ID，关联 `biz_review_item.id` |
| `requirementItemId` | 招标要求条目 ID，关联 `biz_requirement_item.id` |
| `bidPackageId` | 投标包 ID |
| `itemType` | `QUALIFICATION` / `SCORING` |
| `requirementCode` | 条目标识，如 `Q1` / `S1` |
| `title` | 条目标题 |
| `content` | 条目正文 |
| `aiStatus` | AI 审查原始状态 |
| `aiVetoRisk` | AI 是否提示废标风险 |
| `aiJudgement` | AI 判断说明 |
| `aiSuggestedScore` | AI 建议分，仅供参考 |
| `aiDeductionReason` | AI 扣分原因，仅供参考 |
| `fullScore` | 满分；资格项可为 `null` |
| `evidenceJson` | 投标原文证据 JSON 字符串，保留给旧前端；新页面优先使用 `bidEvidence` |
| `tenderEvidence` | 招标原文证据数组，用于“查看招标原文” |
| `bidEvidence` | 投标原文证据数组，用于“查看投标原文” |
| `result` | 专家评审结果；未保存为 `null` |
| `scoreValue` | 专家评分；评分项初始为 `null` |
| `comment` | 专家建议；未保存为 `null` |
| `veto` | 专家是否建议废标 |
| `vetoReason` | 专家废标原因 |
| `submittedAt` | 当前条目提交时间 |
| `attachments` | 专家上传的图片附件数组 |

**attachments[] 字段**

| 字段 | 说明 |
|------|------|
| `attachmentId` | 附件 ID |
| `itemId` | 所属专家评审明细 ID |
| `fileName` | 原始文件名 |
| `contentType` | 图片 MIME 类型：`image/jpeg` / `image/png` / `image/webp` |
| `sortOrder` | 当前条目下的附件排序 |
| `createdAt` | 上传时间 |

**evidence 数组元素**

| 字段 | 说明 |
|------|------|
| `fragmentId` | 来源 chunk ID，可用于定位片段 |
| `page` | 页码 |
| `quote` | 原文摘录 |
| `documentId` | 文件 ID，可配合预览接口打开原文 |
| `fileName` | 文件名 |
| `packageId` | 标包 ID；投标原文为当前投标包，招标原文为招标包 |

**readOnly 判定**

任一条件满足则 `readOnly=true`：

- 专家评审不是 `RUNNING`
- 专家成员不是 `ACTIVE`
- 当前专家 `submitStatus` 不是 `NOT_SUBMITTED`
- 已超过 `deadlineAt`

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是专家 |
| 40400 | 当前没有专家评审任务 / 专家评审任务不存在 / 项目不存在 |

---

### 4.2 保存单条评审结果

`POST /api/expert-review-tasks/{taskId}/items/{itemId}`

保存当前专家的一条评审结果。只能保存当前登录专家自己的任务；任务已提交、已截止、非 `RUNNING` 时不可保存。

**资格项请求体**

```json
{
  "result": "FAIL",
  "comment": "未见有效资质证明",
  "veto": true,
  "vetoReason": "核心资格证明缺失"
}
```

**评分项请求体**

```json
{
  "scoreValue": 9.5,
  "comment": "方案较完整，实施细节略少"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `result` | string | 资格项必填 | 资格项仅支持 `PASS` / `FAIL`；评分项可不传，后端保存为 `SCORE` |
| `scoreValue` | number | 评分项必填 | 专家评分；必须 `>= 0` 且不能超过 `fullScore` |
| `comment` | string | 否 | 专家建议 |
| `veto` | boolean | 否 | 是否建议废标；仅资格项 `FAIL` 时允许 |
| `vetoReason` | string | 废标时必填 | 废标原因 |

**成功响应**

返回保存后的 `items[]` 单条结构，字段同 [4.1 当前任务详情](#41-当前任务详情)。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "itemId": 801,
    "sourceReviewItemId": 701,
    "requirementItemId": 9002,
    "bidPackageId": 81,
    "itemType": "SCORING",
    "requirementCode": "S1",
    "title": "技术方案评分",
    "content": "根据技术方案完整性进行评分",
    "aiStatus": "DONE",
    "aiVetoRisk": false,
    "aiJudgement": "方案完整性较好",
    "aiSuggestedScore": 9.5,
    "aiDeductionReason": "部分实施细节不够充分",
    "fullScore": 10,
    "evidenceJson": "[{\"fragmentId\":30012,\"page\":18,\"quote\":\"投标技术方案包含总体架构、实施计划...\"}]",
    "tenderEvidence": [
      {
        "fragmentId": 27833,
        "page": 44,
        "quote": "技术方案的先进性、可行性、完整性 | 15分",
        "documentId": 551,
        "fileName": "招标文件.docx",
        "packageId": 424
      }
    ],
    "bidEvidence": [
      {
        "fragmentId": 30012,
        "page": 18,
        "quote": "投标技术方案包含总体架构、实施计划...",
        "documentId": 660,
        "fileName": "投标文件.docx",
        "packageId": 81
      }
    ],
    "result": "SCORE",
    "scoreValue": 9.5,
    "comment": "方案较完整，实施细节略少",
    "veto": false,
    "vetoReason": null,
    "submittedAt": null,
    "attachments": []
  }
}
```

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 请求体为空 / 资格项结果不是 `PASS` / `FAIL` / 资格项传了评分 / 评分项分值为空、小于 0 或超过满分 / 评分项传废标建议 / 废标原因缺失 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是专家 |
| 40400 | 专家评审任务不存在 / 专家评审条目不存在 |
| 40900 | 任务已只读 / 已提交 / 已截止 / 评审状态已变化 |

---

### 4.3 专家评审附件

附件挂在单条专家评审明细下。只能操作当前登录专家自己的任务项；上传 / 删除要求任务仍可编辑，提交后或截止后只允许查看。

#### 4.3.1 上传附件

`POST /api/expert-review-tasks/{taskId}/items/{itemId}/attachments`

`Content-Type: multipart/form-data`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file | 是 | 图片附件，仅支持 `jpg` / `jpeg` / `png` / `webp`；默认单个最大 10MB；每个评审项默认最多 5 个 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "attachmentId": 901,
    "itemId": 801,
    "fileName": "现场照片.png",
    "contentType": "image/png",
    "sortOrder": 1,
    "createdAt": "2026-08-18T03:00:00Z"
  }
}
```

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 未上传文件 / 附件为空 / 附件超出大小 / 文件名无效 / 非图片格式 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是专家 |
| 40400 | 专家评审任务不存在 / 专家评审条目不存在 |
| 40900 | 每项附件数已达上限 / 任务已只读 / 已提交 / 已截止 |

#### 4.3.2 附件列表

`GET /api/expert-review-tasks/{taskId}/items/{itemId}/attachments`

返回当前评审项下附件数组；字段同上文 `attachments[]` 字段说明。提交后只读态仍可查看。

#### 4.3.3 预览附件

`GET /api/expert-review-tasks/{taskId}/items/{itemId}/attachments/{attachmentId}/preview`

专家侧预览当前登录专家自己任务下的附件。招标方项目端预览使用 `GET /api/projects/{projectId}/expert-reviews/items/{itemId}/attachments/{attachmentId}/preview`。

返回图片二进制，响应不是统一 JSON：

```http
HTTP/1.1 200 OK
Content-Type: image/png
Content-Disposition: inline; filename*=UTF-8''%E7%8E%B0%E5%9C%BA%E7%85%A7%E7%89%87.png
```

#### 4.3.4 删除附件

`DELETE /api/expert-review-tasks/{taskId}/items/{itemId}/attachments/{attachmentId}`

仅任务可编辑时允许删除。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": null
}
```

---

### 4.4 提交专家评审

`POST /api/expert-review-tasks/{taskId}/submit`

提交当前专家整份评审。截止时间自动提交由后台任务处理，不调用本接口。

若当前专家已在某个投标单位的资格项保存有效废标建议（`FAIL` + `veto=true` + `vetoReason`），则该投标单位下剩余资格项和评分项允许未填写；其他投标单位仍必须评完。

提交前后端会校验未被有效废标建议豁免的待评项已完成：

- 资格项必须保存 `PASS` 或 `FAIL`。
- 评分项必须保存 `SCORE` 和 `scoreValue`。
- 评分项分值不能超过满分。
- 废标建议必须是资格项 `FAIL` 且带 `vetoReason`。

提交成功后：

- 当前专家成员状态改为 `READ_ONLY`。
- 当前专家提交状态改为 `SUBMITTED`。
- 当前专家所有条目写入 `submittedAt`。
- 刷新公司废标建议汇总；有待处理废标建议时公司进入 `PENDING_VETO / PENDING_CONFIRMATION`。
- 刷新专家评审提交统计；所有专家都提交后，专家评审主状态进入 `SUCCESS`。
- 若本次提交后评审进入 `SUCCESS`，后端会禁用本次评审全部临时专家账号，成员状态统一改为 `DISABLED`，旧 token 失效。

**截止时间自动提交**

- 后台任务扫描 `deadlineAt <= now` 且 `status=RUNNING` 的专家评审。
- 未提交专家的已保存内容按现状提交；未保存条目只写入 `submittedAt`，`result` / `scoreValue` 保持为空。
- 未提交专家成员 `submitStatus` 置为 `OVERDUE_SUBMITTED`。
- 逾期提交同样刷新废标汇总和提交统计；评审进入 `SUCCESS` 后禁用本次评审全部临时专家账号。
- 逾期提交专家会计入提交人数；若某投标单位下存在空评分项，该专家对该投标单位的部分分值不参与平均分和专家分数对比。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskId": 88,
    "memberId": 88,
    "reviewId": 501,
    "projectId": 100,
    "reviewStatus": "RUNNING",
    "memberStatus": "READ_ONLY",
    "submitStatus": "SUBMITTED",
    "submittedAt": "2026-08-18T01:45:00Z",
    "submittedExpertCount": 1,
    "reviewCompleted": false
  }
}
```

| 字段 | 说明 |
|------|------|
| `reviewStatus` | 若所有专家均已提交，返回 `SUCCESS`；否则仍为 `RUNNING` |
| `memberStatus` | 未整体完成时为 `READ_ONLY`；若本次提交后评审完成，返回 `DISABLED` |
| `submitStatus` | 提交后为 `SUBMITTED` |
| `submittedExpertCount` | 当前已提交专家数 |
| `reviewCompleted` | 本次提交后专家评审是否整体完成 |

**常见错误**

| code | 场景 |
|------|------|
| 40000 | 无有效废标建议时存在未完成资格项 / 未完成评分项 / 分值越界 / 废标原因缺失 |
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是专家 |
| 40400 | 专家评审任务不存在 |
| 40900 | 待评项不存在 / 任务已只读 / 已提交 / 已截止 / 任务状态已变化 |

---

## 5. 导出专家评审报告

### 5.1 导出 xlsx 报告

`GET /api/projects/{projectId}/expert-reviews/export`

导出当前项目专家评审聚合结果。响应是 xlsx 二进制附件，不是统一 JSON：

```http
HTTP/1.1 200 OK
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename*=UTF-8''%E9%A1%B9%E7%9B%AE%E5%90%8D-%E4%B8%93%E5%AE%B6%E8%AF%84%E5%AE%A1%E6%8A%A5%E5%91%8A.xlsx
```

工作簿包含：

| sheet | 内容 |
|------|------|
| `概览` | 项目、评审状态、截止时间、提交统计、推送留言 |
| `专家提交` | 专家账号、成员状态、提交状态、提交时间 |
| `投标单位排行` | 投标单位平均分、排名、废标状态和确认信息 |
| `专家评分对比` | 投标单位下各已纳入统计专家的评分 |
| `待确认废标` | 当前仍待管理端确认的废标建议 |

**常见错误**

| code | 场景 |
|------|------|
| 40100 | 未登录或 token 失效 |
| 40300 | 当前用户不是招标方 |
| 40400 | 项目不存在 / 专家评审不存在 |

---

## 6. 隐藏逻辑（本册必读）

- 专家评审只能在项目最新 AI 审查状态为 `SUCCESS` 后发起。
- 添加专家会创建临时 `EXPERT` 账号，`temporaryPassword` 只在添加 / 生成追加账号 / 重置密码接口返回一次。
- 重置密码会生成新临时密码、更新密码 hash 并提升 `token_version`，使旧 token 失效。
- 移除专家只允许处理 `BUILDING` 评审中的 `ACTIVE` 成员，或 `RUNNING` 评审中尚未确认追加的 `PENDING_APPEND` 成员；移除后成员标记为 `DELETED`，临时专家账号从 `sys_user` 物理删除，后续推送 / 确认追加不会带上该成员。
- 生成追加账号只写入 `PENDING_APPEND` 成员，不生成专家评审明细；确认追加成功后才将成员改为 `ACTIVE` 并生成评审明细。
- `PENDING_APPEND` 成员不返回给项目专家列表、专家当前任务和提交统计；若专家评审完成时仍存在未确认追加账号，会清理对应临时账号。
- 推送前必须至少添加一名专家。
- 推送时 `reviewItemCount` 是单个专家的待评条目模板数，不是总插入行数。
- 待评项来源于最新成功 AI 审查：资格项只取 `无法判断`，评分项全部进入。
- 专家侧评分项 `scoreValue` 初始为空；`aiSuggestedScore` 和 `aiDeductionReason` 仅为参考。
- 评分项保存后 `result` 固定为 `SCORE`；资格项仅允许 `PASS` / `FAIL`。
- 专家评审附件挂在单条评审明细下，仅支持 `jpg` / `jpeg` / `png` / `webp` 图片；上传 / 删除只允许可编辑任务，提交后只读态仍可浏览。
- 提交后专家成员状态先进入 `READ_ONLY`，提交状态为 `SUBMITTED`，再次进入当前任务详情时 `readOnly=true`。
- 提交后会按已提交 `isVeto=true` 的资格项刷新公司废标汇总。
- 当前专家某个投标单位下任意资格项保存有效废标建议后，该投标单位剩余资格项和评分项不再阻塞提交；其他投标单位仍必须评完。
- 公司未选择继续展示且存在废标建议时，进入 `PENDING_VETO / PENDING_CONFIRMATION`。
- 确认废标只处理 `PENDING_VETO / PENDING_CONFIRMATION`，成功后进入 `VETOED / NORMAL`。
- 继续展示只处理 `PENDING_VETO / PENDING_CONFIRMATION`，成功后进入 `NORMAL / CONTINUE_DISPLAY`，后续同公司新废标建议不再弹确认。
- 截止时间自动提交由 `bidding.expert-review.deadline-worker-enabled` 控制；生产 API 默认关闭，local 单进程调试和独立 Worker 进程开启。
- 所有专家都提交或逾期自动提交后，专家评审主状态进入 `SUCCESS`，本次评审全部临时专家账号改为 `DISABLED` 并提升 `token_version`。
- `SUBMITTED` 和 `OVERDUE_SUBMITTED` 都会计入专家提交人数；逾期提交中未填完整的投标单位不使用该专家的部分分值参与平均分和专家分数对比。
- 项目专家评测聚合接口的平均分实时按已提交专家评分明细计算，不依赖公司表 `average_score` 冗余字段。
- `hasAnyCompleteSubmission=false` 时前端不展示评分对比、统计表格和综合排行。
- 项目专家评测聚合接口新增 `itemReviewGroups`，用于按“投标单位 + 资格 / 评分项”展示每位专家的单项结论、评分、建议、废标原因和附件。
- 已确认废标公司不展示平均分，不进入 `averageRanking`。
- 未从 AI 审查生成资格要求或评分项的投标单位会返回 `participated=false`、`reviewItemCount=0`；该投标单位不需要专家填写，不阻塞专家提交或项目通过评审结果。
- 导出专家评审报告复用项目专家评测聚合结果，返回 xlsx 二进制附件。
- 专家当前任务只返回一个最新 `RUNNING` 或 `SUCCESS` 任务；没有任务列表接口。
- 专家当前任务返回 `pusherName`，取正式推送专家评审时的操作人姓名。
- 删除项目时会同步清理专家评审：`biz_expert_review.status` 和 `biz_expert_review_member.status` 标为 `DELETED`，临时专家账号从 `sys_user` 物理删除；公司、条目、附件明细保留在已删除评审下作为历史数据。

---

## 7. 更新记录

| 日期 | 说明 |
|------|------|
| 2026-08-24 | 项目专家评测聚合和专家当前任务增加 `participated` / `reviewItemCount`；未生成待评项的投标单位不参与完成判断 |
| 2026-08-21 | 调整专家提交规则：有效废标建议只豁免同一投标单位，其他投标单位仍必须评完 |
| 2026-08-20 | 调整专家提交规则：任意资格项有效废标后，该专家可直接提交项目评审 |
| 2026-08-20 | 新增项目端专家附件预览接口 `GET /api/projects/{projectId}/expert-reviews/items/{itemId}/attachments/{attachmentId}/preview` |
| 2026-08-20 | 追加专家改为两步式：先生成 `PENDING_APPEND` 账号，再确认追加生成评审明细 |
| 2026-08-19 | 项目专家评测聚合接口新增 `itemReviewGroups`，支持逐项展示每位专家的结论、评分、建议和附件 |
| 2026-08-19 | 新增专家侧评审附件上传、列表、图片预览和删除接口，并在当前任务详情 / 保存响应中返回 `attachments` |
| 2026-08-19 | 新增推送前移除专家接口，移除后同步删除临时专家账号并刷新专家人数 |
| 2026-08-18 | 新增保存单条评审、提交专家评审、截止时间自动提交、项目专家评测聚合、导出专家评审报告、管理端确认废标、继续展示、重置临时专家密码接口，以及评审完成后的临时专家账号禁用和项目删除联动清理 |
| 2026-08-17 | 初版：添加专家、推送、追加专家、专家当前任务详情 |
