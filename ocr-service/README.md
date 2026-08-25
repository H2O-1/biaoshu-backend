# OCR 微服务 v2

基于 **PyMuPDF + RapidOCR（PP-OCR ONNX）** 的 PDF 文本提取服务。

## 能力

- 可复制文本 PDF：原生提取（`source=native`）
- 扫描版 PDF：整页 OCR（`source=ocr`）
- 图文混排 PDF：原生文字 + 图片区域补 OCR（`source=hybrid`）
- 每行 OCR 结果带 **坐标框**（`lines[].box`），供后续 PDF 高亮

## 分流规则（每页）

| 条件 | 处理 |
|------|------|
| `force_ocr=true` | 整页 OCR |
| 无原生文字 | 整页 OCR |
| 有原生文字 + 检测到图片（面积 ≥ `OCR_MIN_IMAGE_AREA`） | 原生 + 补 OCR，合并去重 |
| 纯文字页 | 仅原生提取 |

## 本地运行

```powershell
cd ocr-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8090
```

## Docker

```powershell
docker compose -f docker-compose.ocr.yml up -d --build ocr
curl http://127.0.0.1:8090/health
```

线上建议只绑定本机端口：`127.0.0.1:8090:8090`。Java / worker 通过
`BIDDING_OCR_BASE_URL=http://127.0.0.1:8090` 访问，不对公网暴露 OCR 服务。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 健康检查 |
| POST | `/ocr/pdf` | multipart：`file`、`force_ocr`、`page_nums` |

响应示例：

```json
{
  "page_count": 2,
  "duration_ms": 1800,
  "engine": "rapidocr",
  "pages": [
    {
      "page_no": 1,
      "text": "第一章 总则\n\n[图片OCR]\n营业执照",
      "confidence": 0.93,
      "source": "hybrid",
      "has_images": true,
      "lines": [
        {"text": "营业执照", "confidence": 0.93, "box": [[120, 450], [280, 450], [280, 470], [120, 470]]}
      ]
    }
  ]
}
```

Java 主应用配置 `BIDDING_OCR_ENABLED=true` 后，扫描/混排 PDF 在索引切块阶段自动调用。
