"""OCR API 数据模型。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class OcrLineResult(BaseModel):
    text: str
    confidence: float | None = None
    box: list[list[float]] = Field(default_factory=list, description="四点坐标 [[x,y], ...]")


class OcrPageResult(BaseModel):
    page_no: int = Field(..., description="页码，从 1 开始")
    text: str
    confidence: float | None = Field(None, description="OCR 平均置信度；纯原生页为 null")
    source: str = Field(..., description="native | ocr | hybrid")
    has_images: bool = False
    lines: list[OcrLineResult] = Field(default_factory=list)


class OcrPdfResponse(BaseModel):
    page_count: int
    duration_ms: int
    engine: str = "rapidocr"
    pages: list[OcrPageResult]


class OcrImageResponse(BaseModel):
    text: str
    confidence: float | None = None
    duration_ms: int
    engine: str = "rapidocr"
    lines: list[OcrLineResult] = Field(default_factory=list)
