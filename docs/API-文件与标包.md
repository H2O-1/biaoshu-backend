# 招投标后端 · API（文件 / 标包）

> 本册范围：**招标包 / 投标包、标包文件上传下载预览删除、历史项目复制**。  
> 索引发起与任务轮询、召回/重排/建清单/审查见：[检索 / 重排 / LLM](./API-检索重排与LLM.md)  
> 账号 / 组织 / 项目见：[账号组织项目](./API-账号组织项目.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-07-30

## 通用约定（摘要）

- 统一响应：`{ code, message, data }`，成功 `code=0`。
- 鉴权：`Authorization: Bearer <accessToken>`。
- 本册业务接口角色：**仅 `TENDERER`**；平台管理员无招标方角色 → **403**。
- 跨组织资源一律 **40400**（不暴露是否存在）。
- CORS：前端 Origin 须在允许列表；改端口需改 `CORS_ALLOWED_ORIGINS` 并重启。

---

## 7. 标包 Packages

> 仅角色含 `TENDERER` 的用户。平台管理员无招标方角色时 **403**。  
> 挂在当前用户所属组织；`orgId` 由服务端取自登录态。  
> **创建时均不绑项目**（`projectId` 为空）：  
> - 招标包：创建项目时选绑（见 [账号组织项目 · 项目](./API-账号组织项目.md)）  
> - 投标包：在项目里添加时选绑  
> `fileCount` 为未删除文件真实数量。  
> `name`：招标=包名；投标=投标公司名称（同组织未删除唯一）。

### 标包摘要字段（列表 / 详情 / 创建 / 更新 共用）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | number | 标包 ID |
| orgId | number | 所属组织 |
| projectId | number \| null | 已绑项目 ID；未绑为 `null` |
| type | string | `TENDER` / `BID` |
| name | string | 招标=包名；投标=公司名 |
| contactName | string \| null | 负责人（投标有意义；招标多为 null） |
| contactPhone | string \| null | 负责人电话 |
| companyAddress | string \| null | 公司地址（仅投标；可选） |
| priceWithTax | number \| null | 含税价格（仅投标；可选） |
| taxRate | number \| null | 税率，如 `13` 表示 13%（仅投标；可选） |
| priceWithoutTax | number \| null | 不含税价（前端计算后传入；仅投标；可选） |
| status | string | `ACTIVE`（列表不返回已删） |
| createdBy | number | 创建人用户 ID |
| createdAt | string | ISO 时间 |
| updatedAt | string | ISO 时间 |
| fileCount | number | 包内未删除文件数 |
| indexStatus | string\|null | 包内文件解析汇总：`PENDING`/`PROCESSING`/`SUCCESS`/`FAILED`；无文件为 null。多文件取最差：FAILED &gt; PROCESSING &gt; PENDING &gt; SUCCESS |

### 7.1 标包分页列表

`GET /api/packages`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| type | string | 否 | `TENDER` | `TENDER` / `BID` |
| keyword | string | 否 | `""` | 名称模糊搜 |
| unboundOnly | boolean | 否 | false | `true`=只返回未绑定项目的 |
| page | number | 否 | 1 | 页码 |
| size | number | 否 | 20 | 每页条数，最大 100 |

已删除不返回。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 50,
        "orgId": 2,
        "projectId": null,
        "type": "TENDER",
        "name": "市政招标文件包",
        "contactName": null,
        "contactPhone": null,
        "status": "ACTIVE",
        "createdBy": 10,
        "createdAt": "2026-07-22T06:00:00Z",
        "updatedAt": "2026-07-22T06:00:00Z",
        "fileCount": 2
      },
      {
        "id": 60,
        "orgId": 2,
        "projectId": 10,
        "type": "BID",
        "name": "华建集团",
        "contactName": "王五",
        "contactPhone": "13800001111",
        "companyAddress": "上海市浦东新区示例路 1 号",
        "priceWithTax": 1130000.00,
        "taxRate": 13,
        "priceWithoutTax": 1000000.00,
        "status": "ACTIVE",
        "createdBy": 10,
        "createdAt": "2026-07-22T06:10:00Z",
        "updatedAt": "2026-07-22T06:10:00Z",
        "fileCount": 3
      }
    ],
    "total": 2,
    "page": 1,
    "size": 20
  }
}
```

### 7.2 创建标包

`POST /api/packages`

**招标包**

```json
{ "type": "TENDER", "name": "市政招标文件包" }
```

仅传 type 也可：`{ "type": "TENDER" }`（`name` 空则自动生成）。

**投标包**

```json
{
  "type": "BID",
  "name": "华建集团",
  "contactName": "王五",
  "contactPhone": "13800001111",
  "companyAddress": "上海市浦东新区示例路 1 号",
  "priceWithTax": 1130000.00,
  "taxRate": 13,
  "priceWithoutTax": 1000000.00
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | `TENDER` / `BID` |
| name | string | 否 | 招标=包名；投标=公司名；同组织同类型唯一；空则自动生成 `未命名-yyyyMMdd-HHmm`（冲突再加秒/序号） |
| contactName | string | 否 | 负责人姓名（仅投标包有意义） |
| contactPhone | string | 否 | 负责人电话（仅投标包有意义） |
| companyAddress | string | 否 | 公司地址（仅投标；最长 256） |
| priceWithTax | number | 否 | 含税价格；≥0，最多两位小数 |
| taxRate | number | 否 | 税率，如 `13` 表示 13%；≥0，最多四位小数 |
| priceWithoutTax | number | 否 | 不含税价（**前端计算后传入**）；≥0，最多两位小数 |

创建后 `projectId` 为 null。**更新接口名称与联系人仍必填；地址与报价选填，传 `null` 表示清空。**

**成功响应**：`data` 为标包摘要（字段见上表），例如：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 50,
    "orgId": 2,
    "projectId": null,
    "type": "TENDER",
    "name": "市政招标文件包",
    "contactName": null,
    "contactPhone": null,
    "status": "ACTIVE",
    "createdBy": 10,
    "createdAt": "2026-07-22T06:00:00Z",
    "updatedAt": "2026-07-22T06:00:00Z",
    "fileCount": 0
  }
}
```

### 7.3 标包详情

`GET /api/packages/{packageId}`

跨组织或已删除 → `40400`。

**成功响应**：`data` 为标包摘要（字段同 7.1 单条）。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 60,
    "orgId": 2,
    "projectId": 10,
    "type": "BID",
    "name": "华建集团",
    "contactName": "王五",
    "contactPhone": "13800001111",
    "companyAddress": "上海市浦东新区示例路 1 号",
    "priceWithTax": 1130000.00,
    "taxRate": 13,
    "priceWithoutTax": 1000000.00,
    "status": "ACTIVE",
    "createdBy": 10,
    "createdAt": "2026-07-22T06:10:00Z",
    "updatedAt": "2026-07-22T06:10:00Z",
    "fileCount": 3
  }
}
```

