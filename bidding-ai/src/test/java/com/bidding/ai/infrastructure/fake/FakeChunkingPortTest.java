package com.bidding.ai.infrastructure.fake;

import com.bidding.common.port.ChunkingPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeChunkingPortTest {

    @Test
    void chunkGeneratesConfiguredCount() {
        FakeChunkingPort port = new FakeChunkingPort(3);
        List<ChunkingPort.ChunkDraft> drafts = port.chunk("招标文件.pdf");
        assertEquals(3, drafts.size());
        assertEquals(0, drafts.get(0).getChunkIndex());
        assertEquals(1, drafts.get(0).getPageNo());
        assertTrue(drafts.get(0).getContent().contains("FAKE_CHUNK"));
        assertTrue(drafts.get(0).getContent().contains("招标文件.pdf"));
    }
}
