"""PDF 文本提取：原生文字 + 扫描页整页 OCR + 混排页按图 OCR。"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Iterable

import cv2
import fitz
import numpy as np
from rapidocr_onnxruntime import RapidOCR

logger = logging.getLogger(__name__)

MIN_PAGE_CHARS = 30
MIN_TOTAL_CHARS = 50
MIN_IMAGE_PAGE_RATIO = 0.03
MIN_IMAGE_DIMENSION = 80
REGIONAL_OCR_DPI = 300
# 「上文字 + 下大图」版式：文字块很稀疏，且下方有大块非文字区域（避免普通页误触发）
MAX_TEXT_COVERAGE_FOR_LAYOUT = 0.18
MIN_BELOW_TEXT_AREA_RATIO = 0.42
LAYOUT_FULLPAGE_BELOW_RATIO = 0.55
MAX_NATIVE_CHARS_FOR_LAYOUT = 500


@dataclass
class PageExtractResult:
    page_no: int
    text: str
    confidence: float | None
    source: str
    has_images: bool
    lines: list[dict]


class PdfTextExtractor:
    def __init__(self, dpi: int = 200, min_image_area: int = 8_000):
        self.dpi = dpi
        self.min_image_area = min_image_area
        self._ocr = RapidOCR()

    def extract(
        self,
        pdf_bytes: bytes,
        *,
        force_ocr: bool = False,
        page_nums: list[int] | None = None,
    ) -> list[PageExtractResult]:
        results: list[PageExtractResult] = []
        with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
            indexes = self._resolve_page_indexes(page_nums, doc.page_count)
            force_all = force_ocr or self._should_force_full_document_ocr(doc, indexes)
            if force_all and not force_ocr:
                logger.info("PDF 原生总字符 < %s，全部页面强制整页 OCR", MIN_TOTAL_CHARS)

            for page_idx in indexes:
                page = doc[page_idx]
                results.append(self._extract_page(doc, page, page_idx, force_all))
        return results

    def _should_force_full_document_ocr(self, doc: fitz.Document, indexes: list[int]) -> bool:
        total = 0
        for page_idx in indexes:
            page = doc[page_idx]
            native_text = self._normalize(page.get_text("text", sort=True))
            total += self._count_chars(native_text)
        return total < MIN_TOTAL_CHARS

    def _extract_page(
        self,
        doc: fitz.Document,
        page: fitz.Page,
        page_idx: int,
        force_all: bool,
    ) -> PageExtractResult:
        page_no = page_idx + 1
        native_text = self._normalize(page.get_text("text", sort=True))
        native_chars = self._count_chars(native_text)
        raw_images = page.get_images(full=True)
        image_placements = self._collect_page_image_placements(doc, page)
        has_embedded_images = len(image_placements) > 0 or len(raw_images) > 0
        suspected_layout = self._suspected_text_image_layout(page, native_chars)

        if force_all or native_chars < MIN_PAGE_CHARS:
            ocr_lines = self._ocr_full_page(page)
            return self._build_result(
                page_no, native_text if not force_all else "", ocr_lines, "ocr", has_embedded_images
            )

        if not has_embedded_images and not suspected_layout:
            return PageExtractResult(
                page_no=page_no,
                text=native_text,
                confidence=None,
                source="native",
                has_images=False,
                lines=[],
            )

        ocr_lines: list[dict] = []
        if has_embedded_images:
            if not image_placements and raw_images:
                logger.warning(
                    "page=%s 有 %s 个 xref 但无有效 placement，尝试直接 OCR xref",
                    page_no,
                    len(raw_images),
                )
                ocr_lines = self._ocr_raw_xrefs(doc, raw_images)
            else:
                ocr_lines = self._ocr_page_images(doc, page, image_placements)
            if not ocr_lines:
                logger.warning(
                    "page=%s 嵌入图 OCR 无结果（placements=%s），回退整页 diff OCR",
                    page_no,
                    len(image_placements),
                )
                ocr_lines = self._ocr_full_page(page)
        else:
            below_ratio = self._below_text_area_ratio(page)
            text_coverage = self._page_text_coverage(page)
            logger.info(
                "page=%s 版式 OCR：coverage=%.2f below=%.2f",
                page_no,
                text_coverage,
                below_ratio,
            )
            ocr_lines = self._ocr_below_text_blocks(page)
            if not ocr_lines and below_ratio >= LAYOUT_FULLPAGE_BELOW_RATIO:
                logger.info("page=%s 版式裁剪 OCR 无结果，回退整页 diff OCR", page_no)
                ocr_lines = self._ocr_full_page(page)

        if not ocr_lines:
            return PageExtractResult(
                page_no=page_no,
                text=native_text,
                confidence=None,
                source="native",
                has_images=has_embedded_images,
                lines=[],
            )

        merged = self._merge_native_and_ocr(native_text, ocr_lines)
        has_ocr_snippet = "[图片OCR]" in merged
        source = "hybrid" if has_ocr_snippet else "native"
        if has_ocr_snippet:
            logger.info(
                "page=%s hybrid OCR 成功，追加字符=%s",
                page_no,
                self._count_chars(merged) - native_chars,
            )
        return PageExtractResult(
            page_no=page_no,
            text=merged,
            confidence=self._avg_confidence(ocr_lines),
            source=source,
            has_images=has_embedded_images or suspected_layout,
            lines=ocr_lines,
        )

    def _suspected_text_image_layout(self, page: fitz.Page, native_chars: int) -> bool:
        if native_chars < MIN_PAGE_CHARS or native_chars > MAX_NATIVE_CHARS_FOR_LAYOUT:
            return False
        text_coverage = self._page_text_coverage(page)
        below_ratio = self._below_text_area_ratio(page)
        return (
            text_coverage < MAX_TEXT_COVERAGE_FOR_LAYOUT
            and below_ratio >= MIN_BELOW_TEXT_AREA_RATIO
        )

    def _below_text_area_ratio(self, page: fitz.Page) -> float:
        page_rect = page.rect
        page_area = max(page_rect.width * page_rect.height, 1.0)
        text_bottoms: list[float] = []
        for block in page.get_text("dict", sort=True).get("blocks", []):
            if block.get("type") != 0:
                continue
            text_bottoms.append(float(block["bbox"][3]))
        if not text_bottoms:
            return 0.0
        last_bottom = max(text_bottoms)
        if last_bottom >= page_rect.y1 - 20:
            return 0.0
        clip = fitz.Rect(page_rect.x0, last_bottom + 4, page_rect.x1, page_rect.y1)
        clip.intersect(page_rect)
        if clip.is_empty:
            return 0.0
        return (clip.width * clip.height) / page_area

    def _page_text_coverage(self, page: fitz.Page) -> float:
        page_rect = page.rect
        page_area = max(page_rect.width * page_rect.height, 1.0)
        covered = 0.0
        for block in page.get_text("dict", sort=True).get("blocks", []):
            if block.get("type") != 0:
                continue
            x0, y0, x1, y1 = block.get("bbox", (0, 0, 0, 0))
            covered += max(0.0, x1 - x0) * max(0.0, y1 - y0)
        return min(1.0, covered / page_area)

    def _ocr_below_text_blocks(self, page: fitz.Page) -> list[dict]:
        page_rect = page.rect
        text_bottoms: list[float] = []
        for block in page.get_text("dict", sort=True).get("blocks", []):
            if block.get("type") != 0:
                continue
            text_bottoms.append(float(block["bbox"][3]))
        if not text_bottoms:
            return []
        last_bottom = max(text_bottoms)
        if last_bottom >= page_rect.y1 - 20:
            return []
        clip = fitz.Rect(page_rect.x0, last_bottom + 4, page_rect.x1, page_rect.y1)
        clip.intersect(page_rect)
        if clip.is_empty:
            return []
        clip_ratio = self._below_text_area_ratio(page)
        if clip_ratio < MIN_BELOW_TEXT_AREA_RATIO:
            return []
        logger.info(
            "page=%s OCR clip below text, area_ratio=%.2f",
            page.number + 1,
            clip_ratio,
        )
        return self._ocr_page_rect(page, clip, dpi=REGIONAL_OCR_DPI)

    def _collect_page_image_placements(self, doc: fitz.Document, page: fitz.Page) -> list[dict]:
        page_area = max(page.rect.width * page.rect.height, 1.0)
        placements: list[dict] = []
        seen_xrefs: set[int] = set()

        for img in page.get_images(full=True):
            xref = img[0]
            if xref in seen_xrefs:
                continue
            seen_xrefs.add(xref)

            rects: list[fitz.Rect] = []
            try:
                rects = list(page.get_image_rects(xref) or [])
            except Exception:
                rects = []

            if rects:
                for rect in rects:
                    placement = self._build_placement(xref, rect, page_area)
                    if placement is not None:
                        placements.append(placement)
                continue

            meta = self._image_meta(doc, xref)
            if meta is None:
                continue
            width, height, _ext = meta
            area = width * height
            if area < self.min_image_area:
                continue
            if width < MIN_IMAGE_DIMENSION and height < MIN_IMAGE_DIMENSION:
                continue
            placements.append({"xref": xref, "rect": None, "area": float(area)})

        placements.sort(key=lambda item: item["area"], reverse=True)
        return placements

    def _build_placement(self, xref: int, rect: fitz.Rect, page_area: float) -> dict | None:
        if rect.is_empty or rect.width <= 0 or rect.height <= 0:
            return None
        area = rect.width * rect.height
        if area < self.min_image_area:
            return None
        if area / page_area < MIN_IMAGE_PAGE_RATIO:
            return None
        if rect.width < MIN_IMAGE_DIMENSION and rect.height < MIN_IMAGE_DIMENSION:
            return None
        return {"xref": xref, "rect": rect, "area": area}

    def _image_meta(self, doc: fitz.Document, xref: int) -> tuple[int, int, str] | None:
        try:
            base = doc.extract_image(xref)
            width = int(base.get("width") or 0)
            height = int(base.get("height") or 0)
            ext = str(base.get("ext") or "")
            if width <= 0 or height <= 0:
                return None
            return width, height, ext
        except Exception:
            return None

    def _ocr_raw_xrefs(self, doc: fitz.Document, raw_images: list) -> list[dict]:
        lines: list[dict] = []
        seen_text: set[str] = set()
        seen_xrefs: set[int] = set()
        for img in raw_images:
            xref = img[0]
            if xref in seen_xrefs:
                continue
            seen_xrefs.add(xref)
            meta = self._image_meta(doc, xref)
            if meta is not None:
                width, height, _ext = meta
                if width * height < self.min_image_area:
                    continue
            for line in self._ocr_image_xref(doc, xref):
                text = self._normalize(line.get("text", ""))
                if not text or text in seen_text:
                    continue
                seen_text.add(text)
                lines.append(line)
        return lines

    def _ocr_page_images(
        self,
        doc: fitz.Document,
        page: fitz.Page,
        placements: list[dict],
    ) -> list[dict]:
        lines: list[dict] = []
        seen_text: set[str] = set()
        for placement in placements:
            xref = placement["xref"]
            rect = placement.get("rect")
            image_lines: list[dict] = []
            if rect is not None:
                image_lines = self._ocr_page_rect(page, rect, dpi=REGIONAL_OCR_DPI)
            if not image_lines:
                image_lines = self._ocr_image_xref(doc, xref)
            if not image_lines and rect is not None:
                image_lines = self._ocr_page_rect(page, rect, dpi=self.dpi)
            for line in image_lines:
                text = self._normalize(line.get("text", ""))
                if not text or text in seen_text:
                    continue
                seen_text.add(text)
                lines.append(line)
        return lines

    def _ocr_image_xref(self, doc: fitz.Document, xref: int) -> list[dict]:
        lines = self._ocr_xref_pixmap(doc, xref)
        if lines:
            return lines
        try:
            base = doc.extract_image(xref)
            image_bytes = base.get("image")
            if not image_bytes:
                return []
            image_arr = np.frombuffer(image_bytes, dtype=np.uint8)
            image = cv2.imdecode(image_arr, cv2.IMREAD_COLOR)
            if image is None:
                return []
            return self._ocr_ndarray(image)
        except Exception:
            logger.debug("extract_image OCR failed xref=%s", xref, exc_info=True)
            return []

    def _ocr_xref_pixmap(self, doc: fitz.Document, xref: int) -> list[dict]:
        try:
            pix = fitz.Pixmap(doc, xref)
            if pix.n >= 5:
                pix = fitz.Pixmap(fitz.csRGB, pix)
            elif pix.n == 4:
                pix = fitz.Pixmap(fitz.csRGB, pix)
            image = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w, pix.n)
            if pix.n == 1:
                image = cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
            return self._ocr_ndarray(image)
        except Exception:
            logger.debug("xref pixmap OCR failed xref=%s", xref, exc_info=True)
            return []

    def _ocr_page_rect(self, page: fitz.Page, rect: fitz.Rect, *, dpi: int | None = None) -> list[dict]:
        try:
            clip = fitz.Rect(rect)
            clip.intersect(page.rect)
            if clip.is_empty:
                return []
            render_dpi = dpi or self.dpi
            pix = page.get_pixmap(clip=clip, dpi=render_dpi)
            image = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w, pix.n)
            if pix.n == 4:
                image = cv2.cvtColor(image, cv2.COLOR_RGBA2RGB)
            elif pix.n == 1:
                image = cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
            else:
                image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            return self._ocr_ndarray(image)
        except Exception:
            logger.debug("rect OCR failed page=%s", page.number + 1, exc_info=True)
            return []

    def _ocr_full_page(self, page: fitz.Page) -> list[dict]:
        image = self._render_page(page)
        if image is None:
            return []
        return self._ocr_ndarray(image)

    def _ocr_ndarray(self, image: np.ndarray) -> list[dict]:
        raw = self._ocr(image)
        if raw is None:
            return []
        ocr_res, _elapsed = raw
        if not ocr_res:
            return []

        lines: list[dict] = []
        for item in ocr_res:
            if not item or len(item) < 2:
                continue
            box = item[0] if isinstance(item[0], list) else []
            text = str(item[1]).strip() if item[1] is not None else ""
            if not text:
                continue
            score = float(item[2]) if len(item) > 2 and item[2] is not None else None
            lines.append({"text": text, "confidence": score, "box": box})
        return lines

    def _render_page(self, page: fitz.Page) -> np.ndarray | None:
        pix = page.get_pixmap(dpi=self.dpi)
        image = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w, pix.n)
        if pix.n == 4:
            image = cv2.cvtColor(image, cv2.COLOR_RGBA2RGB)
        elif pix.n == 1:
            image = cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
        else:
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        return image

    def _build_result(
        self,
        page_no: int,
        native_text: str,
        ocr_lines: list[dict],
        source: str,
        has_images: bool,
    ) -> PageExtractResult:
        ocr_text = "\n".join(line["text"] for line in ocr_lines if line.get("text"))
        text = native_text if native_text else self._normalize(ocr_text)
        if native_text and ocr_lines:
            text = self._merge_native_and_ocr(native_text, ocr_lines)
        confidence = self._avg_confidence(ocr_lines) if ocr_lines else None
        return PageExtractResult(
            page_no=page_no,
            text=text,
            confidence=confidence,
            source=source,
            has_images=has_images,
            lines=ocr_lines,
        )

    def _merge_native_and_ocr(self, native_text: str, ocr_lines: Iterable[dict]) -> str:
        native_lines = {
            line.strip()
            for line in native_text.split("\n")
            if line and line.strip()
        }
        native_compact = re.sub(r"\s+", "", native_text)
        extras: list[str] = []
        for line in ocr_lines:
            text = self._normalize(line.get("text", ""))
            if not text:
                continue
            compact = re.sub(r"\s+", "", text)
            if len(compact) < 4 and compact in native_compact:
                continue
            if text in native_lines:
                continue
            if any(text == item for item in extras):
                continue
            extras.append(text)
        if not extras:
            return self._normalize(native_text)
        return self._normalize(native_text) + "\n\n[图片OCR]\n" + "\n".join(extras)

    @staticmethod
    def _count_chars(text: str) -> int:
        return len(re.sub(r"\s+", "", text or ""))

    @staticmethod
    def _avg_confidence(lines: list[dict]) -> float | None:
        scores = [line["confidence"] for line in lines if line.get("confidence") is not None]
        if not scores:
            return None
        return round(sum(scores) / len(scores), 4)

    def ocr_image_bytes(self, image_bytes: bytes) -> tuple[str, float | None, list[dict]]:
        if not image_bytes:
            return "", None, []
        image_arr = np.frombuffer(image_bytes, dtype=np.uint8)
        image = cv2.imdecode(image_arr, cv2.IMREAD_COLOR)
        if image is None:
            return "", None, []
        lines = self._ocr_ndarray(image)
        if not lines:
            return "", None, []
        text = "\n".join(line["text"] for line in lines if line.get("text"))
        return self._normalize(text), self._avg_confidence(lines), lines

    @staticmethod
    def _normalize(text: str | None) -> str:
        if not text:
            return ""
        return text.replace("\r\n", "\n").strip()

    @staticmethod
    def _resolve_page_indexes(page_nums: list[int] | None, page_count: int) -> list[int]:
        if page_nums is None:
            return list(range(page_count))
        indexes: set[int] = set()
        for page_num in page_nums:
            if page_num < 0:
                indexes.add(page_count + page_num)
            else:
                indexes.add(page_num)
        valid = sorted(idx for idx in indexes if 0 <= idx < page_count)
        if not valid:
            raise ValueError("page_nums 无有效页码")
        return valid
