# OCR 服务接入功能拆分

> 目标：接入独立 OCR HTTP 服务，让扫描版 PDF、图文混排 PDF 在文件索引阶段可以被识别成可检索文本，并把可信页码写入 chunk 表。后续招标解析、投标模拟审核、项目一键审查、专家评审继续统一读取 `biz_document_chunk`，不在业务审查流程里分别处理 OCR。

## 1. 功能范围

### 1.1 本期要做

- 接入外部 OCR HTTP 服务。
- OCR 服务优先采用旧项目方案：RapidOCR + PP-OCR ONNX。
- OCR 服务以 Docker 容器方式部署，端口默认 `8090`。
- 后端在文件 `INDEX` 阶段调用 OCR。
- 支持 PDF 文档 OCR：
  - 可复制文本 PDF：优先使用 PDFBox 原生文本。
  - 扫描版 PDF：调用 OCR 识别整页文本。
  - 原生文本过少的 PDF：调用 OCR 增强文本抽取。
- OCR 返回的 `page_no` 写入 `DocumentTextExtractPort.ExtractResult.pages`。
- 后续 chunk 的 `pageNo` 只来自抽取结果 / OCR 结果，不使用 LLM 返回的页码。
- OCR 调用失败时，前端只展示干净业务提示，不展示 `request timed out`、连接异常、Python 堆栈等底层信息。
- 支持通过配置开关启用 / 关闭 OCR。
- 同步索引、招标解析、投标模拟审核相关文档中“扫描版 PDF”的说明。

### 1.2 本期不做

- 不做前端直接上传文件调用 OCR。
- 不新增单独的用户侧 OCR 页面。
- 不让招标解析 / 投标模拟审核 / 专家评审各自调用 OCR。
- 不做 LLM 视觉识别。
- 不做 OCR 坐标高亮。
- 不做 DOCX 内嵌图片 OCR。
- 不做图片文件单独 OCR 上传入口。
- 不做 OCR 结果人工校对工作流。
- 不做 OCR 失败后的静默兜底；需要 OCR 且 OCR 失败时，索引任务失败并给出明确提示。

## 2. 推荐业务流程

1. 用户上传招标文件 / 投标文件。
2. 后端创建文档记录和 `INDEX` 任务。
3. `IndexJobExecutor` 读取对象存储中的文件。
4. `PdfWordTextExtractPort` 对 PDF 先执行原生文本抽取。
5. 后端判断是否需要 OCR：
   - PDF 原生全文字符过少。
   - PDF 单页文本字符过少。
   - PDF 页面包含大图片。
6. 如需要 OCR，后端通过 HTTP 调用 OCR 服务。
7. OCR 服务返回每页识别文本和页码。
8. 后端组装 `ExtractResult.fullText` 和 `ExtractResult.pages`。
9. `ChapterChunkingPort` 基于 `ExtractResult.pages` 切 chunk。
10. `DocumentIndexStorePort` 写入 `biz_document_chunk.page_no` 和 `content`。
11. 后续招标解析、投标模拟审核、项目审查、专家评审均从 chunk 表读取文本和页码。

## 3. 已确认产品规则

- OCR 是后端索引能力，不是独立业务模块。
- OCR 服务是外部 HTTP 服务，类似 LLM 服务，但职责是图片文字识别。
- Java 后端不直接运行 OCR 模型，只负责调用 OCR HTTP 接口。
- OCR 服务推荐 Docker 部署，和 PostgreSQL、MinIO 一样由服务器维护。
- 线上统一部署根目录按 `/opt/biaoshu/backend` 组织，OCR 相关源码、Compose 和配置纳入这套目录体系，不单独拆到别的顶层目录。
- 页码统一以 chunk 表 `page_no` 为准。
- LLM 不负责返回页码；即使 LLM 返回页码，也需要后端按 `chunkId` 回填。
- 扫描版 PDF 如果 OCR 未启用，应继续索引失败，并提示启用 OCR 或检查文件。
- OCR 失败时不向前端暴露底层异常信息。
- 每完成一项开发，需要同步更新本拆分文档。
- 新增或变更接口时，需要同步更新相关 API 文档和 `docs/API.md` 总索引。

