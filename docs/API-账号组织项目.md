# 招投标后端 · API（账号 / 组织 / 项目）

> 本册范围：**认证、账号、组织、项目**。  
> 另见：[文件与标包](./API-文件与标包.md) · [检索 / 重排 / LLM](./API-检索重排与LLM.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-07-29

### 浏览器 CORS（前端页面调接口必看）

- 前端页面在 `http://127.0.0.1:5186`，接口在 `http://192.168.8.83`，属于**跨域**。
- 浏览器会先发 OPTIONS 预检；后端必须返回 `Access-Control-Allow-Origin` 等头，否则请求被拦截（Network 里常显示 Provisional headers、无 Response Headers）。
- Apifox / curl **不检查 CORS**，所以会出现「工具能通、浏览器不通」。
- 后端已配置允许 Origin：`http://127.0.0.1:5186`、`http://localhost:5186`（可用环境变量 `CORS_ALLOWED_ORIGINS` 追加）。
- 改前端端口或加新域名后，需同步改配置并**重启后端**。

---

## 1. 通用约定

### 1.1 统一响应

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | number | `0` 成功；非 0 业务/鉴权错误 |
| message | string | 提示信息 |
| data | object / array / null | 业务数据 |

前端判断：优先看 `code === 0`，不要只依赖 HTTP 状态码（多数错误也会带 4xx，但以 body.code 为准更稳）。

### 1.2 鉴权

| 场景 | 要求 |
|------|------|
| 登录 | 无需 Token |
| `/api/auth/change-password`、`/api/auth/logout` | Header：`Authorization: Bearer <accessToken>` |
| `/api/admin/**` | Header：`Authorization: Bearer <accessToken>`，且角色含 `PLATFORM_ADMIN` 或 `PLATFORM_SUPER_ADMIN` |

未登录 / Token 无效（含会话已吊销）：

```json
{ "code": 40100, "message": "未登录或登录已失效", "data": null }
```

无权限 / 强制改密未完成：

```json
{ "code": 40300, "message": "无权限", "data": null }
```

强制改密期间访问非改密/退出接口时，`message` 为「请先修改密码」。

### 1.3 错误码

| code | 含义 |
|------|------|
| 0 | 成功 |
| 40000 | 请求参数错误 |
| 40100 | 未登录或登录已失效 |
| 40300 | 无权限 |
| 40400 | 资源不存在 |
| 40900 | 资源冲突（如不能删除最后一个平台管理员） |
| 50000 | 系统错误 |

### 1.4 平台角色枚举

| roleCode | 名称 |
|----------|------|
| PLATFORM_ADMIN | 平台管理员 |
| PLATFORM_SUPER_ADMIN | 平台超级管理员（全局唯一；可删除组织、转让超管） |
| TENDERER | 招标方 |
| BIDDER | 投标方 |
| EXPERT | 专家 |

> 说明：以上为平台角色，挂在用户账号上。`PLATFORM_SUPER_ADMIN` 同时视为平台管理员（可访问全部 `/api/admin/**`）。仅现任超管可将他人设为超管；授予时自动附带 `PLATFORM_ADMIN`，并摘掉原超管角色。

### 1.5 密码与会话规则

| 规则 | 说明 |
|------|------|
| 密码强度 | ≥8 位，且同时包含字母和数字；两次输入一致由**前端**校验 |
| 不能与当前密码相同 | 自行改密时后端校验 |
| 首次登录 / 重置后 | `mustChangePassword=true`，除改密与退出外一律 403 |
| 会话吊销 | 使用 `token_version`：重置密码、改密、改角色、停用、删除、退出时版本 +1，旧 Token 立即失效 |

### 1.6 本地联调账号（dev）

| 项 | 值 |
|------|------|
| 用户名 | `admin` |
| 密码 | `ChangeMe@123` |
| 角色 | PLATFORM_ADMIN + PLATFORM_SUPER_ADMIN |

---

## 2. 认证 Auth

### 2.1 登录

`POST /api/auth/login`

**请求体**

```json
{
  "username": "admin",
  "password": "ChangeMe@123"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| username | string | 是 | 用户名 |
| password | string | 是 | 密码 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "userId": 1,
    "orgId": 1,
    "orgCode": "SYSTEM",
    "orgName": "系统组织",
    "username": "admin",
    "displayName": "平台管理员",
    "phone": null,
    "email": null,
    "roleCodes": ["PLATFORM_ADMIN"],
    "mustChangePassword": false
  }
}
```

| 字段 | 说明 |
|------|------|
| accessToken | 后续请求放在 `Authorization: Bearer ...` |
| orgId / orgCode / orgName | 所属组织；前端展示用 `orgName` |
| phone / email | 手机号、邮箱；引导管理员可能为空 |
| roleCodes | 当前账号平台角色列表 |
| mustChangePassword | `true` 时前端应强制进入改密页，不可进业务页 |

**常见失败**

| code | 说明 |
|------|------|
| 40100 | 用户名或密码错误 |
| 40300 | 账号已停用 |

---

### 2.2 当前用户

`GET /api/auth/me`

需登录。用于刷新页面后重新拉取「我是谁、哪个组织、什么角色」。强制改密期间也可调用。

**成功响应**（无 `accessToken`，其余字段同登录）

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 1,
    "orgId": 1,
    "orgCode": "SYSTEM",
    "orgName": "系统组织",
    "username": "admin",
    "displayName": "平台管理员",
    "phone": null,
    "email": null,
    "roleCodes": ["PLATFORM_ADMIN"],
    "mustChangePassword": false
  }
}
```

---

### 2.3 修改密码

`POST /api/auth/change-password`

需登录。两次输入一致前端校验；后端只收 `newPassword`。

```json
{
  "newPassword": "NewPass12"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| newPassword | string | 是 | ≥8，含字母+数字，且不能与当前密码相同 |

成功后旧 Token **立即失效**，前端应清 Token 并跳转登录页，用新密码重新登录。

---

### 2.4 退出登录

`POST /api/auth/logout`

需登录。服务端 `token_version + 1`，当前及同账号其他旧会话全部失效。前端同时清除本地 Token，并在收到 401 时统一跳登录页。


## 3. 平台管理 Admin（需平台管理员）

以下接口均需：

```http
Authorization: Bearer <accessToken>
```

### 3.0 首页汇总

`GET /api/admin/dashboard`

平台管理员首页指标（全平台汇总；不含提示词）。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "tendererOrgCount": 12,
    "platformAdminActiveCount": 3,
    "platformAdminDisabledCount": 1,
    "reviewSuccessCount": 8,
    "reviewFailedCount": 2,
    "documentIndexSuccessCount": 40,
    "documentIndexFailedCount": 5
  }
}
```

| 字段 | 说明 |
|------|------|
| tendererOrgCount | 招标方组织总数（排除系统组织与已删除） |
| platformAdminActiveCount | 正常平台管理员账号数（含超管；`ACTIVE`） |
| platformAdminDisabledCount | 已停用平台管理员账号数（含超管；`DISABLED`） |
| reviewSuccessCount | AI 审查成功数（`biz_review.status=SUCCESS`，含历史版本行） |
| reviewFailedCount | AI 审查失败数（`biz_review.status=FAILED`） |
| documentIndexSuccessCount | 解析完成文档数（未删除且 `indexStatus=SUCCESS`） |
| documentIndexFailedCount | 解析失败文档数（未删除且 `indexStatus=FAILED`） |

### 3.1 角色字典列表

`GET /api/admin/roles`

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    { "code": "BIDDER", "name": "投标方" },
    { "code": "EXPERT", "name": "专家" },
    { "code": "PLATFORM_ADMIN", "name": "平台管理员" },
    { "code": "PLATFORM_SUPER_ADMIN", "name": "平台超级管理员" },
    { "code": "TENDERER", "name": "招标方" }
  ]
}
```