### 7.4 更新标包

`PUT /api/packages/{packageId}`

招标包：`{ "name": "..." }`  
投标包：

```json
{
  "name": "华建集团",
  "contactName": "王五",
  "contactPhone": "13800001111",
  "companyAddress": "上海市浦东新区示例路 1 号",
  "priceWithTax": 1130000.00,
  "taxRate": 13,
  "priceWithoutTax": 1000000.00
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| name / contactName / contactPhone | 是 | 与创建不同，更新时联系人仍必填 |
| companyAddress / priceWithTax / taxRate / priceWithoutTax | 否 | 不传或 `null` 表示清空 |

已绑定项目的仍可改。

**成功响应**：`data` 为更新后的标包摘要（同 7.3）。

### 7.5 删除（逻辑删除）

`DELETE /api/packages/{packageId}`

| 类型 | 规则 |
|------|------|
| 招标包 | 已绑定项目 → `40900`；未绑可删，**级联逻辑删除包内文件**（含对象存储与切块/向量） |
| 投标包 | **可直接删**（已绑则项目下不再可见）；**级联逻辑删除包内文件**；已绑项目时：删除该包审查条目，并将项目下 SUCCESS/FAILED/BUILDING 审查标为 `OBSOLETE`（`workflowStatus` 回到待审查）；建清单/审查进行中 → `40900` |

**成功响应**

```json
{ "code": 0, "message": "ok", "data": null }
```

---

## 标包文件 Documents

> 类型限 PDF / Word / Excel；单文件默认 ≤ **100MB**（`UPLOAD_MAX_FILE_SIZE`）；每包最多 5 个。
> 鉴权同其它接口；前端拉 blob 后用 `blob:` URL 页内展示。  
> local 测试用内存存储；dev 连 MinIO（bucket `bidding-dev`）。

### 文件摘要字段（列表 / 上传 / 复制结果 items 共用）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | number | 文档 ID |
| orgId | number | 所属组织 |
| projectId | number \| null | 所属项目（包未绑项目时可为 null） |
| packageId | number | 所属标包 |
| fileName | string | 原始文件名 |
| contentType | string \| null | MIME |
| fileExt | string | `pdf` / `doc` / `docx` / `xls` / `xlsx` |
| fileType | string | 业务文件类型（自由字符串） |
| sizeBytes | number | 字节数 |
| indexStatus | string | 见下表 |
| status | string | `ACTIVE`（列表不含已删） |
| createdBy | number | 上传人 |
| createdAt | string | ISO 时间 |
| updatedAt | string | ISO 时间 |

**`indexStatus` 取值（前端展示可用中文）**

| 值 | 中文 | 说明 |
|----|------|------|
| `PENDING` | 待解析 | 已上传，尚未开始索引 |
| `PROCESSING` | 解析中 | INDEX 任务进行中 |
| `SUCCESS` | 解析完成 | 可参与建清单 / 审查召回 |
| `FAILED` | 解析失败 | 可重新点索引 |

> 「待上传 / 上传中 / 上传失败」为前端上传过程态，后端不落库。

### 8.1 上传

`POST /api/packages/{packageId}/files`  
`Content-Type: multipart/form-data`

| 字段 | 必填 | 说明 |
|------|------|------|
| `file` | 是 | 文件本体（`multipart` part） |
| `fileType` | 是 | 业务文件类型；自由字符串，后端只存不校验枚举；最长 128 |

**成功响应**：`data` 为文件摘要；新上传默认 `indexStatus=PENDING`。  
已绑项目时：建清单/审查进行中 → `40900`；成功后按包类型作废产物（招标：清单+审查；投标：仅审查），`OBSOLETE` 详情仍可查看。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 1,
    "orgId": 2,
    "projectId": 10,
    "packageId": 50,
    "fileName": "招标文件.pdf",
    "contentType": "application/pdf",
    "fileExt": "pdf",
    "fileType": "招标正文",
    "sizeBytes": 1024,
    "indexStatus": "PENDING",
    "status": "ACTIVE",
    "createdBy": 10,
    "createdAt": "2026-07-22T06:00:00Z",
    "updatedAt": "2026-07-22T06:00:00Z"
  }
}
```