## 4. 与现有功能的关系

### 4.1 和文件索引的关系

OCR 接入文件索引链路：

- 当前索引链路：`IndexJobExecutor` → `DocumentTextExtractPort` → `ChunkingPort` → `DocumentIndexStorePort`。
- OCR 应接入 `DocumentTextExtractPort` 的 PDF 抽取实现。
- OCR 识别完成后仍然走现有切块、向量化、落库流程。

区别：

- 现有 PDFBox 只能抽取可复制文本。
- OCR 负责补齐扫描 PDF / 图片型 PDF 的文字。
- OCR 不改变后续业务模块读取 chunk 的方式。

### 4.2 和招标解析的关系

招标解析不直接调用 OCR。

- 招标解析读取招标文件 chunk。
- OCR 只影响 chunk 内容是否完整、页码是否可靠。
- 招标解析中的基础信息、资格要求、评分项、逻辑问题、错别字识别都继续使用现有 LLM 流程。

### 4.3 和投标模拟审核的关系

投标模拟审核不直接调用 OCR。

- 招标文件和投标文件上传后都会先走 `INDEX`。
- 如果上传的是扫描版 PDF，索引阶段完成 OCR 后，模拟审核才能读取到文本。
- 资格响应、评分响应、逻辑问题、错别字识别继续读取 chunk。

### 4.4 和专家评审的关系

专家评审不直接调用 OCR。

- 专家评审使用项目下已索引完成的招标文件和投标文件。
- OCR 只负责让扫描件在前置索引阶段产生可审查文本。
- 专家查看原文片段时仍然使用 evidence / chunk 对应内容。

## 5. 复用现有能力

### 5.1 可直接复用

- `DocumentContentPort` 打开对象存储中的文件流。
- `DocumentTextExtractPort` 抽取结果模型。
- `PdfWordTextExtractPort` PDF / DOCX 文本抽取入口。
- `ChapterChunkingPort` 基于 `ExtractResult.pages` 切块。
- `IndexJobExecutor` 异步索引任务。
- `JobPort` / `JdbcJobRepository` 任务状态流转。
- `DocumentIndexStorePort.replaceAll` chunk/vector 落库。
- 旧项目 OCR HTTP 客户端设计：
  - `OcrClient`
  - `OcrClientImpl`
  - `OcrPdfResponse`
  - `OcrPageResult`

### 5.2 需要小改或抽象复用

- `PdfWordTextExtractPort` 需要支持可选 OCR 客户端。
- `DocumentTextExtractPort.ExtractResult` 如需保留 OCR 信息，可补充轻量字段。
- `IndexJobExecutor` 的扫描件失败提示需要从“不支持 OCR”改为“文档 OCR 识别失败 / OCR 未启用”。
- 配置文件需要增加 `bidding.ocr` 配置段。
- 测试需要覆盖 OCR 启用、未启用、失败、返回页码等场景。

### 5.3 需要新增

- OCR Port 或客户端接口。
- OCR HTTP 客户端实现。
- OCR DTO：
  - PDF 响应。
  - 页响应。
  - 可选图片响应。
- OCR 配置类。
- OCR 服务 Docker 部署说明。
- OCR 接入相关单元测试。
- OCR 功能拆分文档：`docs/OCR服务接入功能拆分.md`。

## 6. 配置建议

### 6.1 Java 后端配置

建议新增：

```yaml
bidding:
  ocr:
    enabled: ${BIDDING_OCR_ENABLED:false}
    base-url: ${BIDDING_OCR_BASE_URL:}
    min-total-chars: ${BIDDING_OCR_MIN_TOTAL_CHARS:50}
    min-page-chars: ${BIDDING_OCR_MIN_PAGE_CHARS:30}
    connect-timeout-ms: ${BIDDING_OCR_CONNECT_TIMEOUT_MS:5000}
    read-timeout-ms: ${BIDDING_OCR_READ_TIMEOUT_MS:120000}
```

说明：

