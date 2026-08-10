# 招投标后端 · 接口文档（前端联调）

> 维护约定：**每新增/变更接口，同步更新本文档**。  
> 更新：2026-07-21  
> Base URL（本地）：`http://localhost:8080`  
> 联调机（示例）：`http://192.168.8.83`（经 Nginx 反代到后端）

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

---

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

---

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
| keyword | string | 否 | `""` | 统一关键字：公司名称 / 城市 / 负责人 / 手机号 / 账号 / 邮箱模糊搜 |
| name | string | 否 | `""` | 兼容旧前端；`keyword` 为空时按统一关键字处理 |
| city | string | 否 | `""` | 所在城市附加筛选 |
| status | string | 否 | `""` | `ACTIVE` / `DISABLED`；空=全部未删除（不含 `DELETED`） |
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
    "size": 20
  }
}
```

| 字段 | 说明 |
|------|------|
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

## 5. 联调检查清单（前端）

- [ ] 登录拿到 `accessToken`，本地存储；同时保存 `orgName` / `roleCodes` 用于顶栏展示
- [ ] 刷新页面可调 `GET /api/auth/me` 恢复当前用户与组织信息
- [ ] 请求业务/管理接口带上 `Authorization: Bearer ...`
- [ ] `code !== 0` 时用 `message` 提示用户
- [ ] 401 → 清 Token 跳转登录；403 → 无权限 / 请先改密提示
- [ ] `mustChangePassword === true` → 强制改密页，不可进业务
- [ ] 改密页：两次输入一致前端校验；成功后重新登录
- [ ] 管理员重置密码：弹窗一次性展示 `temporaryPassword`，关闭后不可再看
- [ ] 退出登录调 `/api/auth/logout` 并清本地 Token；浏览器返回也不能进业务页
- [ ] 角色多选提交时传完整 `roleCodes` 数组
- [ ] 平台首页汇总：`GET /api/admin/dashboard`（组织数/管理员启停/审查成功失败/文档解析成功失败）
- [ ] 平台方账号列表：`GET /api/admin/platform-admins` 分页；详情看创建时间/创建人/更新时间
- [ ] 修改招标方账号资料：`PUT /api/admin/orgs/{orgId}/users/{userId}/profile`（姓名/手机/邮箱必填）
- [ ] 创建平台管理员：`POST /api/admin/platform-admins`，只填 displayName / username / password
- [ ] 创建用户密码：≥8 且含字母+数字
- [ ] 组织：可编辑名称/城市；`code` 不可改；停用组织后不可再往该组织建用户
- [ ] 组织删除：仅超管可见入口；`DELETE /api/admin/orgs/{orgId}`；有用户/项目/标包/文件时禁止；已删不在列表
- [ ] 超管转让：仅现任超管可赋 `PLATFORM_SUPER_ADMIN`；全局唯一，转让后原超管失效需重登
- [ ] 创建组织：城市从 `GET /api/admin/cities` 选择，传正式名（如「深圳市」）
- [ ] 组织二级：点进组织调 `GET /api/admin/orgs/{orgId}/users` 看该组织账号（含系统组织）
- [ ] 停用组织后：该组织用户旧 Token 立即 401，需重新登录（组织启用后）
- [ ] 项目：仅招标方；平台管理员 403；停用后列表可见且可改基本信息
- [ ] 创建项目：必须 `tenderPackageId`（未绑招标包）；绑定后包不可删、不可再绑
- [ ] 投标包：先建后选；`POST /api/projects/{id}/bid-packages` 绑定；公司名同组织唯一；已绑可删
- [ ] 招标包：仅招标方；未绑可删；已绑项目不可删；unboundOnly 可选未绑列表
- [ ] 文件：标包下上传 PDF/Word/Excel；≤100MB；每包≤5；有文件不可删包

---

## 6. 项目 Projects

> 仅角色含 `TENDERER` 的用户。平台管理员无招标方角色时 **403**。  
> 项目挂在当前用户所属组织；`orgId` 由服务端取自登录态，前端不可跨组织指定。  
> **无项目成员角色**。创建时必须绑定一个本组织**未占用**的招标包。  
> 文件数量本期占位为 `0`。

### 6.1 项目分页列表

`GET /api/projects`

**Query**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| keyword | string | 否 | `""` | 名称 / 代理机构 / 负责人模糊搜 |
| status | string | 否 | `""` | `ACTIVE` / `DISABLED`；空=未删除全部 |
| page | number | 否 | 1 | 页码 |
| size | number | 否 | 20 | 每页条数，最大 100 |

停用项目仍出现在列表中（可按 status 筛选）。已删除不返回。

### 6.2 创建项目

`POST /api/projects`

```json
{
  "name": "市政道路改造工程",
  "agency": "某某招标代理",
  "projectType": "公开招标",
  "leaderName": "张三",
  "tenderPackageId": 50
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 项目名称；同组织内未删除唯一；创建后可改 |
| agency | string | 是 | 代理机构 |
| projectType | string | 是 | 项目类型 |
| leaderName | string | 是 | 项目负责人 |
| tenderPackageId | number | 是 | 本组织未绑定的招标包 ID（可先 `GET /api/packages?unboundOnly=true`） |

绑定失败（无效/已占用/跨组织）→ `40000` 或 `40900`；同一事务内完成，失败则项目不会落库。

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
  "createdBy": 10,
  "createdAt": "2026-07-21T08:00:00Z",
  "updatedAt": "2026-07-21T08:00:00Z",
  "tenderPackageId": 50,
  "tenderPackageCount": 1,
  "tenderFileCount": 0
}
```

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

请求体字段同创建中的基本信息字段（**不含** `tenderPackageId`，绑定后不可换包）。停用项目**仍可**更新基本信息（含改名）。

**成功响应**：`data` 为更新后的项目摘要（结构同 6.3）。

### 6.5 启用 / 停用

`PUT /api/projects/{projectId}/status`

```json
{ "status": "DISABLED" }
```

停用后列表仍可见；后续标包/文件等业务将拦截（本期尚未实现这些业务）。

### 6.6 删除（逻辑删除）

`DELETE /api/projects/{projectId}`

`status` → `DELETED`，之后不可再查。  
已绑定的招标包**仍占着**（不可再绑到新项目、不可删）；释放规则以后另定。

### 6.7 项目下投标包

#### 添加（绑定）投标包

`POST /api/projects/{projectId}/bid-packages`

```json
{ "bidPackageId": 60 }
```

须为本组织、类型 `BID`、未删除、**尚未绑定**的投标包。一投标包只能绑一个项目。  
未绑列表：`GET /api/packages?type=BID&unboundOnly=true`。

#### 项目下投标包列表

`GET /api/projects/{projectId}/bid-packages?page=1&size=20`

返回已绑定到该项目的投标包分页；每条摘要含文件数/解析状态，以及最新成功审查下的 `suggestedScoreTotal`、`fullScoreTotal`、`qualificationMatchCount`、`qualificationMismatchCount`、`qualificationUnknownCount`。

---

## 7. 标包 Packages

> 仅角色含 `TENDERER` 的用户。平台管理员无招标方角色时 **403**。  
> 挂在当前用户所属组织；`orgId` 由服务端取自登录态。  
> **创建时均不绑项目**（`projectId` 为空）：  
> - 招标包：创建项目时选绑（见 6.2）  
> - 投标包：在项目里添加时选绑（见 6.7）  
> `fileCount` 占位为 `0`（文件下期）。  
> `name`：招标=包名；投标=投标公司名称（同组织未删除唯一）。

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
  "contactPhone": "13800001111"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| type | string | 是 | `TENDER` / `BID` |
| name | string | 否 | 招标=包名；投标=公司名；同组织同类型唯一；空则自动生成 `未命名-yyyyMMdd-HHmm`（冲突再加秒/序号） |
| contactName | string | 否 | 负责人姓名（仅投标包有意义） |
| contactPhone | string | 否 | 负责人电话（仅投标包有意义） |

创建后 `projectId` 为 null。**更新接口仍要求名称等字段，未一并放宽。**

### 7.3 标包详情

`GET /api/packages/{packageId}`

跨组织或已删除 → `40400`。

### 7.4 更新标包

`PUT /api/packages/{packageId}`

招标包：`{ "name": "..." }`  
投标包：`{ "name", "contactName", "contactPhone" }`（均必填）

已绑定项目的仍可改。

### 7.5 删除（逻辑删除）

`DELETE /api/packages/{packageId}`

| 类型 | 规则 |
|------|------|
| 招标包 | 已绑定项目 → `40900`；未绑可删 |
| 投标包 | **可直接删**（已绑则项目下不再可见）；**级联逻辑删除包内文件**；已绑项目且包内有文件时作废审查，空投标包删除不影响审查状态 |

---

## 8. 标包文件 Documents

> 仅 `TENDERER`。文件挂在标包下；类型限 PDF / Word / Excel；单文件 ≤ 100MB；每包最多 5 个。  
> 能力：上传 / 列表 / 下载 / PDF 预览 / 删除。  
> 鉴权：与其它接口相同，仅 `Authorization: Bearer <accessToken>`（前端拉 blob 后用 `blob:` URL 页内展示）。  
> `indexStatus`：`PENDING` / `PROCESSING` / `SUCCESS` / `FAILED`（M1 起可由任务回写；上传默认 `PENDING`）。  
> local 测试用内存存储；dev 连 MinIO（bucket `bidding-dev`）。

### 8.0 异步任务与索引（M2 / M3）

| 能力 | 说明 |
|------|------|
| 表 `biz_job` | 统一异步任务；INDEX 的 payload 含 `documentId` |
| 表 `biz_document_chunk` / `biz_document_vector` | INDEX 产物；向量暂存 `embedding_json`（后续可迁 pgvector） |
| 任务状态 | `PENDING` → `RUNNING` → `SUCCESS` / `FAILED` / `CANCELLED` |
| 文档 `indexStatus` | 创建任务时 `PROCESSING`；Worker 成功/失败回写 `SUCCESS` / `FAILED` |
| 执行器（当前） | 同进程定时认领；`INDEX_MODE=real` 时真抽文本/切块 + 百炼向量 |
| 真索引 M1 | `DocumentContentPort`：跨模块打开未删除文档的对象存储流 |
| 真索引 M2/M5 | 阿里云百炼 `text-embedding-v4`（OpenAI 兼容 `/embeddings`）；Java `HttpEmbeddingPort`；默认 1024 维、batch≤10 |
| 真索引 M3 | PDF（PDFBox）/ Word docx（POI）抽文本；空文本/扫描件 → FAILED |
| 真索引 M4 | 章节切块：`ChapterChunkingPort`（PDF Outline → 标题启发式 → 固定窗口；超长章再切）；`mode=real` 启用 |
| 召回 / 重排列表 | **不入库**；执行时打日志；开发期可用 `recall-debug` 接口查看 |
| 向量召回 | `VectorRecallPort`：项目范围 + 可选 packageIds/documentIds；应用层余弦（embedding_json） |
| 审查维度 | 全局表 `biz_review_dimension` + `biz_review_dimension_query`；招标文件 RAG 13 维（V26）；平台管理员可改 |
| 重排 | `RerankPort` + 百炼 `qwen3-rerank`（`compatible-api/v1/reranks`）；结果不入库；开发期 `rerank-debug` |
| 建清单 | `BUILD_CHECKLIST`：招标侧多维召回→重排→LLM 抽取→`biz_requirement_checklist/item`；异步 Job |
| 智能审查 | `REVIEW`：投标侧逐条召回→重排→LLM 对照→`biz_review/item`；异步 Job |

角色：仅 `TENDERER`；跨组织资源一律 `40400`。

配置（可选）：`INDEX_MODE`、`DASHSCOPE_API_KEY`、`EMBEDDING_*`、`RECALL_*`、`RERANK_*`、`CHECKLIST_*`、`LLM_*`。

#### 8.0.1 发起文档索引

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

#### 8.0.2 查询任务

`GET /api/jobs/{jobId}`

成功 `data` 字段同 8.0.1。不存在或非本组织 → `40400`。  
轮询至 `status=SUCCESS` 且文件列表里该文档 `indexStatus=SUCCESS` 即索引完成。

#### 8.0.3 向量召回调试（开发期）

`POST /api/projects/{projectId}/recall-debug`

仅当 `bidding.recall.debug-enabled=true`（环境变量 `RECALL_DEBUG`）时注册；关闭后接口不存在（404）。  
仅 `TENDERER`；项目须属本组织。召回结果**不入库**，同时写应用日志。

**请求体**

```json
{
  "query": "投标人资格条件",
  "topK": 10,
  "packageIds": [50],
  "documentIds": null
}
```

| 字段 | 说明 |
|------|------|
| query | 必填 |
| topK | 可选；不传或 ≤0 用配置默认（40） |
| packageIds | 可选；只搜这些标包 |
| documentIds | 可选；只搜这些文档 |

**成功 `data`**：命中数组，按 score 降序；含 `chunkId`、`documentId`、`fileName`、`packageId`、`packageType`（`TENDER`/`BID`）、`packageName`、`content`、`score` 等。

### 8.0.4 审查维度（全局）

全局一份配置，供建清单勾选与多 query 召回。  
表：`biz_review_dimension`（**id 主键**，`code` 唯一业务键） / `biz_review_dimension_query`（挂 **`dimension_id`**）。

种子：招标文件 RAG 13 维（V26；详见现行册 `API-检索重排与LLM.md` §8.0.4）。列表以 `id` + 中文 `name` 为主，`code` 给系统用。

#### 只读（登录即可）

`GET /api/review-dimensions`

返回**启用中**的维度及 `queries`。

**成功 `data` 元素示例**

```json
{
  "id": 1,
  "code": "QUALIFICATION",
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

#### 8.0.5 召回后重排调试（开发期）

`POST /api/projects/{projectId}/rerank-debug`

仅当 `bidding.rerank.debug-enabled=true`（`RERANK_DEBUG`）时注册。  
流程：项目向量召回 → 百炼 `qwen3-rerank` → 返回 topN（**不入库**）。  
注意：重排 URL 为业务空间 **`compatible-api/v1/reranks`**（与 embedding 的 `compatible-mode` 不同）。

**请求体**

```json
{
  "query": "投标人资格条件与资质等级要求",
  "recallTopK": 40,
  "rerankTopN": 8,
  "side": "TENDER",
  "packageIds": null,
  "documentIds": null
}
```

| 字段 | 说明 |
|------|------|
| query | 必填 |
| recallTopK | 可选；默认配置 40 |
| rerankTopN | 可选；TENDER 默认 8，BID 默认 6 |
| side | `TENDER`（默认）或 `BID`，决定 instruct / 默认 topN |
| packageIds / documentIds | 可选过滤 |

**成功 `data`**：含文件区分字段，以及 `recallScore`、`rerankScore`。

#### 8.0.6 建清单（BUILD_CHECKLIST）

异步：按勾选审查维度，在**招标包**已 INDEX 成功的文档上多 query 召回 → **综合 query（维度名+全部侧面）重排一次** → LLM 抽取 → 落库新版本清单。  
不做人工确认；`SUCCESS` 即可被后续 REVIEW 引用。

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
| dimensionCodes | 可选；不传则用维度表 `defaultSelected` 且 enabled |
| documentIds | 可选；须属本项目招标包且 `indexStatus=SUCCESS`；不传则用该包下全部已成功索引文档 |

**成功 `data`**

```json
{ "checklistId": 88, "jobId": 99, "version": 1 }
```

前置：项目已绑招标包；至少 1 份招标文档 INDEX 成功。  
轮询：`GET /api/jobs/{jobId}`；清单状态 `BUILDING` → `SUCCESS` / `FAILED`。

`GET /api/projects/{projectId}/checklists` — 列表（按 version 降序）

`GET /api/projects/{projectId}/checklists/{checklistId}` — 详情（含 `items`）

配置：`CHECKLIST_*`、`LLM_BASE_URL`、`LLM_MODEL`（默认 `qwen3.7-plus`）、`LLM_PROMPT_VERSION`（复用 `DASHSCOPE_API_KEY`）。

#### 8.0.7 智能审查（REVIEW）

异步：基于某版 **SUCCESS** 清单，在本项目 **投标包** 已 INDEX 文档上逐条召回 → 重排 → LLM 对照 → 落库审查报告。  
总判定：任一条目 `FAIL` → `FAIL`；全部 `PASS` → `PASS`；否则 `PARTIAL`。`needHumanReview` 默认 `true`。

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
轮询：`GET /api/jobs/{jobId}`；审查状态 `BUILDING` → `SUCCESS` / `FAILED`。

`GET /api/projects/{projectId}/reviews` — 列表（按 version 降序）

`GET /api/projects/{projectId}/reviews/{reviewId}` — 详情（含 `items`：verdict / reason / bidExcerpt / 证据定位）

配置：`REVIEW_*`、`LLM_REVIEW_PROMPT_VERSION`（默认 `review-v2`）；对照模型复用 `LLM_MODEL`（产品向对照提示词）。

---

### 8.1 上传

`POST /api/packages/{packageId}/files`  
`Content-Type: multipart/form-data`

| 字段 | 必填 | 说明 |
|------|------|------|
| `file` | 是 | 文件本体（`multipart` part） |
| `fileType` | 是 | 业务文件类型；自由字符串，后端只存不校验枚举；最长 128 |

### 8.2 列表

`GET /api/packages/{packageId}/files`

**成功 `data` 元素示例**

```json
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
  "indexStatus": "PENDING",
  "status": "ACTIVE",
  "createdBy": 10,
  "createdAt": "2026-07-22T06:00:00Z",
  "updatedAt": "2026-07-22T06:00:00Z"
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

先删对象存储，再逻辑删元数据。

---

## 9. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-28 | 组织详情增加标包/文档解析/REVIEW 成功失败统计 |
| 2026-07-28 | 建清单重排改为综合 query（维度名+全部侧面拼接），仍一次官方 rerank |
| 2026-07-28 | REVIEW：V13 审查表 + 投标侧召回/重排/LLM 对照 + 异步 API |
| 2026-07-28 | BUILD_CHECKLIST：V11 清单表 + LlmPort + 异步建清单 API（招标侧） |
| 2026-07-28 | 真重排 qwen3-rerank（RerankPort + 召回→重排 debug）；结果不入库 |
| 2026-07-29 | 平台账号列表支持 `status` 筛选，并返回全量 `activeCount` / `disabledCount` |
| 2026-07-29 | 创建标包：仅 `type` 必填；`name` 空则自动生成；联系人可空；更新接口未放宽 |
| 2026-07-29 | 新增 `PUT /api/admin/orgs/{orgId}`：可编辑公司名称与城市；`code` 仍不可改 |
| 2026-07-29 | 组织列表摘要增加 `tenderPackageCount` / `bidPackageCount`（与详情口径一致） |
| 2026-07-29 | 新增 `GET /api/admin/dashboard`：平台首页汇总（组织/管理员/审查/文档解析计数；提示词暂不做） |
| 2026-07-29 | 新增 `GET /api/admin/platform-admins` 分页列表与详情；用户摘要补齐 createdAt/updatedAt/createdBy；V15 `sys_user.created_by` |
| 2026-07-29 | 新增 `PUT /api/admin/orgs/{orgId}/users/{userId}/profile`：改招标方账号负责人姓名/手机/邮箱 |
| 2026-07-29 | 新增 `POST /api/admin/platform-admins`：创建平台管理员（固定系统组织 + PLATFORM_ADMIN；仅姓名/账号/初始密码） |
| 2026-07-28 | 超管 `PLATFORM_SUPER_ADMIN`（全局唯一）+ 组织逻辑删 `DELETE /api/admin/orgs/{orgId}`；列表/详情不含 DELETED；已删组织不可登录 |
| 2026-07-28 | 维度改 id 主键；query 挂 dimension_id；管理接口改为 PUT .../{id} |
| 2026-07-28 | 全局审查维度入表（V9）+ 只读/管理 API；种子 4 维及默认 query |
| 2026-07-27 | 向量召回 Port（项目范围+文件区分）+ 开发期 `recall-debug` 接口（`RECALL_DEBUG`） |
| 2026-07-27 | 向量改为百炼 `text-embedding-v4`（OpenAI 兼容）；下线本地 embedding-service / BGE-M3 |
| 2026-07-27 | 真索引 M5：embedding-service 加载 BGE-M3（sentence-transformers）；stub 可降级 |
| 2026-07-27 | 真索引 M4：章节切块（Outline/标题/窗口降级）；ExtractResult 带 outline |
| 2026-07-27 | 真索引 M3：PDFBox/POI 抽文本；real 模式 INDEX 读文件；拒 Excel/老 doc |
| 2026-07-27 | 真索引 M2：embedding-service stub（1024）+ HttpEmbeddingPort（mode=real） |
| 2026-07-27 | 真索引 M1：DocumentContentPort 打开文档对象流（file 适配） |
| 2026-07-27 | M3：Fake INDEX Worker；biz_document_chunk/vector；同进程认领执行 |
| 2026-07-27 | M2：`POST .../files/{documentId}/index`、`GET /api/jobs/{jobId}`；PROCESSING 防重复提交 |
| 2026-07-27 | M1：biz_job + JobPort/DocumentAccessPort；index_status 可回写；召回/重排列表不入库须打日志 |
| 2026-07-22 | 标包文件：下载 + PDF 预览；仍仅 Bearer；非 PDF 预览 400 |
| 2026-07-22 | 标包文件：上传/列表/删除；MinIO(S3)；有文件不可删包；fileCount 真数；index_status 占位 |
| 2026-07-22 | P3 投标包：先建后选；项目下绑定/列表；公司名同组织唯一；已绑可删 |
| 2026-07-22 | P2 创建项目必选未绑招标包并绑定；详情返回 tenderPackageId；删项目后包仍占用 |
| 2026-07-22 | P1 招标包 CRUD：本组织创建/列表/改名/删除；unboundOnly；已绑不可删；fileCount 占位 |
| 2026-07-20 | 初版：登录 + 平台管理员角色/用户查询与角色设置 |
| 2026-07-20 | 新增：平台管理员创建用户（跨组织 orgId、初始角色、密码≥8） |
| 2026-07-20 | 密码与会话闭环：`mustChangePassword` + `token_version`；改密/退出/重置/停用/删除 |
| 2026-07-21 | 登录与 `/api/auth/me` 返回 `orgCode` / `orgName`，便于展示所属组织 |
| 2026-07-21 | 用户增加手机号：创建必填、唯一；登录/me/用户列表返回 |
| 2026-07-21 | 用户增加邮箱：创建必填、唯一；登录/me/用户列表返回 |
| 2026-07-21 | M1 组织主数据：创建/列表/详情/启停；名称与编码创建后不可改；无删除 |
| 2026-07-21 | 全国地级市词典：`GET /api/admin/cities`；创建组织 city 必须精确匹配词典 |
| 2026-07-21 | M2 组织二级列表：`GET /api/admin/orgs/{orgId}/users`；排除 DELETED；复用用户摘要 |
| 2026-07-21 | M3 停用组织批量吊销会话：`token_version + 1`；启用不 bump；不改用户 status |
| 2026-07-21 | 项目 CRUD：招标方本组织；字段含代理机构/类型/负责人；逻辑删；组织 projectCount 接真数 |