### 8.2 列表

`GET /api/packages/{packageId}/files`

**成功响应**：`data` 为文件摘要**数组**（非分页）。

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": 1,
      "orgId": 2,
      "projectId": 10,
      "packageId": 50,
      "fileName": "招标文件.pdf",
      "contentType": "application/pdf",
      "fileExt": "pdf",
      "fileType": "招标正文",
      "sizeBytes": 1024,
      "indexStatus": "SUCCESS",
      "status": "ACTIVE",
      "createdBy": 10,
      "createdAt": "2026-07-22T06:00:00Z",
      "updatedAt": "2026-07-22T06:30:00Z"
    }
  ]
}
```

### 8.3 下载

`GET /api/packages/{packageId}/files/{documentId}/download`

- 响应：文件二进制流（非 `ApiResponse` 包装）
- `Content-Disposition: attachment; filename*=UTF-8''...`
- `Content-Type`：存库的 MIME（缺省按扩展名推断）
- 支持 PDF / Word / Excel

### 8.4 预览（仅 PDF）

`GET /api/packages/{packageId}/files/{documentId}/preview`

- 响应：文件二进制流（非 `ApiResponse` 包装）
- `Content-Disposition: inline; filename*=UTF-8''...`
- 仅 PDF；Word/Excel → `40000`，提示「仅支持预览 PDF，Word/Excel 请下载后查看」

### 8.5 删除

`DELETE /api/packages/{packageId}/files/{documentId}`

先删对象存储，再逻辑删元数据，并清理该文档切块/向量。

| 文件所属 | 附加规则 |
|----------|----------|
| 招标文件（已绑项目） | 作废项目下 SUCCESS/FAILED/BUILDING 清单与审查（`OBSOLETE`），需重新解析/一键审查；建清单/审查进行中 → `40900` |
| 投标文件（已绑项目） | 仅作废审查（清单保留可复用）；建清单/审查进行中 → `40900` |

**成功响应**

```json
{ "code": 0, "message": "ok", "data": null }
```

### 8.6 从历史项目复制招标文件

> 招标侧一键全拷；投标侧见 §8.7（拷入已有投标包）。  
> 前置：当前项目已创建且已绑定招标包。原项目文件不受影响（独立副本 + 新 object_key）。

#### 8.6.1 可复制来源列表

`GET /api/projects/{projectId}/tender-copy-sources`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| keyword | string | 否 | `""` | 按项目名称等模糊搜 |

返回本组织**其它**未删除项目中，招标包下至少有 1 个未删除文件的项目（含已解析 / 未解析）。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "projectId": 20,
      "projectName": "智慧园区一体化平台",
      "tenderPackageId": 60,
      "fileCount": 2
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| projectId | 来源项目 ID |
| projectName | 来源项目名称 |
| tenderPackageId | 该项目招标包 ID |
| fileCount | 该招标包未删除文件数 |

#### 8.6.2 一键复制

`POST /api/projects/{projectId}/tender-files/copy-from`

```json
{ "sourceProjectId": 20 }
```

将 `sourceProjectId` 项目招标包下**全部**未删除文件，复制到当前项目招标包：

- 文件名自动加 `_副本`（冲突则 `_副本2`…）
- 复制后每个新文件自动排队 INDEX（返回 `jobIds`）
- 若复制后将超过每包文件数上限 → **40900**，整单失败、不写任何副本
- 来源无招标文件 / 当前未绑招标包 / 复制到自身 → **40000**
- 跨组织来源项目 → **40400**

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "copiedCount": 2,
    "jobIds": [101, 102],
    "items": [
      {
        "id": 88,
        "orgId": 2,
        "projectId": 10,
        "packageId": 50,
        "fileName": "招标文件_副本.pdf",
        "contentType": "application/pdf",
        "fileExt": "pdf",
        "fileType": "招标正文",
        "sizeBytes": 1024,
        "indexStatus": "PROCESSING",
        "status": "ACTIVE",
        "createdBy": 10,
        "createdAt": "2026-07-30T02:00:00Z",
        "updatedAt": "2026-07-30T02:00:00Z"
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| copiedCount | 本次复制文件数 |
| jobIds | 各文件 INDEX 任务 ID，可 `GET /api/jobs/{jobId}` 轮询 |
| items | 新文件摘要数组（字段同「文件摘要」） |

### 8.7 从历史项目复制投标文件（拷入已有投标包）

> 只按**整个源投标包**复制文件，不能勾包内单文件；一次请求只拷一个源包。  
> **不新建**投标包：把源包全部文件追加到当前项目里**已打开/已创建**的目标投标包（目标包可已有文件）。

#### 8.7.1 可复制来源列表

`GET /api/projects/{projectId}/bid-copy-sources`

**Query**：`keyword` 可选，按项目名称模糊搜。

返回本组织**其它**未删除项目中，有未删除文件的投标包（按项目分组；空包不返回）。不含当前项目自己的包。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "projectId": 20,
      "projectName": "智慧园区一体化平台",
      "bidPackages": [
        {
          "packageId": 71,
          "packageName": "华建集团",
          "fileCount": 3,
          "contactName": "王五",
          "contactPhone": "13800001111",
          "companyAddress": "上海市浦东新区示例路 1 号",
          "priceWithTax": 1130000.00,
          "taxRate": 13,
          "priceWithoutTax": 1000000.00
        }
      ]
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| projectId / projectName | 来源项目 |
| bidPackages[].packageId | 可复制的源包 ID（传给 copy-from 的 `sourcePackageId`） |
| bidPackages[].packageName | 源投标公司名 |
| bidPackages[].fileCount | 源包内未删除文件数 |
| bidPackages[].contactName 等 | 来源展示用；**本次复制不改目标包元数据** |

#### 8.7.2 复制到已有目标包

`POST /api/projects/{projectId}/bid-files/copy-from`

```json
{
  "sourcePackageId": 71,
  "targetPackageId": 80
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| sourcePackageId | 是 | 历史项目下的源投标包 |
| targetPackageId | 是 | 当前项目下**已有**的目标投标包（当前打开的包） |

行为：

- 校验来源为同组织 `BID`、已绑**其它**项目、有文件
- 校验目标为同组织 `BID`、已绑**当前**项目
- 源包全部文件复制进目标包（文件名 `_副本`…，与目标包已有文件名避重），每个自动排队 INDEX
- 复制后文件数将超每包上限 → **40900** 整单失败、不写副本
- 来源空包 / 类型不对 / 源来自当前项目 / 目标未绑当前项目 → **40000**；跨组织 → **40400**

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "targetPackageId": 80,
    "packageName": "当前投标包",
    "copiedCount": 3,
    "jobIds": [201, 202, 203],
    "items": [
      {
        "id": 90,
        "orgId": 2,
        "projectId": 10,
        "packageId": 80,
        "fileName": "响应文件_副本.pdf",
        "contentType": "application/pdf",
        "fileExt": "pdf",
        "fileType": "投标文件",
        "sizeBytes": 2048,
        "indexStatus": "PROCESSING",
        "status": "ACTIVE",
        "createdBy": 10,
        "createdAt": "2026-07-30T02:10:00Z",
        "updatedAt": "2026-07-30T02:10:00Z"
      }
    ]
  }
}
```

| 字段 | 说明 |
|------|------|
| targetPackageId | 目标投标包 ID（即请求里的已有包） |
| packageName | 目标包名称 |
| copiedCount / jobIds / items | 同招标复制 |

---

## 隐藏逻辑（本册必读）

### 标包
- 创建时均不绑项目（`projectId=null`）：招标包在创建项目时选绑；投标包在项目下「添加投标包」时选绑。
- 创建：仅 `type` 必填；`name` 可空（空则 `未命名-yyyyMMdd-HHmm`）；投标联系人可空。更新接口名称等仍必填。
- 招标包 `name`=包名；投标包 `name`=投标公司名（同组织未删除唯一）。
- 已绑项目的招标包：**不可删**；投标包可删（已绑则项目下不再可见），但**包内有文件不可删**。
- `fileCount` 为真实未删除文件数（非占位）。
- `unboundOnly=true`：只返回尚未绑定项目的包，供创建项目/绑投标包下拉。

### 文件
- 上传：multipart 必填 `file` + `fileType`（业务类型自由字符串，只存不校验；空/过长 → 400）；扩展名限 PDF/Word/Excel；超限 → 400。
- 已绑项目下上传/复制/删除文件：建清单或审查进行中 → `40900`；成功后招标变更作废清单+审查，投标变更只作废审查；`OBSOLETE` 不删行，详情可按 id 查看。向量化（INDEX）进行中不拦截改文件。
- 上传时 `projectId` 取自标包；包尚未绑项目则为 `null`。创建项目绑包时，后端会把包内已有文件（及 chunk/vector）的空 `project_id` 回写为该项目，**前端无需单独传/补绑**。
- 有文件的包不可删（投标包规则）；删文件为逻辑删（status=DELETED），对象存储侧按实现清理或保留。
- 预览仅 PDF；非 PDF 预览 → 400。
- 下载/预览/列表均校验：包属本组织、文件属该包、未删除。
- `indexStatus=PROCESSING` 时勿重复点索引（见 AI 册）；列表可轮询 `indexStatus` 展示进度。
- 跨组织用别人的 packageId/documentId → **404**，不是 403。
- **历史招标复制**：只拷文件本体与元数据，不拷清单/审查；源项目删文件不影响已复制副本；副本有独立 `documentId` 与存储 key。
- **历史投标复制**：一次一个源包；文件拷入当前项目**已有**目标投标包；不新建包、不改目标包联系人/报价；不拷审查结果。
