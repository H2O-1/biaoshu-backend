# 招投标后端 · API（任务进度 SSE）

> 本册范围：**异步任务进度推送（SSE）**，以及与短轮询的配合方式。  
> 任务创建、索引 / 建清单 / 审查业务接口见：[检索重排与LLM](./API-检索重排与LLM.md)  
> 登录鉴权见：[账号组织项目](./API-账号组织项目.md) · [总索引](./API.md)  
> Base URL（本地）：`http://localhost:8080`  
> 更新：2026-08-04

---

## 1. 为什么用 SSE

长任务（`INDEX` / `BUILD_CHECKLIST` / `REVIEW` / `AI_REVIEW_PIPELINE`）写入表 `biz_job` 后异步执行。  
前端需要知道 `status`、`progress`、`message`。

| 方式 | 接口 | 说明 |
|------|------|------|
| **短轮询（保留）** | `GET /api/jobs/{jobId}` | 定时拉快照；刷新、断线、SSE 关闭时**必须能用** |
| **SSE（推荐新页面）** | `GET /api/jobs/{jobId}/events` | 服务端有变化再推；减少无效请求，进度更顺 |

**真相源仍是数据库 `biz_job`。** SSE 只是推送通道，不替代落库状态。

---

## 2. 鉴权（必读）

与其它业务接口相同：

```http
Authorization: Bearer <accessToken>
```

- 角色：仅 **`TENDERER`**
- 任务须属**当前用户所在组织**；不存在或跨组织 → **40400**（与查询任务一致，故意模糊）
- 未登录 / Token 失效 → **40100**

### 前端不要用原生 `EventSource`

浏览器 `EventSource` **不能**设置自定义 Header，带不上 `Authorization`。

请使用 **`fetch` + `ReadableStream`（或等价库）** 解析 `text/event-stream`，请求头照常带 Bearer。

---

## 3. 短轮询（保留）

### `GET /api/jobs/{jobId}`

- 响应：统一包装 `{ code, message, data }`，成功 `code=0`
- `data`：`JobSummary`（字段见下文）
- 用途：
  - 旧页面继续轮询
  - 新页面：**进入/刷新时先调一次拿快照**；若仍为 `PENDING`/`RUNNING` 再开 SSE
  - SSE 失败时的兜底

索引完成判定示例：任务 `status=SUCCESS`，且文件列表中该文档 `indexStatus=SUCCESS`。

---

## 4. 任务进度 SSE

### `GET /api/jobs/{jobId}/events`

| 项 | 说明 |
|----|------|
| Method / Path | `GET /api/jobs/{jobId}/events` |
| 成功响应类型 | `Content-Type: text/event-stream` |
| 响应形态 | **不是** `{ code, message, data }` 包装；每条事件的 `data` 行为 **JobSummary 的 JSON 一行** |
| 建连前失败 | 鉴权/不存在等仍走常规 JSON 错误（如 401 / 404 业务码） |
| 关闭条件 | 推送终态（`done` / `failed`）后服务端结束流；客户端也可主动 `abort` |

### 4.1 行为

1. 连接建立后**立即**推当前任务状态（首包事件名见下表）。
2. 服务端按配置间隔读 `biz_job`，仅当下列字段变化时再推：
   - `status`、`progress`、`message`、`errorMessage`、`payloadJson`
   - 一键审查额外关心摘要中的 `checklistId`、`reviewId`（从 payload 抽出）
3. 无变化时定期发 SSE **注释心跳**（`: ping`），降低代理掐空闲连接概率。
4. `status` 为 `SUCCESS` / `FAILED` / `CANCELLED` 时推送对应终态事件后**关闭流**。
5. **不替代**短轮询：刷新、重连务必先 `GET /api/jobs/{jobId}`。

### 4.2 事件名

| event | 含义 |
|-------|------|
| `snapshot` | 首包，且任务仍为 `PENDING` / `RUNNING` |
| `progress` | 后续进行中状态有变化 |
| `done` | `status=SUCCESS`（含一连接已是成功） |
| `failed` | `status=FAILED` 或 `CANCELLED` |