---

### 3.2 用户分页列表

`GET /api/admin/users`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| keyword | string | 否 | `""` | 按用户名 / 显示名模糊搜 |
| page | number | 否 | 1 | 页码，从 1 起 |
| size | number | 否 | 20 | 每页条数，最大 100 |

示例：`GET /api/admin/users?keyword=ad&page=1&size=20`

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 1,
        "orgId": 1,
        "username": "admin",
        "displayName": "平台管理员",
        "phone": null,
        "email": null,
        "status": "ACTIVE",
        "mustChangePassword": false,
        "roleCodes": ["PLATFORM_ADMIN"]
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
| status | `ACTIVE` / `DISABLED` / `DELETED` |
| mustChangePassword | 是否仍需强制改密 |
| createdAt | 创建时间 |
| updatedAt | 最近更新时间 |
| createdBy | 创建人用户 ID；引导账号可能为 `null` |
| createdByName | 创建人显示名 |

---

### 3.3 用户详情

`GET /api/admin/users/{userId}`

**路径参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| userId | number | 用户 ID |

**成功响应**：`data` 结构同列表中的单条用户。

**失败**：用户不存在 → `40400`

---

### 3.3.1 平台方账号分页列表

`GET /api/admin/platform-admins`

仅返回角色含 `PLATFORM_ADMIN` 或 `PLATFORM_SUPER_ADMIN`、且未删除的账号，供前端分页专用。

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| keyword | string | 否 | `""` | 按用户名 / 显示名模糊搜 |
| status | string | 否 | `""` | `ACTIVE` / `DISABLED`；空=全部未删除 |
| page | number | 否 | 1 | 页码，从 1 起 |
| size | number | 否 | 20 | 每页条数，最大 100 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 1,
        "orgId": 1,
        "username": "admin",
        "displayName": "平台管理员",
        "phone": null,
        "email": null,
        "status": "ACTIVE",
        "mustChangePassword": false,
        "roleCodes": ["PLATFORM_ADMIN", "PLATFORM_SUPER_ADMIN"],
        "createdBy": null,
        "createdByName": null,
        "createdAt": "2026-07-20T02:00:00Z",
        "updatedAt": "2026-07-29T01:00:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20,
    "activeCount": 3,
    "disabledCount": 1
  }
}
```

| 字段 | 说明 |
|------|------|
| total | 当前筛选（keyword + status）下的条数 |
| activeCount | 全量正常平台账号数（不受 keyword / status 影响） |
| disabledCount | 全量停用平台账号数（不受 keyword / status 影响） |

---

### 3.3.2 平台方账号详情

`GET /api/admin/platform-admins/{userId}`

**成功响应**：同列表单条。非平台方账号或已删除 → `40400`。

---

### 3.4 创建用户

`POST /api/admin/users`

**请求体**

```json
{
  "orgId": 1,
  "username": "zhangsan",
  "password": "Passw0rd!",
  "displayName": "张三",
  "phone": "13800138000",
  "email": "zhangsan@example.com",
  "roleCodes": ["TENDERER"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orgId | number | 是 | 归属组织；平台管理员可跨组织创建 |
| username | string | 是 | 仅字母/数字/下划线，长度 3~64 |
| password | string | 是 | ≥8，且含字母+数字（最长 72） |
| displayName | string | 是 | 显示名，最长 128 |
| phone | string | 是 | 中国大陆 11 位手机号，全局唯一 |
| email | string | 是 | 邮箱，全局唯一（存库统一小写） |
| roleCodes | string[] | 是 | 至少 1 个合法平台角色 |

**成功响应**：`data` 为新建用户摘要（含 `mustChangePassword: true`、`phone`、`email`，不含密码）。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 参数不合法 / 组织不存在或已停用 / 密码、手机号或邮箱不符合规则 / 角色为空或非法 |
| 40900 | 用户名、手机号或邮箱已存在 |

**安全说明**

- 数据库访问使用参数绑定，不拼接 SQL。  
- 用户名白名单校验，降低注入与脏数据风险。  
- 新建账号默认 `mustChangePassword=true`，首次登录必须改密。

---

### 3.4.1 创建平台管理员

`POST /api/admin/platform-admins`

任意平台管理员（含超管）可调用。后端固定：归属系统组织 `SYSTEM`、角色 `PLATFORM_ADMIN`；手机号与邮箱为空。

**请求体**

```json
{
  "displayName": "李四",
  "username": "lisi",
  "password": "Passw0rd!"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| displayName | string | 是 | 姓名（显示名），最长 128 |
| username | string | 是 | 登录账号：字母/数字/下划线，3~64 |
| password | string | 是 | 初始密码：≥8，且含字母+数字 |

**成功响应**：同创建用户摘要；`orgId` 为系统组织，`roleCodes` 含 `PLATFORM_ADMIN`，`phone` / `email` 为 `null`，`mustChangePassword=true`。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 参数不合法 / 密码不符合规则 |
| 40900 | 用户名已存在 |

> 不在此接口创建超管；超管仍由现任超管通过改角色转让。

---

### 3.5 设置用户角色（全量覆盖）

`PUT /api/admin/users/{userId}/roles`

**请求体**

```json
{
  "roleCodes": ["TENDERER", "EXPERT"]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| roleCodes | string[] | 是 | 至少一个；必须是合法角色码；**全量替换**原角色 |

**成功响应**：返回更新后的用户摘要（同 3.3）。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 空列表 / 未知 roleCode |
| 40300 | 非超管试图授予或摘除 `PLATFORM_SUPER_ADMIN` |
| 40400 | 用户不存在 |
| 40900 | 系统至少保留一名平台管理员 / 至少保留一名平台超级管理员 |

**前端注意**

- 这是全量覆盖：最终角色 = 本次提交的数组，不是增量追加。
- 改角色后对方旧 Token **立即失效**，需重新登录。
- 超管全局唯一：现任超管把 `PLATFORM_SUPER_ADMIN` 赋给他人时，原超管角色会被自动摘掉并吊销其会话。
- 删除组织按钮是否展示由前端按 `roleCodes` 判断；后端删除接口仍会校验超管。

---

### 3.6 重置密码（一次性展示临时密码）

`POST /api/admin/users/{userId}/reset-password`

无请求体。成功后：

- 生成临时密码并写入库（`mustChangePassword=true`）
- `token_version + 1`，旧会话立即失效
- **明文临时密码仅本次响应返回**，管理员页面展示后不可再查

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 123,
    "reset": true,
    "temporaryPassword": "aB3k9mQx2LpN",
    "message": "临时密码已生成，请立即复制保存，关闭后不可再查看"
  }
}
```

> `temporaryPassword` 供页面一次性复制；关闭弹窗后后端不再提供查询接口。

---

### 3.7 启用 / 停用

`PUT /api/admin/users/{userId}/status`

```json
{
  "status": "DISABLED"
}
```

| status | 说明 |
|--------|------|
| ACTIVE | 启用 |
| DISABLED | 停用；旧会话立即失效，无法再登录 |

---

### 3.8 删除用户（逻辑删除）

`DELETE /api/admin/users/{userId}`

将 `status` 设为 `DELETED`，并吊销会话。系统至少保留一名平台管理员；若目标为末位超管则禁止删除。


## 4. 组织管理 Orgs（M1）

> 平台管理员（`PLATFORM_ADMIN` / `PLATFORM_SUPER_ADMIN`）可管理组织。  
> **编码 `code` 创建后不可改**；公司名称、所在城市可通过编辑接口修改。  
> **逻辑删除**仅超管：`DELETE /api/admin/orgs/{orgId}`；前端按 `roleCodes` 决定是否展示删除按钮。  
> **两级列表**：一级看组织；二级 `GET /api/admin/orgs/{orgId}/users` 看该组织下全部用户（组织与账号分开创建，可先建空组织）。  
> 列表/详情**默认不展示** `DELETED`；组织状态对前端启停为 `ACTIVE` / `DISABLED`。系统组织列表可见、可点进看用户，但不可停用、不可删除、不可编辑。  
> 组织已删除：该组织用户**无法登录**（提示所属组织不可用）；删除时对该组织用户批量 `token_version + 1`。  
> **所在城市**：创建/编辑时必须从全国地级市词典中选择（正式名，如「深圳市」，不可手填「深圳」）。省份仅用于城市下拉联动，**不落组织表**。

### 4.0 城市选项（创建组织下拉）

#### 4.0.1 省份列表

`GET /api/admin/cities/provinces`

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": ["北京市", "天津市", "河北省", "山西省"],
    "total": 34
  }
}
```

#### 4.0.2 地级市列表

`GET /api/admin/cities`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| province | string | 否 | `""` | 按省精确筛（传省份正式名，如 `广东省`） |
| keyword | string | 否 | `""` | 按城市名模糊搜 |

示例：`GET /api/admin/cities?province=广东省&keyword=深圳`

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "code": "440300",
        "name": "深圳市",
        "province": "广东省",
        "provinceCode": "440000"
      }
    ],
    "total": 1
  }
}
```

| 字段 | 说明 |
|------|------|
| code | 行政区划代码 |
| name | 城市正式名；**创建组织时 `city` 必须传此值** |
| province / provinceCode | 所属省（直辖市） |

> 词典含直辖市、地级市、自治州/地区/盟，以及部分省直管/兵团师市等常用选项；数据来自 classpath 静态 JSON，不建表。

---

### 4.1 组织分页列表

`GET /api/admin/orgs`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| name | string | 否 | `""` | 公司名称模糊搜 |
| city | string | 否 | `""` | 所在城市模糊搜 |
| status | string | 否 | `""` | `ACTIVE` / `DISABLED`；空=全部未删除（不含 `DELETED`） |
| page | number | 否 | 1 | 页码，从 1 起 |
| size | number | 否 | 20 | 每页条数，最大 500 |

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 2,
        "code": "ORG_A1B2C3D4E5F6",
        "name": "华能招标有限公司",
        "city": "北京市",
        "status": "ACTIVE",
        "userCount": 0,
        "projectCount": 0,
        "tenderPackageCount": 0,
        "bidPackageCount": 0,
        "createdBy": 1,
        "createdByName": "平台管理员",
        "createdAt": "2026-07-21T03:00:00Z",
        "updatedAt": "2026-07-21T03:00:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20,
    "activeCount": 5,
    "disabledCount": 2,
    "pendingConfigCount": 1
  }
}
```

