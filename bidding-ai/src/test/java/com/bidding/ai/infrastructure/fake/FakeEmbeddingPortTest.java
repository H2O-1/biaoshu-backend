package com.bidding.ai.infrastructure.fake;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FakeEmbeddingPortTest {

    @Test
    void embedIsDeterministicAndFixedDim() {
        FakeEmbeddingPort port = new FakeEmbeddingPort(8);
        List<String> texts = new ArrayList<>();
        texts.add("hello");
        texts.add("world");

        List<float[]> a = port.embed(texts);
        List<float[]> b = port.embed(texts);

        assertEquals(2, a.size());
        assertEquals(8, a.get(0).length);
        assertEquals(8, port.dimension());
        assertEquals("fake-embedding", port.modelName());

        for (int i = 0; i < 8; i++) {
            assertEquals(a.get(0)[i], b.get(0)[i]);
        }
        // 不同文本向量应有差异
        assertNotEquals(a.get(0)[0], a.get(1)[0]);
    }
}