### 4.3 流示例

```text
event: snapshot
data: {"id":55,"orgId":2,"projectId":10,"type":"AI_REVIEW_PIPELINE","status":"RUNNING","progress":40,"message":"建清单中","payloadJson":"{\"projectId\":10}","errorMessage":null,"retryCount":0,"createdBy":10,"createdAt":"2026-08-04T08:00:00Z","startedAt":"2026-08-04T08:00:01Z","finishedAt":null,"updatedAt":"2026-08-04T08:01:00Z","checklistId":null,"reviewId":null}

: ping

event: progress
data: {"id":55,"orgId":2,"projectId":10,"type":"AI_REVIEW_PIPELINE","status":"RUNNING","progress":70,"message":"AI审查中","payloadJson":"{\"projectId\":10}","errorMessage":null,"retryCount":0,"createdBy":10,"createdAt":"2026-08-04T08:00:00Z","startedAt":"2026-08-04T08:00:01Z","finishedAt":null,"updatedAt":"2026-08-04T08:05:00Z","checklistId":null,"reviewId":null}

event: done
data: {"id":55,"orgId":2,"projectId":10,"type":"AI_REVIEW_PIPELINE","status":"SUCCESS","progress":100,"message":"审查完成","payloadJson":"{\"projectId\":10,\"phase\":\"DONE\",\"checklistId\":88,\"reviewId\":201,\"reusedChecklist\":false}","errorMessage":null,"retryCount":0,"createdBy":10,"createdAt":"2026-08-04T08:00:00Z","startedAt":"2026-08-04T08:00:01Z","finishedAt":"2026-08-04T08:10:00Z","updatedAt":"2026-08-04T08:10:00Z","checklistId":88,"reviewId":201}
```

---

## 5. JobSummary 字段

与 `GET /api/jobs/{jobId}` 的 `data`、以及创建任务返回的任务摘要一致。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | number | 任务 id（即 jobId） |
| orgId | number | 组织 id |
| projectId | number \| null | 项目 id |
| type | string | `INDEX` / `BUILD_CHECKLIST` / `REVIEW` / `AI_REVIEW_PIPELINE` |
| status | string | `PENDING` → `RUNNING` → `SUCCESS` / `FAILED` / `CANCELLED` |
| progress | number | 0–100 进度 |
| message | string \| null | 进度文案（如「建清单中」「AI审查中」） |
| payloadJson | string \| null | 任务入参/流水线扩展 JSON 字符串 |
| errorMessage | string \| null | 失败原因 |
| retryCount | number | 重试次数 |
| createdBy | number | 创建人用户 id |
| createdAt / startedAt / finishedAt / updatedAt | string \| null | ISO-8601 时间 |
| checklistId | number \| null | **仅**一键审查类型可能有：清单 id |
| reviewId | number \| null | **仅**一键审查类型可能有：审查 id |

任务类型说明见 [检索重排与LLM](./API-检索重排与LLM.md)（创建接口返回 `jobId` 后即可订阅本册 SSE）。

---

## 6. 前端推荐接入流程

```text
1. 调业务接口创建任务 → 拿到 jobId
   （索引 / 建清单 / 审查 / 一键审查 均可）
2. （可选）GET /api/jobs/{jobId} 确认存在
3. fetch 打开 GET /api/jobs/{jobId}/events
   Header: Authorization + Accept: text/event-stream
4. 解析 event / data：
   - snapshot / progress → 更新进度条与文案
   - done → 成功 UI；一键审查可读 checklistId、reviewId
   - failed → 失败 UI，展示 errorMessage
5. 终态后关闭 reader / AbortController
6. 用户刷新页面：
   - 先 GET /api/jobs/{jobId}
   - 若仍 PENDING/RUNNING → 再开 SSE
   - 若已 SUCCESS/FAILED → 直接展示，不必开 SSE
```

### 6.1 fetch 示意

