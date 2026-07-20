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
| `/api/admin/**` | Header：`Authorization: Bearer <accessToken>`，且角色含 `PLATFORM_ADMIN` |

未登录 / Token 无效：

```json
{ "code": 40100, "message": "未登录或登录已失效", "data": null }
```

无权限：

```json
{ "code": 40300, "message": "无权限", "data": null }
```

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

### 1.5 本地联调账号（dev）

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
    "username": "admin",
    "displayName": "平台管理员",
    "roleCodes": ["PLATFORM_ADMIN"]
  }
}
```

| 字段 | 说明 |
|------|------|
| accessToken | 后续请求放在 `Authorization: Bearer ...` |
| roleCodes | 当前账号平台角色列表 |

**常见失败**

| code | 说明 |
|------|------|
| 40100 | 用户名或密码错误 |
| 40300 | 账号已停用 |

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
        "status": "ACTIVE",
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
| status | 当前仅见 `ACTIVE`；停用态后续扩展 |

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

### 3.4 设置用户角色（全量覆盖）

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
- 改角色后，对方旧 Token 里仍是旧角色；需重新登录或等 Token 过期（当前默认约 8 小时）后生效。建议产品上提示「请重新登录」。

---

## 4. 联调检查清单（前端）

- [ ] 登录拿到 `accessToken`，本地存储（方案后续可再定）
- [ ] 请求管理接口带上 `Authorization: Bearer ...`
- [ ] `code !== 0` 时用 `message` 提示用户
- [ ] 401 → 跳转登录；403 → 无权限提示
- [ ] 角色多选提交时传完整 `roleCodes` 数组

---

## 5. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-07-20 | 初版：登录 + 平台管理员角色/用户查询与角色设置 |