| 配置 | 说明 |
|------|------|
| `enabled` | 是否启用 OCR |
| `base-url` | OCR 服务地址，如 `http://127.0.0.1:8090` |
| `min-total-chars` | PDF 原生全文低于该字符数时触发 OCR |
| `min-page-chars` | PDF 单页低于该字符数时触发 OCR |
| `connect-timeout-ms` | 连接 OCR 服务超时时间 |
| `read-timeout-ms` | OCR 识别读取超时时间 |

### 6.2 线上 `common.env` 建议

```env
# OCR 服务
BIDDING_OCR_ENABLED=true
BIDDING_OCR_BASE_URL=http://127.0.0.1:8090
BIDDING_OCR_MIN_TOTAL_CHARS=50
BIDDING_OCR_MIN_PAGE_CHARS=30
BIDDING_OCR_CONNECT_TIMEOUT_MS=5000
BIDDING_OCR_READ_TIMEOUT_MS=120000
```

### 6.3 OCR 容器建议

建议容器：

| 项 | 建议值 |
|----|--------|
| 容器名 | `bidding-ocr` |
| 端口 | `127.0.0.1:8090:8090` |
| 重启策略 | `unless-stopped` |
| OCR 引擎 | RapidOCR |
| OCR 模型 | PP-OCR ONNX |

示例：

```yaml
ocr:
  build: ./ocr-service
  container_name: bidding-ocr
  ports:
    - "127.0.0.1:8090:8090"
  environment:
    OCR_DPI: "200"
    OCR_MIN_IMAGE_AREA: "8000"
    OCR_MAX_UPLOAD_MB: "200"
  restart: unless-stopped
```

### 6.4 线上启动说明

统一按 `/opt/biaoshu/backend` 目录组织：

- `ocr-service/`：OCR 服务源码与 Docker 构建上下文。
- `config/common.env`：后端公共环境变量。
- `docker-compose.ocr.yml`：OCR 容器编排文件。

推荐启动顺序：

1. 确认 `ocr-service/` 已就位。
2. 在 `config/common.env` 中加入：

```env
BIDDING_OCR_ENABLED=true
BIDDING_OCR_BASE_URL=http://127.0.0.1:8090
BIDDING_OCR_MIN_TOTAL_CHARS=50
BIDDING_OCR_MIN_PAGE_CHARS=30
BIDDING_OCR_CONNECT_TIMEOUT_MS=5000
BIDDING_OCR_READ_TIMEOUT_MS=120000
```

3. 在 `/opt/biaoshu/backend` 下启动 OCR，并统一归入 `bidding-platform` Compose 项目：

```bash
docker compose -p bidding-platform -f docker-compose.ocr.yml up -d --build ocr
```

4. 检查健康状态：

```bash
curl http://127.0.0.1:8090/health
```

5. 重启 `bidding-api` 和 `bidding-worker`，让它们读取新的 OCR 环境变量。

说明：

- OCR 容器只绑定本机回环地址，不对外网暴露。
- 后端服务通过 `BIDDING_OCR_BASE_URL` 访问 OCR。
- 若 OCR 未启动，扫描版 PDF 索引会失败，不做静默降级。

## 7. OCR HTTP 接口建议

### 7.1 健康检查

`GET /health`

返回：

```json
{
  "status": "ok",
  "service": "ocr",
  "version": "2.0.0"
}
```

### 7.2 PDF OCR

`POST /ocr/pdf`

`Content-Type: multipart/form-data`

参数：

| 字段 | 必填 | 说明 |
|------|------|------|
| `file` | 是 | PDF 文件 |
| `force_ocr` | 否 | OCR 服务内部参数；Java 后端本期固定传 `false` |
| `page_nums` | 否 | 指定页码；默认全量 |

返回：

```json
{
  "page_count": 29,
  "duration_ms": 1800,
  "engine": "rapidocr",
  "pages": [
    {
      "page_no": 1,
      "text": "智慧城市综合管理服务平台建设项目\n招标文件\n招标编号：ZHCS-2024-001",
      "confidence": 0.93,
      "source": "ocr",
      "has_images": true
    }
  ]
}
```

### 7.3 图片 OCR

`POST /ocr/image`

本期 Java 侧可先定义 DTO，但不接用户侧业务入口。

