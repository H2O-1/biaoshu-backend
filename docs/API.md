# 招投标后端 · 接口文档（前端联调）

> 维护约定：**每新增/变更接口，同步更新本文档**。  
> 更新：2026-07-21  
> Base URL（本地）：`http://localhost:8080`

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
| `/api/admin/**` | Header：`Authorization: Bearer <accessToken>`，且角色含 `PLATFORM_ADMIN` |

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
| TENDERER | 招标方 |
| BIDDER | 投标方 |
| EXPERT | 专家 |

> 说明：以上为平台角色，挂在用户账号上。

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
| 角色 | PLATFORM_ADMIN |

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
| 40400 | 用户不存在 |
| 40900 | 系统至少保留一名平台管理员 |

**前端注意**

- 这是全量覆盖：最终角色 = 本次提交的数组，不是增量追加。
- 改角色后对方旧 Token **立即失效**，需重新登录。

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

将 `status` 设为 `DELETED`，并吊销会话。系统至少保留一名平台管理员。

---

## 4. 组织管理 Orgs（M1）

> 仅 `PLATFORM_ADMIN`（平台管理员，即原「超级管理员」口径）。  
> **公司名称 `name`、编码 `code` 创建后绝对不可改**；本期不提供删除、不提供改名接口。  
> **两级列表**：一级看组织；二级 `GET /api/admin/orgs/{orgId}/users` 看该组织下全部用户（组织与账号分开创建，可先建空组织）。  
> 组织状态仅 `ACTIVE` / `DISABLED`。系统组织列表可见、可点进看用户，但不可停用。  
> **所在城市**：创建时必须从全国地级市词典中选择（正式名，如「深圳市」，不可手填「深圳」）。

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
| status | string | 否 | `""` | `ACTIVE` / `DISABLED`；空=全部 |
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
| name | 公司名称，创建后不可改 |
| status | 库内仅 `ACTIVE` / `DISABLED` |
| userCount | 组织下未删除用户数 |
| projectCount | 项目模块未建，当前固定 `0` |

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
| name | string | 是 | 公司名称，全局唯一，最长 128；创建后不可改 |
| city | string | 是 | 所在城市，必须为 `GET /api/admin/cities` 返回的 `name`（精确匹配） |

**成功响应**：`data` 为新建组织摘要（含自动生成的 `code`，`status=ACTIVE`，`userCount=0`）。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 名称/城市为空，或城市不在地级市词典中 |
| 40900 | 公司名称已存在 |

允许先建空组织（无账号）。

---

### 4.3 组织详情

`GET /api/admin/orgs/{orgId}`

**成功响应**：`data` 结构同列表单条。

**失败**：不存在 → `40400`

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
- [ ] 创建用户密码：≥8 且含字母+数字
- [ ] 组织：创建后不可改公司名称；停用组织后不可再往该组织建用户
- [ ] 创建组织：城市从 `GET /api/admin/cities` 选择，传正式名（如「深圳市」）
- [ ] 组织二级：点进组织调 `GET /api/admin/orgs/{orgId}/users` 看该组织账号（含系统组织）
- [ ] 停用组织后：该组织用户旧 Token 立即 401，需重新登录（组织启用后）

---

## 6. 变更记录

| 日期 | 说明 |
|------|------|
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
