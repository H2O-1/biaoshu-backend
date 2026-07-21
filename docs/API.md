# 招投标后端 · 接口文档（前端联调）

> 维护约定：**每新增/变更接口，同步更新本文档**。  
> 更新：2026-07-20  
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

> 说明：以上是**账号级平台角色**。项目成员角色后续另开接口，勿混用。

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
    "roleCodes": ["PLATFORM_ADMIN"],
    "mustChangePassword": false
  }
}
```

| 字段 | 说明 |
|------|------|
| accessToken | 后续请求放在 `Authorization: Bearer ...` |
| orgId / orgCode / orgName | 所属组织；前端展示用 `orgName` |
| phone | 手机号；引导管理员可能为空 |
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
| roleCodes | string[] | 是 | 至少 1 个合法平台角色 |

**成功响应**：`data` 为新建用户摘要（含 `mustChangePassword: true`、`phone`，不含密码）。

**常见失败**

| code | 说明 |
|------|------|
| 40000 | 参数不合法 / 组织不存在 / 密码或手机号不符合规则 / 角色为空或非法 |
| 40900 | 用户名或手机号已存在 |

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

## 4. 联调检查清单（前端）

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

---

## 5. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-20 | 初版：登录 + 平台管理员角色/用户查询与角色设置 |
| 2026-07-20 | 新增：平台管理员创建用户（跨组织 orgId、初始角色、密码≥8） |
| 2026-07-20 | 密码与会话闭环：`mustChangePassword` + `token_version`；改密/退出/重置/停用/删除 |
| 2026-07-21 | 登录与 `/api/auth/me` 返回 `orgCode` / `orgName`，便于展示所属组织 |
| 2026-07-21 | 用户增加手机号：创建必填、唯一；登录/me/用户列表返回 |