## 8. 数据模型建议

### 8.1 本期不新增业务表

本期优先复用现有表：

| 表 | 字段 | 用途 |
|----|------|------|
| `biz_document_chunk` | `page_no` | OCR / 原生抽取后的可信页码 |
| `biz_document_chunk` | `content` | OCR / 原生抽取后的 chunk 内容 |
| `biz_document_vector` | `embedding` | OCR 文本向量化结果 |
| `biz_job` | `status` / `error_message` | INDEX 任务状态和失败提示 |

### 8.2 后续可选增强

如果后续要做 OCR 坐标高亮，再考虑新增：

- OCR 行级结果表。
- OCR 坐标 JSON 字段。
- 文档页级 OCR 状态字段。

本期不做，避免数据模型过早复杂化。

## 9. Pipeline 拆分

### 9.1 `DocumentOcrPort`

职责：

- 定义 OCR 调用能力。
- 屏蔽 HTTP 客户端细节。
- 返回 PDF 页级文本结果。

建议方法：

```java
OcrPdfResult ocrPdf(byte[] pdfBytes, boolean forceOcr);
```

### 9.2 `HttpDocumentOcrPort`

职责：

- 读取 `bidding.ocr` 配置。
- 通过 `RestClient` 调用 OCR 服务。
- 发送 multipart 请求。
- 解析 OCR JSON 响应。
- 记录底层异常日志。
- 对外抛出干净业务异常。

### 9.3 `PdfWordTextExtractPort`

职责：

- PDF 原生文本抽取。
- 判断是否需要 OCR。
- 调用 `DocumentOcrPort`。
- 将 OCR 页文本合并到 `ExtractResult.pages`。
- 组装 `ExtractResult.fullText`。
- 保证 `pageNo` 来自 PDF 页号或 OCR `page_no`。

### 9.4 `IndexJobExecutor`

职责：

- 保持现有索引编排。
- 接收 OCR 增强后的 `ExtractResult`。
- OCR 失败时将 INDEX job 标记失败。
- 失败原因对前端保持业务化表达。

### 9.5 `ChapterChunkingPort`

职责：

- 不直接调用 OCR。
- 继续根据 `ExtractResult.pages` 进行章节 / 标题 / 窗口切块。
- chunk 的 `pageNo` 继续来自所属页。

## 10. 失败处理规则

| 场景 | 处理 |
|------|------|
| OCR 未启用，PDF 原生文本为空 | INDEX 失败，提示“未能从文件抽取到文本，扫描版 PDF 需启用 OCR 服务” |
| OCR 已启用但 `base-url` 未配置 | 启动或调用时失败，提示“OCR 服务地址未配置” |
| OCR 服务连接失败 | INDEX 失败，提示“文档 OCR 识别失败，请稍后重试或检查 OCR 服务” |
| OCR 服务超时 | INDEX 失败，提示“文档 OCR 识别超时，请稍后重试” |
| OCR 返回空 pages | INDEX 失败，提示“文档 OCR 未识别到有效文本” |
| OCR 返回页码异常 | INDEX 失败，提示“文档 OCR 返回页码异常” |

说明：

- 日志中保留真实异常。
- 前端接口不展示底层异常信息。
- 不做静默兜底，避免扫描件被误判为索引成功。

## 11. 权限规则

- OCR 服务不暴露公网，只允许后端服务器本机或内网访问。
- 前端不直接访问 OCR 服务。
- Java 后端仍按现有文档权限控制文件上传、预览和解析结果访问。
- OCR 服务如后续暴露跨机器访问，需要增加内网防火墙或服务鉴权。

## 12. API 文档维护约定

- 本期不新增用户侧 OCR API。
- OCR 属于文件索引内部能力，需要更新 `docs/API-检索重排与LLM.md` 的索引行为说明。
- 如文件上传 / 任务进度返回新增 OCR 状态字段，需要同步更新：
  - `docs/API-文件与标包.md`
  - `docs/API-任务进度SSE.md`
  - `docs/API.md`
- 招标解析和投标模拟审核文档中原“不做 OCR”的说明需要改为“依赖索引阶段 OCR”。

