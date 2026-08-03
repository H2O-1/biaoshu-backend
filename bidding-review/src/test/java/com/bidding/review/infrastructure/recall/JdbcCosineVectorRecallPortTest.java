package com.bidding.review.infrastructure.recall;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCosineVectorRecallPortTest {

    @Test
    void cosineIdenticalVectorsIsOne() {
        float[] a = new float[]{1f, 0f, 0f};
        float[] b = new float[]{1f, 0f, 0f};
        assertEquals(1f, JdbcCosineVectorRecallPort.cosine(a, b), 0.0001f);
    }

    @Test
    void cosineOrthogonalIsZero() {
        float[] a = new float[]{1f, 0f};
        float[] b = new float[]{0f, 1f};
        assertEquals(0f, JdbcCosineVectorRecallPort.cosine(a, b), 0.0001f);
    }

    @Test
    void cosineZeroVectorIsZero() {
        float[] a = new float[]{0f, 0f};
        float[] b = new float[]{1f, 2f};
        assertEquals(0f, JdbcCosineVectorRecallPort.cosine(a, b), 0.0001f);
    }

    @Test
    void cosineSimilarHigherThanDissimilar() {
        float[] query = new float[]{1f, 1f, 0f};
        float[] near = new float[]{0.9f, 1.1f, 0.1f};
        float[] far = new float[]{-1f, 0f, 1f};
        float nearScore = JdbcCosineVectorRecallPort.cosine(query, near);
        float farScore = JdbcCosineVectorRecallPort.cosine(query, far);
        assertTrue(nearScore > farScore);
    }
}
