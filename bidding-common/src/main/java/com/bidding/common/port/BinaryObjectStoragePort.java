package com.bidding.common.port;

import java.io.InputStream;

/**
 * 通用二进制对象存储端口。
 */
public interface BinaryObjectStoragePort {

    void put(String objectKey, InputStream content, long sizeBytes, String contentType);

    /**
     * 打开对象输入流；调用方负责关闭。
     */
    InputStream open(String objectKey);

    void delete(String objectKey);
}