## 13. 开发顺序建议

- [x] 新增 OCR 服务接入功能拆分文档。已完成：`docs/OCR服务接入功能拆分.md`
- [x] 确认 OCR 服务部署方式和端口。已确认：Docker 容器 `bidding-ocr`，端口 `127.0.0.1:8090`；Java / worker 通过 `BIDDING_OCR_BASE_URL=http://127.0.0.1:8090` 调用，不对公网暴露
- [x] 从旧项目迁移或整理 OCR 服务部署材料。已完成：新增 `ocr-service` 源码和 `docker-compose.ocr.yml` 独立部署模板，容器端口仅绑定 `127.0.0.1:8090`
- [x] 新增 `DocumentOcrPort` 和 OCR DTO。已完成：`DocumentOcrPort` 定义 PDF OCR 页级结果，包含 `pageCount`、`durationMs`、`engine`、`pageNo`、`text`、`confidence`、`source`、`hasImages`
- [x] 新增 OCR HTTP 客户端实现。已完成：`HttpDocumentOcrPort` 调用 `POST /ocr/pdf`，multipart 上传 PDF 并转换为 `DocumentOcrPort.OcrPdfResult`
- [x] 新增 `OcrProperties` 和 `bidding.ocr` 配置。已完成：API / Worker 增加默认关闭的 `bidding.ocr.enabled/base-url/connect-timeout-ms/read-timeout-ms`
- [x] 改造 `PdfWordTextExtractPort`，支持 PDF 原生抽取后按条件调用 OCR。已完成：PDFBox 原生抽取后根据全文字符数、单页字符数、大图片页面判断是否调用 `DocumentOcrPort`
- [x] 调整扫描版 PDF 的索引失败提示。已完成：INDEX 空文本默认失败原因改为“未能从文件抽取到文本，扫描版 PDF 需启用 OCR 服务”
- [x] 补充 OCR 单元测试。已完成：覆盖 OCR 未启用扫描件提示、OCR 启用后返回页文本、OCR 页码写入 `ExtractResult.pages`、OCR HTTP 失败干净提示
  - OCR 未启用时扫描件失败。
  - OCR 启用后扫描件返回页文本。
  - OCR 页码写入 `ExtractResult.pages`。
  - OCR 失败时对外提示干净。
- [x] 补充 `IndexJobExecutor` 关键流程测试。已完成：覆盖 OCR 页码从抽取结果进入 chunk 并落库
- [x] 更新 `docs/API-检索重排与LLM.md`。已完成：补充 INDEX 阶段 OCR 能力和 `BIDDING_OCR_*` 配置说明
- [x] 更新 `docs/API-文件与标包.md` 或任务进度文档中涉及索引失败提示的说明。已确认：本期未新增文件上传 / 任务进度字段，仅在检索重排与 LLM 文档记录 INDEX 失败提示
- [x] 更新招标解析、投标模拟审核拆分文档中的 OCR 范围说明。已完成：两处均改为依赖索引阶段 OCR
- [x] 编译和关键测试验证。已完成：`PdfWordTextExtractPortTest`、`HttpDocumentOcrPortTest` 通过，`bidding-api` / `bidding-worker` 编译通过
- [ ] 使用真实扫描 PDF 做本地闭环验证。
- [x] 整理线上 `common.env` 和 Docker 启动说明。已完成：统一目录为 `/opt/biaoshu/backend`，`common.env` 增加 `BIDDING_OCR_*`，OCR 通过 `docker compose -p bidding-platform -f docker-compose.ocr.yml up -d --build ocr` 启动并用 `/health` 校验

## 14. 工作量评估

整体属于中等改造。

- OCR HTTP 客户端：较容易。
- 配置接入：较容易。
- PDF 原生抽取后触发 OCR：中等。
- 页码保持可信：中等，重点是确保所有 evidence 继续从 chunk 表取页码。
- 失败提示清理：较容易。
- Docker 部署：中等，取决于线上部署目录和镜像构建方式。
- 真实文件验证：中等偏上，主要风险是扫描件质量、PDF 混排顺序、OCR 耗时和服务器 CPU / 内存占用。
