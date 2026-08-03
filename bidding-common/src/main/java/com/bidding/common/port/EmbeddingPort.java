package com.bidding.common.port;

import java.util.List;

/**
 * 文本向量化（实现可在 ai 模块：Fake 或真模型）。
 */
public interface EmbeddingPort {

    /**
     * 对多段文本分别生成向量；返回列表与入参一一对应。
     */
    List<float[]> embed(List<String> texts);

    /**
     * 当前模型输出维度。
     */
    int dimension();

    /**
     * 模型标识（写入向量表）。
     */
    String modelName();
}
