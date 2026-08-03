package com.bidding.ai.infrastructure.chunk;

import com.bidding.common.port.ChunkingPort;
import com.bidding.common.port.DocumentTextExtractPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChapterChunkingPortTest {

    @Test
    void splitByOutlinePages() {
        ChapterChunkingPort port = new ChapterChunkingPort(2000, 500, 50);

        DocumentTextExtractPort.ExtractResult source = new DocumentTextExtractPort.ExtractResult();
        source.setFullText("p1\np2\np3");

        List<DocumentTextExtractPort.PageText> pages = new ArrayList<>();
        pages.add(page(1, "封面内容"));
        pages.add(page(2, "第一章 资格要求细则"));
        pages.add(page(3, "第二章 工期条款细则"));
        source.setPages(pages);

        List<DocumentTextExtractPort.OutlineItem> outline = new ArrayList<>();
        outline.add(outlineItem("第一章 资格", 2, 1));
        outline.add(outlineItem("第二章 工期", 3, 1));
        source.setOutline(outline);

        List<ChunkingPort.ChunkDraft> drafts = port.chunk(source);
        assertFalse(drafts.isEmpty());
        assertTrue(drafts.get(0).getContent().contains("文前")
                || drafts.get(0).getContent().contains("封面"));
        boolean hasChapter1 = false;
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).getContent().contains("资格")) {
                hasChapter1 = true;
            }
        }
        assertTrue(hasChapter1);
    }

    @Test
    void fallbackWindowWhenNoOutline() {
        // fallbackWindow 构造时 <50 会被钳成 500；这里用合法窗口，确保长文切出多块
        ChapterChunkingPort port = new ChapterChunkingPort(100, 50, 5);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            longText.append("招标要求正文内容").append(i).append('。');
        }
        DocumentTextExtractPort.ExtractResult source = new DocumentTextExtractPort.ExtractResult();
        source.setFullText(longText.toString());
        List<DocumentTextExtractPort.PageText> pages = new ArrayList<>();
        pages.add(page(1, longText.toString()));
        source.setPages(pages);

        List<ChunkingPort.ChunkDraft> drafts = port.chunk(source);
        assertTrue(drafts.size() >= 2);
        assertEquals(0, drafts.get(0).getChunkIndex());
    }

    @Test
    void heuristicChapterTitle() {
        ChapterChunkingPort port = new ChapterChunkingPort(2000, 500, 50);
        DocumentTextExtractPort.ExtractResult source = new DocumentTextExtractPort.ExtractResult();
        source.setFullText("第一章 总则\n本项目要求合格投标人。\n第二章 工期\n交货期 90 天。");
        List<ChunkingPort.ChunkDraft> drafts = port.chunk(source);
        assertTrue(drafts.size() >= 2);
        assertTrue(drafts.get(0).getContent().contains("总则")
                || drafts.get(0).getContent().contains("合格"));
    }

    private static DocumentTextExtractPort.PageText page(int no, String text) {
        DocumentTextExtractPort.PageText p = new DocumentTextExtractPort.PageText();
        p.setPageNo(no);
        p.setText(text);
        return p;
    }

    private static DocumentTextExtractPort.OutlineItem outlineItem(String title, int page, int level) {
        DocumentTextExtractPort.OutlineItem item = new DocumentTextExtractPort.OutlineItem();
        item.setTitle(title);
        item.setPageNo(page);
        item.setLevel(level);
        return item;
    }
}
