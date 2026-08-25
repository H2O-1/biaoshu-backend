"""招投标平台 OCR 微服务 v2：PyMuPDF + RapidOCR，支持图文混排与坐标返回。"""

from __future__ import annotations

import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import FastAPI, File, Form, HTTPException, UploadFile

from models import OcrImageResponse, OcrLineResult, OcrPageResult, OcrPdfResponse
from pdf_extractor import PdfTextExtractor

logger = logging.getLogger("ocr-service")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

DEFAULT_DPI = int(os.getenv("OCR_DPI", "200"))
MIN_IMAGE_AREA = int(os.getenv("OCR_MIN_IMAGE_AREA", "8000"))
MAX_UPLOAD_MB = int(os.getenv("OCR_MAX_UPLOAD_MB", "200"))

_extractor: PdfTextExtractor | None = None


def get_extractor() -> PdfTextExtractor:
    if _extractor is None:
        raise RuntimeError("OCR engine not initialized")
    return _extractor


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _extractor
    logger.info("Initializing PdfTextExtractor dpi=%s min_image_area=%s", DEFAULT_DPI, MIN_IMAGE_AREA)
    _extractor = PdfTextExtractor(dpi=DEFAULT_DPI, min_image_area=MIN_IMAGE_AREA)
    logger.info("OCR engine ready")
    yield
    _extractor = None


app = FastAPI(title="Bidding OCR Service", version="2.0.0", lifespan=lifespan)


def _parse_page_nums(raw: str | None) -> list[int] | None:
    if not raw or not raw.strip():
        return None
    try:
        return [int(x.strip()) for x in raw.split(",") if x.strip()]
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="page_nums 须为逗号分隔整数（0 起页码）") from exc


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "ocr", "version": "2.0.0"}


@app.post("/ocr/pdf", response_model=OcrPdfResponse)
async def ocr_pdf(
    file: Annotated[UploadFile, File(description="PDF 文件")],
    force_ocr: Annotated[bool, Form()] = False,
    page_nums: Annotated[str | None, Form()] = None,
) -> OcrPdfResponse:
    if not file.filename or not file.filename.lower().endswith(".pdf"):
        content_type = (file.content_type or "").lower()
        if "pdf" not in content_type:
            raise HTTPException(status_code=400, detail="仅支持 PDF 文件")

    data = await file.read()
    max_bytes = MAX_UPLOAD_MB * 1024 * 1024
    if len(data) > max_bytes:
        raise HTTPException(status_code=413, detail=f"文件超过 {MAX_UPLOAD_MB}MB 限制")
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")

    page_num_list = _parse_page_nums(page_nums)
    started = time.perf_counter()

    try:
        raw_pages = get_extractor().extract(data, force_ocr=force_ocr, page_nums=page_num_list)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("OCR failed")
        raise HTTPException(status_code=500, detail=f"OCR 处理失败: {exc}") from exc

    pages = [
        OcrPageResult(
            page_no=page.page_no,
            text=page.text,
            confidence=page.confidence,
            source=page.source,
            has_images=page.has_images,
            lines=[
                OcrLineResult(text=line["text"], confidence=line.get("confidence"), box=line.get("box", []))
                for line in page.lines
            ],
        )
        for page in raw_pages
    ]
    page_count = max((page.page_no for page in pages), default=0)
    duration_ms = int((time.perf_counter() - started) * 1000)

    logger.info(
        "OCR done file=%s pages=%s force_ocr=%s duration_ms=%s",
        file.filename,
        len(pages),
        force_ocr,
        duration_ms,
    )
    return OcrPdfResponse(page_count=page_count, duration_ms=duration_ms, pages=pages)


@app.post("/ocr/image", response_model=OcrImageResponse)
async def ocr_image(
    file: Annotated[UploadFile, File(description="图片文件（png/jpg/jpeg/bmp 等）")],
) -> OcrImageResponse:
    data = await file.read()
    max_bytes = MAX_UPLOAD_MB * 1024 * 1024
    if len(data) > max_bytes:
        raise HTTPException(status_code=413, detail=f"文件超过 {MAX_UPLOAD_MB}MB 限制")
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")

    started = time.perf_counter()
    try:
        text, confidence, raw_lines = get_extractor().ocr_image_bytes(data)
    except Exception as exc:
        logger.exception("Image OCR failed")
        raise HTTPException(status_code=500, detail=f"图片 OCR 失败: {exc}") from exc

    duration_ms = int((time.perf_counter() - started) * 1000)
    lines = [
        OcrLineResult(text=line["text"], confidence=line.get("confidence"), box=line.get("box", []))
        for line in raw_lines
    ]
    logger.info("Image OCR done file=%s chars=%s duration_ms=%s", file.filename, len(text), duration_ms)
    return OcrImageResponse(text=text, confidence=confidence, duration_ms=duration_ms, lines=lines)
