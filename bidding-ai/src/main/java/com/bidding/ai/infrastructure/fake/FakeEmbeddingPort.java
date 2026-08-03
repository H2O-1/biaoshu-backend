package com.bidding.ai.infrastructure.fake;

import com.bidding.common.port.EmbeddingPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake 向量：固定维度、由文本内容确定性生成，不调真实模型。
 * 仅在 bidding.index.mode=fake（默认）时启用。
 */
@Component
@ConditionalOnProperty(prefix = "bidding.index", name = "mode", havingValue = "fake", matchIfMissing = true)
public class FakeEmbeddingPort implements EmbeddingPort {

    private static final String MODEL_NAME = "fake-embedding";

    private final int dimension;

    public FakeEmbeddingPort(
            @Value("${bidding.index.embedding-dim:8}") int dimension) {
        if (dimension < 1) {
            this.dimension = 8;
        } else {
            this.dimension = dimension;
        }
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        if (texts == null) {
            return vectors;
        }
        for (int i = 0; i < texts.size(); i++) {
            vectors.add(embedOne(texts.get(i), i));
        }
        return vectors;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    private float[] embedOne(String text, int order) {
        float[] vector = new float[dimension];
        int seed = order;
        if (text != null) {
            seed = seed * 31 + text.hashCode();
        }
        // 简单确定性填充，避免全 0
        for (int d = 0; d < dimension; d++) {
            int v = (seed * 37 + d * 17) % 1000;
            if (v < 0) {
                v = -v;
            }
            vector[d] = v / 1000.0f;
        }
        return vector;
    }
}
