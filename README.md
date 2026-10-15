# 招投标智能评审系统 · 后端

用 AI 把招标文件解析成结构化评审清单，再逐条比对投标标书，自动判定符合性并给出**原文证据**，辅助评标专家完成初审。

> 依据《关于加快招标投标领域人工智能推广应用的实施意见》（发改法规〔2026〕195号），
> 本系统定位为**辅助评审工具**，AI 结论不替代招标人、投标人及评标专家的自主判断。

---

## 核心功能

| 模块 | 能力 |
|---|---|
| 项目管理 | 项目、标包（招标包 / 投标包）、投标报价与税率 |
| 文件管理 | PDF / Word 上传、预览、下载、跨项目复制、对象存储 |
| 文档索引 | 章节切块 + 向量化，扫描件自动走 OCR |
| 招标解析 | 生成评审清单（资格项 / 评分项），检测错别字与逻辑错误 |
| 智能评审 | 逐条比对标书，输出符合性结论 + 引用原文证据 |
| 投标模拟审核 | 投标方视角预演审核，提前发现响应性风险 |
| 专家评审 | 任务分配、成员管理、附件留痕、否决与复核 |
| 一键审查 | 索引 → 建清单 → 审查 全流程串联，支持断点续跑 |
| 平台管理 | 组织、账号、角色、城市字典、评审维度模板 |

---

## 技术亮点

### 1. 混合检索 RAG

招投标文本专业性强、术语密集，单靠向量召回不够，做了多路融合：

```
章节切块 → 向量召回 ∥ BM25 词汇召回 → RRF 融合 → 重排 → LLM
```

- **RRF（Reciprocal Rank Fusion）** —— 向量分与 BM25 分数量级不可比，无法直接加权。
  改按**名次倒数** `1/(k+rank+1)` 融合，绕开分数归一化问题。
- **BM25** —— 自实现 Okapi BM25 + 中文分词（单字 + 相邻双字）。
- **重排** —— qwen3-rerank 交叉编码器对融合后的候选精排，再送 LLM。

### 2. 持久化任务状态机 + 租约

一键审查可能跑几十分钟，不能依赖内存队列：

- 任务状态落 PostgreSQL（`phase` / `next_run_at` / `lease_expires_at`）
- 抢占用 `SELECT ... FOR UPDATE SKIP LOCKED` + **租约过期接管**，多实例部署安全
- UPDATE 语句内做**二次条件校验**，防 READ COMMITTED 隔离级别下的双认领竞态
- 独立 Worker 进程 + Poller 轮询（`fixedDelay` 而非 `fixedRate`，不忙等空转）
- 长任务进度通过 **SSE** 推送（`text/event-stream`）

### 3. 结构化评审清单 + 证据链

不直接问 AI「这家行不行」，而是先把招标文件拆成结构化产物：

```
A 资格项 ∥ B 评分项 → C bidSearch（检索标书对应段落）→ D evidence（抽原文）
```

每条结论**强制引用标书原文**作为证据。这既是防 LLM 幻觉的关键手段，
也是评标专家敢采信 AI 结论的前提。

### 4. OCR 分流策略

扫描件、图文混排在招投标材料里很常见，按页智能分流，避免无脑整页 OCR 拖慢速度：

| 条件 | 处理 |
|---|---|
| 强制 OCR | 整页 OCR |
| 无原生文字 | 整页 OCR |
| 有原生文字 + 检测到图片 | 原生提取 + 图片区域补 OCR，合并去重 |
| 纯文字页 | 仅原生提取 |

OCR 用 PyMuPDF + RapidOCR（PP-OCR ONNX），每行结果带**坐标框**，供 PDF 高亮定位。
线上仅绑定本机端口，不对外暴露。

### 5. 模块物理隔离

8 个 Maven 模块，各业务模块的 `pom.xml` **只依赖 `bidding-common`**，
跨模块调用必须走 `common/port` 下的接口。

这不是靠文档约定，而是**构建层面的硬约束** —— 循环依赖和分层倒挂在编译期就不可能发生。

### 6. 会话吊销

JWT 是无状态的，登出/改密后旧 token 无法自然失效。
用 `tokenVersion` 实现服务端吊销，且**每个请求从库重载最新角色**，权限变更即时生效。

---

## 架构

```
bidding-common      统一响应、错误码、安全上下文、跨模块 Port 接口、DB 迁移
bidding-identity    组织 / 账号 / 角色 / 平台管理
bidding-project     项目与标包
bidding-file        文档元数据与对象存储
bidding-review      索引、清单、召回重排、评审、任务调度（核心）
bidding-ai          AI 能力：切块、向量、LLM、重排、OCR 客户端
bidding-api         HTTP 入口（组装根）
bidding-worker      独立异步任务进程（组装根）
ocr-service         OCR 微服务（Python）
```

模块内分层：`adapter` / `application` / `domain` / `infrastructure`

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / 框架 | Java 17、Spring Boot 3.2.5、Maven 多模块 |
| 数据 | PostgreSQL + Flyway（41 个版本化迁移） |
| 认证 | Spring Security + JWT（jjwt） |
| 存储 | MinIO / S3 |
| 大模型 | 阿里云百炼：`text-embedding-v4`（向量）、`qwen3-rerank`（重排）、`qwen3.7-plus`（抽取与对照） |
| OCR | Python 3 + FastAPI + PyMuPDF + RapidOCR |
| 前端 | 独立工程（不在此仓库） |

---

## 快速开始

### 依赖

JDK 17+、Maven 3.9+、PostgreSQL 14+；（可选）MinIO、Python 3.10+

### 配置

全部配置支持环境变量覆盖，关键项：

```bash
DB_URL=jdbc:postgresql://localhost:5432/bidding
DB_USER=bidding
DB_PASSWORD=<your-password>
JWT_SECRET=<至少 32 字节>
BOOTSTRAP_ADMIN_PASSWORD=<初始管理员密码>
DASHSCOPE_API_KEY=<阿里云百炼 API Key>
```

本地开发也可写入 `bidding-api/src/main/resources/application-secret.yml`
（该文件已在 `.gitignore` 中，不会被提交）。

### 启动

```bash
# 编译
mvn -pl bidding-api -am package -DskipTests

# 启动 API（Flyway 自动建表）
java -jar bidding-api/target/bidding-api-1.0.0-SNAPSHOT.jar

# 启动 Worker（异步任务执行）
java -jar bidding-worker/target/bidding-worker-1.0.0-SNAPSHOT.jar
```

默认管理员账号 `admin`，密码由 `BOOTSTRAP_ADMIN_PASSWORD` 指定，首次登录强制改密。

### OCR 服务（可选）

```bash
docker compose -f docker-compose.ocr.yml up -d --build ocr
```

Java 侧设 `BIDDING_OCR_ENABLED=true`、`BIDDING_OCR_BASE_URL=http://127.0.0.1:8090` 后生效。

---

## 文档

`docs/` 目录下有完整的功能拆分与 API 文档。
