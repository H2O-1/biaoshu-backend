package com.bidding.ai.infrastructure.chunk;

import com.bidding.common.port.ChunkingPort;
import com.bidding.common.port.DocumentTextExtractPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 章节切块：PDF Outline / Word 标题优先 → 标题启发式 → 固定窗口降级；
 * 单章过长再按窗口切分。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.index", name = "mode", havingValue = "real")
public class ChapterChunkingPort implements ChunkingPort {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "^第[零一二三四五六七八九十百千万0-9]+[章节篇部].*"
    );
    private static final Pattern NUMBERED_TITLE_PATTERN = Pattern.compile(
            "^\\d+(?:\\.\\d+)*[.、\\s].+"
    );

    private final int maxChars;
    private final int fallbackWindow;
    private final int fallbackOverlap;

    public ChapterChunkingPort(
            @Value("${bidding.index.chunk.max-chars:2000}") int maxChars,
            @Value("${bidding.index.chunk.fallback-window:500}") int fallbackWindow,
            @Value("${bidding.index.chunk.fallback-overlap:50}") int fallbackOverlap) {
        this.maxChars = maxChars < 100 ? 2000 : maxChars;
        this.fallbackWindow = fallbackWindow < 50 ? 500 : fallbackWindow;
        int overlap = fallbackOverlap;
        if (overlap < 0) {
            overlap = 0;
        }
        if (overlap >= this.fallbackWindow) {
            overlap = this.fallbackWindow / 5;
        }
        this.fallbackOverlap = overlap;
    }

    @Override
    public List<ChunkDraft> chunk(DocumentTextExtractPort.ExtractResult source) {
        List<ChunkDraft> drafts = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return drafts;
        }

        List<Section> sections = splitByOutline(source);
        if (sections.isEmpty()) {
            sections = splitByHeadingHeuristic(source.getFullText());
        }
        if (sections.isEmpty()) {
            Section whole = new Section();
            whole.pageNo = firstPageNo(source);
            whole.title = null;
            whole.body = source.getFullText().trim();
            sections.add(whole);
        }

        int chunkIndex = 0;
        for (int s = 0; s < sections.size(); s++) {
            Section section = sections.get(s);
            String body = section.body;
            if (body == null || body.trim().isEmpty()) {
                continue;
            }
            body = body.trim();
            String prefix = "";
            if (section.title != null && !section.title.isEmpty()) {
                prefix = "【" + section.title + "】\n";
            }
            List<String> parts = splitLongText(body);
            for (int p = 0; p < parts.size(); p++) {
                String content = prefix + parts.get(p);
                // 仅第一章块保留标题前缀，避免重复占额度
                if (p > 0) {
                    content = parts.get(p);
                }
                ChunkDraft draft = new ChunkDraft();
                draft.setChunkIndex(chunkIndex);
                draft.setPageNo(section.pageNo);
                draft.setContent(content);
                draft.setTokenEstimate(Math.max(1, content.length() / 2));
                drafts.add(draft);
                chunkIndex++;
            }
        }
        return drafts;
    }

    private List<Section> splitByOutline(DocumentTextExtractPort.ExtractResult source) {
        List<Section> sections = new ArrayList<>();
        List<DocumentTextExtractPort.OutlineItem> outline = source.getOutline();
        List<DocumentTextExtractPort.PageText> pages = source.getPages();
        if (outline == null || outline.isEmpty() || pages == null || pages.isEmpty()) {
            return sections;
        }

        List<DocumentTextExtractPort.OutlineItem> sorted = new ArrayList<>();
        for (int i = 0; i < outline.size(); i++) {
            DocumentTextExtractPort.OutlineItem item = outline.get(i);
            if (item == null || item.getTitle() == null || item.getTitle().trim().isEmpty()) {
                continue;
            }
            if (item.getPageNo() <= 0) {
                continue;
            }
            sorted.add(item);
        }
        if (sorted.isEmpty()) {
            return sections;
        }
        sorted.sort(Comparator.comparingInt(DocumentTextExtractPort.OutlineItem::getPageNo)
                .thenComparingInt(DocumentTextExtractPort.OutlineItem::getLevel));

        int maxPage = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).getPageNo() > maxPage) {
                maxPage = pages.get(i).getPageNo();
            }
        }

        // 目录前的正文
        int firstOutlinePage = sorted.get(0).getPageNo();
        if (firstOutlinePage > 1) {
            String prelude = joinPages(pages, 1, firstOutlinePage - 1);
            if (!prelude.isEmpty()) {
                Section section = new Section();
                section.pageNo = 1;
                section.title = "文前";
                section.body = prelude;
                sections.add(section);
            }
        }

        for (int i = 0; i < sorted.size(); i++) {
            DocumentTextExtractPort.OutlineItem item = sorted.get(i);
            int start = item.getPageNo();
            int end = maxPage;
            if (i + 1 < sorted.size()) {
                end = sorted.get(i + 1).getPageNo() - 1;
            }
            if (end < start) {
                end = start;
            }
            String body = joinPages(pages, start, end);
            Section section = new Section();
            section.pageNo = start;
            section.title = item.getTitle().trim();
            section.body = body;
            sections.add(section);
        }
        return sections;
    }

    private List<Section> splitByHeadingHeuristic(String fullText) {
        List<Section> sections = new ArrayList<>();
        if (fullText == null || fullText.trim().isEmpty()) {
            return sections;
        }
        String[] lines = fullText.split("\\R");
        StringBuilder current = new StringBuilder();
        String currentTitle = null;
        Integer currentPage = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isHeadingLine(line)) {
                if (current.length() > 0) {
                    Section section = new Section();
                    section.pageNo = currentPage;
                    section.title = currentTitle;
                    section.body = current.toString().trim();
                    sections.add(section);
                    current = new StringBuilder();
                }
                currentTitle = line;
                currentPage = sections.size() + 1;
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (current.length() > 0 || (currentTitle != null && !currentTitle.isEmpty())) {
            Section section = new Section();
            section.pageNo = currentPage;
            section.title = currentTitle;
            section.body = current.toString().trim();
            if (section.body.isEmpty() && currentTitle != null) {
                section.body = currentTitle;
            }
            sections.add(section);
        }
        return sections;
    }

    private boolean isHeadingLine(String line) {
        if (line.length() > 40) {
            return false;
        }
        if (CHAPTER_PATTERN.matcher(line).matches()) {
            return true;
        }
        return NUMBERED_TITLE_PATTERN.matcher(line).matches();
    }

    private List<String> splitLongText(String text) {
        List<String> parts = new ArrayList<>();
        if (text.length() <= maxChars) {
            parts.add(text);
            return parts;
        }
        int start = 0;
        while (start < text.length()) {
            int end = start + fallbackWindow;
            if (end > text.length()) {
                end = text.length();
            }
            parts.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            start = end - fallbackOverlap;
            if (start < 0) {
                start = 0;
            }
            if (start >= end) {
                start = end;
            }
        }
        return parts;
    }

    private String joinPages(List<DocumentTextExtractPort.PageText> pages, int startPage, int endPage) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            DocumentTextExtractPort.PageText page = pages.get(i);
            if (page.getPageNo() < startPage || page.getPageNo() > endPage) {
                continue;
            }
            String text = page.getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(text.trim());
        }
        return sb.toString();
    }

    private Integer firstPageNo(DocumentTextExtractPort.ExtractResult source) {
        if (source.getPages() != null && !source.getPages().isEmpty()) {
            return source.getPages().get(0).getPageNo();
        }
        return 1;
    }

    private static class Section {
        private Integer pageNo;
        private String title;
        private String body;
    }
}