| 字段 | 说明 |
|------|------|
| total | 当前筛选（name + city + status）下的条数 |
| activeCount | 全量正常组织数（不受筛选影响） |
| disabledCount | 全量停用组织数（不受筛选影响） |
| pendingConfigCount | 全量待配置：未删除且下属无未删除账号的组织数（不受筛选影响） |
| code | 系统自动生成，创建后不可改 |
| name | 公司名称，可编辑；全局未删除唯一 |
| status | 库内可为 `ACTIVE` / `DISABLED` / `DELETED`；列表与详情不返回已删 |
| userCount | 组织下未删除用户数 |
| projectCount | 组织下未删除项目数 |
| tenderPackageCount | 未删除招标包数量 |
| bidPackageCount | 未删除投标包数量 |

---

### 4.2 创建组织

`POST /api/admin/orgs`

```json
{
  "name": "华能招标有限公司",
  "city": "北京市"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 公司名称，全局唯一，最长 128；创建后可编辑 |
| city | string | 是 | 所在城市，必须为 `GET /api/admin/cities` 返回的 `name`（精确匹配） |

**成功响应**：`data` 为新建组织摘要（含自动生成的 `code`，`status=ACTIVE`，`userCount=0`）。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 名称/城市为空，或城市不在地级市词典中 |
| 40900 | 公司名称已存在 |

允许先建空组织（无账号）。

---

### 4.2.1 编辑组织

`PUT /api/admin/orgs/{orgId}`

```json
{
  "name": "华能招标有限公司",
  "city": "深圳市"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 公司名称；全局未删除唯一；最长 128 |
| city | string | 是 | 所在城市，必须为词典正式名 |

**成功响应**：`data` 为更新后的组织摘要（同列表单条）。  
系统组织 / 已删除 → `40000` / `40400`；重名 → `40900`。

---

### 4.3 组织详情

`GET /api/admin/orgs/{orgId}`

**成功响应**：在列表单条字段基础上，额外返回解析/审查统计：

| 字段 | 说明 |
|------|------|
| documentIndexSuccessCount | 未删除文档中 `indexStatus=SUCCESS` 数量 |
| documentIndexFailedCount | 未删除文档中 `indexStatus=FAILED` 数量 |
| reviewSuccessCount | 审查（REVIEW）`status=SUCCESS` 数量（含历史版本行） |
| reviewFailedCount | 审查（REVIEW）`status=FAILED` 数量 |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 2,
    "code": "ORG_A1B2C3D4E5F6",
    "name": "华能招标有限公司",
    "city": "北京市",
    "status": "ACTIVE",
    "userCount": 3,
    "projectCount": 5,
    "tenderPackageCount": 2,
    "bidPackageCount": 4,
    "documentIndexSuccessCount": 10,
    "documentIndexFailedCount": 1,
    "reviewSuccessCount": 3,
    "reviewFailedCount": 2,
    "createdBy": 1,
    "createdByName": "平台管理员",
    "createdAt": "2026-07-21T03:00:00Z",
    "updatedAt": "2026-07-21T03:00:00Z"
  }
}
```

**失败**：不存在或已删除 → `40400`

---

### 4.4 组织下用户（二级列表）

`GET /api/admin/orgs/{orgId}/users`

点进某一组织后，查看该组织下全部账号。系统组织也可查询。

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| keyword | string | 否 | `""` | 按用户名 / 显示名 / 手机 / 邮箱模糊搜 |
| page | number | 否 | 1 | 页码，从 1 起 |
| size | number | 否 | 20 | 每页条数，最大 100 |

**成功响应**：分页结构同用户列表；`items` 为用户摘要（与 `GET /api/admin/users` 单条字段一致）。  
**不含** `DELETED` 用户。

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 1,
        "orgId": 1,
        "username": "admin",
        "displayName": "平台管理员",
        "phone": null,
        "email": null,
        "status": "ACTIVE",
        "mustChangePassword": false,
        "roleCodes": ["PLATFORM_ADMIN"]
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  }
}
```