```javascript
async function subscribeJob(jobId, accessToken, onEvent) {
  const controller = new AbortController();
  const res = await fetch(`/api/jobs/${jobId}/events`, {
    method: 'GET',
    headers: {
      Authorization: `Bearer ${accessToken}`,
      Accept: 'text/event-stream',
    },
    signal: controller.signal,
  });
  if (!res.ok) {
    // 401/403/404 等：按 JSON 错误体处理（若有）
    throw new Error('SSE HTTP ' + res.status);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let eventName = 'message';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split('\n');
    buffer = parts.pop() || '';
    for (let i = 0; i < parts.length; i++) {
      const line = parts[i];
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        const raw = line.slice(5).trim();
        let data = raw;
        try {
          data = JSON.parse(raw);
        } catch (e) {
          // 保持字符串
        }
        onEvent(eventName, data);
        if (eventName === 'done' || eventName === 'failed') {
          controller.abort();
          return;
        }
        eventName = 'message';
      } else if (line.startsWith(':')) {
        // 心跳注释，可忽略
      }
    }
  }
}
```

> 以上为联调示意；生产请按你们前端工程封装错误处理、重连策略与 TypeScript 类型。

### 6.2 重连建议

- 非终态断线：退避重连（如 1s / 2s / 5s），重连前先 `GET /api/jobs/{jobId}`。
- 已是终态：不要死循环重连 SSE。
- 同一 `jobId` 避免开多个并行 SSE（浪费连接）。

---

## 7. 配置项

配置前缀：`bidding.job.sse`（`application.yml`）。

| 配置 / 环境变量 | 默认 | 说明 |
|-----------------|------|------|
| `enabled` / `JOB_SSE_ENABLED` | `true` | 关闭后订阅接口返回业务错误（如「任务 SSE 未启用」） |
| `poll-ms` / `JOB_SSE_POLL_MS` | `1000` | 服务端读库间隔（毫秒） |
| `heartbeat-ms` / `JOB_SSE_HEARTBEAT_MS` | `15000` | 无变化时心跳间隔 |
| `timeout-ms` / `JOB_SSE_TIMEOUT_MS` | `7200000` | 单连接超时（与长任务量级对齐，约 2 小时） |

---

## 8. 网关 / Nginx

SSE 为长连接，反代注意：

- 关闭响应缓冲（如 `proxy_buffering off`）
- 适当加大 `proxy_read_timeout`（不低于任务最长耗时）
- 可选响应头：`X-Accel-Buffering: no`

跨域：与其它 API 相同，须在 `CORS_ALLOWED_ORIGINS` 中配置前端 Origin；预检 OPTIONS 已放行。详见 [API.md](./API.md) CORS 说明。

---

## 9. 与业务接口的对应关系

| 业务动作 | 拿到 jobId 的接口（详见检索册） | 进度订阅 |
|----------|--------------------------------|----------|
| 文档索引 | `POST /api/packages/{packageId}/files/{documentId}/index` | 本册 SSE 或短轮询 |
| 建清单 | `POST /api/projects/{projectId}/checklists` | 同上 |
| 一键审查 | `POST /api/projects/{projectId}/ai-reviews/run` | 同上；成功后可读 `checklistId`/`reviewId` |
| 项目详情续查 | 项目上的 `aiReviewJobId` 等字段 | 用该 id 调本册接口 |

---

## 10. 隐藏逻辑（本册必读）

1. **轮询接口永久保留**，不是临时兼容；SSE 是增强。
2. **服务端实现是读库 diff**，不是 MQ；进度粒度约等于 `poll-ms`，不是毫秒级总线推送。
3. **多实例部署**：每台机器各自为已建立的 SSE 连接读库推送即可，不依赖本机内存事件总线。
4. **原生 EventSource 不可用**（Bearer）；必须用可自定义 Header 的 HTTP 客户端。
5. 任务被服务重启回收为 `FAILED` 时（见后端 `StaleAiWorkRecovery`），SSE 会推到 `failed` 或下次轮询看到失败。
6. 本册**只覆盖 job 进度**，不包含「项目聚合状态」专用 SSE；项目级字段仍走项目查询接口。

---

## 11. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-08-04 | 初版：`GET /api/jobs/{jobId}/events`；保留 `GET /api/jobs/{jobId}` 短轮询 |
