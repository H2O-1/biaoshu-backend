package com.bidding.ai.infrastructure.fake;

import com.bidding.common.port.ChunkingPort;
import com.bidding.common.port.DocumentTextExtractPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake 切块：按固定块数生成占位正文。
 * 仅在 bidding.index.mode=fake（默认）时启用。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.index", name = "mode", havingValue = "fake", matchIfMissing = true)
public class FakeChunkingPort implements ChunkingPort {

    private final int chunkCount;

    public FakeChunkingPort(
            @Value("${bidding.index.fake-chunk-count:3}") int chunkCount) {
        if (chunkCount < 1) {
            this.chunkCount = 1;
        } else {
            this.chunkCount = chunkCount;
        }
    }

    @Override
    public List<ChunkDraft> chunk(DocumentTextExtractPort.ExtractResult source) {
        String base = "empty";
        if (source != null && source.getFullText() != null && !source.getFullText().trim().isEmpty()) {
            base = source.getFullText().trim();
        }
        return chunkPlain(base);
    }

    private List<ChunkDraft> chunkPlain(String base) {
        List<ChunkDraft> drafts = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            ChunkDraft draft = new ChunkDraft();
            draft.setChunkIndex(i);
            draft.setPageNo(i + 1);
            String content = "[FAKE_CHUNK " + i + "] " + base;
            draft.setContent(content);
            draft.setTokenEstimate(Math.max(1, content.length() / 2));
            drafts.add(draft);
        }
        return drafts;
    }
}