**失败**：组织不存在 → `40400`

往组织下挂账号：继续用 `POST /api/admin/users`，传对应 `orgId`。

---

### 4.4.1 修改招标方账号资料

`PUT /api/admin/orgs/{orgId}/users/{userId}/profile`

平台管理员修改**指定招标方组织**下、角色含 `TENDERER` 的账号资料。  
不改公司名称、城市、登录账号；系统组织账号不可走本接口。

**请求体**

```json
{
  "displayName": "王五",
  "phone": "13900139000",
  "email": "wangwu@example.com"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| displayName | string | 是 | 负责人姓名（显示名），最长 128 |
| phone | string | 是 | 中国大陆 11 位手机号，全局唯一 |
| email | string | 是 | 邮箱，全局唯一（存库统一小写） |

**成功响应**：更新后的用户摘要。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 参数不合法 / 用户不属于该组织 / 非招标方账号 / 系统组织 / 已删除账号 |
| 40400 | 组织或用户不存在 |
| 40900 | 手机号或邮箱已被占用 |

---

### 4.5 启用 / 停用组织

`PUT /api/admin/orgs/{orgId}/status`

```json
{
  "status": "DISABLED"
}
```

| status | 说明 |
|--------|------|
| ACTIVE | 启用；**不**吊销会话 |
| DISABLED | 停用；该组织下用户无法登录，也不能再往该组织创建用户；并对组织下未删除用户批量 `token_version + 1`，旧 Token **立即失效** |

系统组织（`code=SYSTEM`）不允许变更状态。  
说明：

- 只吊销会话，**不改**用户自身 `status`（组织启停与账号启停分开）
- 已是 `DISABLED` 再停用一次：幂等更新，**不再**重复 bump
- 重新启用：不 bump，用户需重新登录

---

### 4.6 删除组织（逻辑删除，仅超管）

`DELETE /api/admin/orgs/{orgId}`

将组织 `status` 设为 `DELETED`，并对该组织下未删除用户批量 `token_version + 1`。

**前置条件**

| 条件 | 失败 |
|------|------|
| 操作者非 `PLATFORM_SUPER_ADMIN` | `40300` |
| 组织不存在或已删除 | `40400` |
| 系统组织（`code=SYSTEM`） | `40000` |
| 仍有未删除用户 / 项目 / 标包 / 文件 | `40000` |

成功响应：`data` 为 `null`。

> 前端：是否展示删除入口由 `roleCodes` 自行判断；后端仍强制校验超管。

---

## 6. 项目 Projects

> 仅角色含 `TENDERER` 的用户。平台管理员无招标方角色时 **403**。  
> 项目挂在当前用户所属组织；`orgId` 由服务端取自登录态，前端不可跨组织指定。  
> **无项目成员角色**。创建时必须绑定一个本组织**未占用**的招标包，以及至少一个投标包。  
> `tenderFileCount` 为招标包下未删除文件真实数量。  
> `mainTenderFileName`：招标包下 `fileType=招标文件` 的文件名（业务约定每项目至多一份；无则为 `null`）。  
> 投标单位列：`bidPackageUploadedCount` 家已上传 · 登记 `bidPackageCount` 家 · `bidFileCount` 个文件。  
> `status` = 启停（`ACTIVE` / `DISABLED`）；`workflowStatus` = **派生业务进度**（不落库，列表/详情实时计算）。

### 6.1 项目分页列表

`GET /api/projects`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| keyword | string | 否 | `""` | 名称 / 代理机构 / 负责人模糊搜 |
| status | string | 否 | `""` | `ACTIVE` / `DISABLED`；空=未删除全部 |
| workflowStatus | string | 否 | `""` | 派生进度筛选，见下表；空=不限 |
| page | number | 否 | 1 | 页码 |
| size | number | 否 | 20 | 每页条数，最大 100 |

停用项目仍出现在列表中（可按 status 筛选）。已删除不返回。

**`workflowStatus` 取值（互斥，优先级从上到下）**

| 码 | 前端文案 | 判定 |
|----|----------|------|
| `NEED_TENDER_FILES` | 待补充招标文件 | 招标文件数=0，或存在 `indexStatus`≠`SUCCESS`（待解析/解析中/失败） |
| `NEED_BID_FILES` | 待补充投标文件 | 招标已齐；投标文件数=0（0 不算齐），或存在投标文件 `indexStatus`≠`SUCCESS` |
| `READY_FOR_AI_REVIEW` | 待AI审查 | 两侧文件均已解析成功，且无进行中审查、最新审查非 SUCCESS/FAILED（含无审查、或已 `OBSOLETE`） |
| `AI_REVIEWING` | AI审查中 | 存在 REVIEW 任务 `PENDING`/`RUNNING`，或审查行 `BUILDING` |
| `AI_REVIEW_SUCCESS` | AI审查完成 | 最新 `biz_review.status=SUCCESS` |
| `AI_REVIEW_FAILED` | AI审查失败 | 最新 `biz_review.status=FAILED` |

> 不做「待AI重新审查」：删投标包 / 删招标文件后会将相关审查（及招标文件对应的清单）标为 `OBSOLETE`，派生状态回到 `READY_FOR_AI_REVIEW`（或待补文件）；重新发起审查仍用一键审查接口。

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
        "name": "市政道路改造工程",
        "agency": "某某招标代理",
        "projectType": "公开招标",
        "leaderName": "张三",
        "status": "ACTIVE",
        "workflowStatus": "READY_FOR_AI_REVIEW",
        "createdBy": 10,
        "createdAt": "2026-07-21T08:00:00Z",
        "updatedAt": "2026-07-21T08:00:00Z",
        "tenderPackageId": 50,
        "tenderPackageCount": 1,
        "tenderFileCount": 2,
        "mainTenderFileName": "招标文件_智慧教育云平台建设项目.docx",
        "bidPackageCount": 1,
        "bidPackageUploadedCount": 1,
        "bidFileCount": 2
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
| status | 启停：`ACTIVE` / `DISABLED` |
| workflowStatus | 派生业务进度（见上表） |
| aiReviewJobId | 最新一键 AI 审查总任务 id；无则为 null；可 `GET /api/jobs/{aiReviewJobId}` 续查 |
| tenderPackageId | 已绑招标包 ID；无则为 null |
| tenderPackageCount | 已绑招标包数量（通常 0/1） |
| tenderFileCount | 招标包下未删除文件数（「N 个文件」） |
| mainTenderFileName | 主招标文件名：`fileType=招标文件`；无则为 `null` |
| bidPackageCount | 已绑投标包数（「登记 Y 家」） |
| bidPackageUploadedCount | 至少有 1 个未删除文件的投标包数（「X 家已上传文件」） |
| bidFileCount | 全部投标包未删除文件数（「Z 个文件」） |

### 6.2 创建项目

`POST /api/projects`

```json
{
  "name": "市政道路改造工程",
  "tenderPackageId": 50,
  "bidPackageIds": [60, 61]
}
```

`name` / `agency` / `projectType` / `leaderName` 均可省略；不填 `name` 时后端按东八区时间自动生成，例如 `未命名-20260729-1030`（同分钟冲突则带秒/序号）。`agency` / `projectType` / `leaderName` 不传或空白存 `null`。

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 否 | 项目名称；同组织内未删除唯一；创建后可改；空则自动生成 `未命名-yyyyMMdd-HHmm` |
| agency | string \| null | 否 | 代理机构；不传或空白存 `null` |
| projectType | string \| null | 否 | 项目类型；不传或空白存 `null` |
| leaderName | string \| null | 否 | 项目负责人；不传或空白存 `null` |
| tenderPackageId | number | 是 | 本组织未绑定的招标包 ID（可先 `GET /api/packages?unboundOnly=true`） |
| bidPackageIds | number[] | 是 | 本组织未绑定的投标包 ID，**至少一个**；创建时一并绑定 |

绑定失败（无效/已占用/跨组织）→ `40000` 或 `40900`；同一事务内完成，失败则项目不会落库。

**成功响应**：`data` 为项目摘要（结构同下方 6.3）。

### 6.3 项目详情

`GET /api/projects/{projectId}`

跨组织或已删除 → `40400`。

**成功 `data` 示例**

```json
{
  "id": 1,
  "orgId": 2,
  "name": "市政道路改造工程",
  "agency": "某某招标代理",
  "projectType": "公开招标",
  "leaderName": "张三",
  "status": "ACTIVE",
  "workflowStatus": "NEED_TENDER_FILES",
  "createdBy": 10,
  "createdAt": "2026-07-21T08:00:00Z",
  "updatedAt": "2026-07-21T08:00:00Z",
  "tenderPackageId": 50,
  "tenderPackageCount": 1,
  "tenderFileCount": 1,
  "mainTenderFileName": "招标文件.pdf",
  "bidPackageCount": 2,
  "bidPackageUploadedCount": 1,
  "bidFileCount": 3,
  "aiReviewJobId": 501,
  "tenderPackage": {
    "id": 50,
    "orgId": 2,
    "projectId": 1,
    "type": "TENDER",
    "name": "市政招标文件包",
    "status": "ACTIVE",
    "fileCount": 1,
    "indexStatus": "SUCCESS"
  },
  "bidPackages": [
    {
      "id": 60,
      "orgId": 2,
      "projectId": 1,
      "type": "BID",
      "name": "华东智建科技",
      "status": "ACTIVE",
      "fileCount": 2,
      "indexStatus": "PROCESSING"
    },
    {
      "id": 61,
      "orgId": 2,
      "projectId": 1,
      "type": "BID",
      "name": "北辰数科",
      "status": "ACTIVE",
      "fileCount": 0,
      "indexStatus": null
    }
  ]
}
```

| 详情增补字段 | 说明 |
|--------------|------|
| aiReviewJobId | 最新一键 AI 审查总任务 id（`AI_REVIEW_PIPELINE`）；无则为 `null`。退出再进可用 `GET /api/jobs/{aiReviewJobId}` 续查进度；成功时 job 上还有 `reviewId`/`checklistId` |
| tenderPackage | 已绑招标包摘要；列表接口也为摘要字段时可能有值，包对象仅详情填充 |
| bidPackages | 项目下投标包列表；列表接口为 `null` |
| `*.indexStatus` | 包内文件解析汇总：`PENDING` 待解析 / `PROCESSING` 解析中 / `SUCCESS` 解析完成 / `FAILED` 解析失败；无文件为 `null`。多文件取最差：FAILED &gt; PROCESSING &gt; PENDING &gt; SUCCESS |

`GET /api/projects/{projectId}/bid-packages` 的包摘要同样返回 `indexStatus`。

### 6.4 更新基本信息

`PUT /api/projects/{projectId}`

```json
{
  "name": "市政道路改造工程-修订",
  "agency": "某某招标代理",
  "projectType": "公开招标",
  "leaderName": "张三"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| name | 是 | 项目名称 |
| agency / projectType / leaderName | 否 | 不传或空白会**清空为 `null`** |

请求体**不含** `tenderPackageId`（绑定后不可换包）。停用项目**仍可**更新基本信息（含改名）。

**成功响应**：`data` 为更新后的项目摘要（结构同 6.3）。

### 6.5 启用 / 停用

`PUT /api/projects/{projectId}/status`

```json
{ "status": "DISABLED" }
```

**成功响应**：`data` 为更新后的项目摘要（结构同 6.3，`status` 已变）。

停用后列表仍可见；后续标包/文件等业务将拦截（本期尚未实现这些业务）。

### 6.6 删除（逻辑删除）

`DELETE /api/projects/{projectId}`

`status` → `DELETED`，之后不可再查。  
已绑定的招标包**仍占着**（不可再绑到新项目、不可删）；释放规则以后另定。

**成功响应**

```json
{ "code": 0, "message": "ok", "data": null }
```

### 6.7 项目下投标包

#### 添加（绑定）投标包（已废弃）

`POST /api/projects/{projectId}/bid-packages`

> **已废弃**：请在 `POST /api/projects` 创建时传入 `bidPackageIds`。本接口暂保留兼容，后续移除。

```json
{ "bidPackageId": 60 }
```

须为本组织、类型 `BID`、未删除、**尚未绑定**的投标包。一投标包只能绑一个项目。  
未绑列表：`GET /api/packages?type=BID&unboundOnly=true`。

**成功响应**：`data` 为标包摘要（字段见 [API-文件与标包 · 标包摘要](./API-文件与标包.md)）。

#### 项目下投标包列表

`GET /api/projects/{projectId}/bid-packages?page=1&size=20`

返回已绑定到该项目的投标包分页；每条摘要含 **`indexStatus`**（包内文件解析汇总，规则同项目详情）。

**成功响应**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
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
        "fileCount": 3,
        "indexStatus": "SUCCESS"
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
| status | 包启停：`ACTIVE`（列表不含已删） |
| fileCount | 包内未删除文件数 |
| indexStatus | 解析汇总：`PENDING`/`PROCESSING`/`SUCCESS`/`FAILED`；无文件为 `null` |


---

## 隐藏逻辑（本册必读）

### 鉴权与会话
- JWT 内含 `tokenVersion`；请求时与库中对比，不一致则 **401**（会话已吊销）。
- `mustChangePassword=true` 时，除 `/api/auth/change-password`、`/api/auth/logout`、`/api/auth/me` 外一律 **403「请先修改密码」**。
- `PLATFORM_SUPER_ADMIN` 在鉴权上等同平台管理员（可访问全部 `/api/admin/**`）；业务上另有超管专属能力。

### 账号
- 平台首页 `GET /api/admin/dashboard`：组织数排除 SYSTEM/DELETED；管理员按角色去重后按 ACTIVE/DISABLED 计；审查/文档解析为全平台计数（文档按未删除 + indexStatus）。
- 创建平台管理员：固定挂 **SYSTEM** 组织、角色固定 `PLATFORM_ADMIN`；手机/邮箱为 `null`；`created_by` 记当前操作人。
- 超管全局唯一：仅现任超管可授予/摘除 `PLATFORM_SUPER_ADMIN`；授予时自动附带 `PLATFORM_ADMIN`，并摘掉原超管角色且 bump 其会话。
- 至少保留一名有效平台管理员、一名超管；停用/删除/改角色时后端拦截。
- 平台方列表 `GET /api/admin/platform-admins` 按角色过滤（普管或超管），支持 `status`；返回 `activeCount` / `disabledCount` 为全量统计；**不是**通用用户列表加参数。

### 组织
- 编码创建后不可改；公司名、城市可编辑（`PUT /api/admin/orgs/{orgId}`）；城市必须词典正式名；省份仅城市下拉联动，不落库。
- 列表/详情默认排除 `DELETED`；启停只用 `ACTIVE`/`DISABLED`。
- 组织列表 `GET /api/admin/orgs` 返回 `activeCount` / `disabledCount` / `pendingConfigCount` 为全量统计；`pendingConfigCount` = 未删除且下属无账号的组织数。
- 停用组织：不改用户 status，只对该组织用户 `token_version+1`；登录时组织非 ACTIVE（含已删）→ **403「所属组织不可用」**。
- 删组织：仅超管；SYSTEM 不可删/不可编辑；仍有用户/项目/标包/文件则禁止；成功后 status=DELETED 并 bump 会话。
- 删组织按钮是否展示由前端看 `roleCodes`；后端仍会 403 非超管。

### 项目
- 仅 `TENDERER`；平台管理员无招标方角色 → **403**。
- `orgId` 一律取登录态，前端不可跨组织指定；跨组织资源统一 **404**（防探测）。
- 创建项目必须绑一个本组织**未占用**招标包；绑完后招标包不可删、不可再绑；删项目不释放招标包占用（现行规则）。
- 绑包时同步回写该包内已上传文件的 `project_id`（支持先上传后创建项目）。
- 停用项目仍可改基本信息；已删项目不可查。
- `agency` / `projectType` / `leaderName` 创建与更新均可空；空白存 `null`；更新时传空白会清空。
- 列表/详情派生：`mainTenderFileName`（`fileType=招标文件`）、`bidPackageCount` / `bidPackageUploadedCount` / `bidFileCount`。
- `workflowStatus` 为派生字段（不落库）：待补充招标/投标（含解析中、解析失败）、待AI审查、AI审查中/完成/失败；与 `status` 启停分离；不做「待AI重新审查」。删投标包 / 删招标文件会将相关审查（及清单）标为 `OBSOLETE`，从而回到待审查或待补文件。
