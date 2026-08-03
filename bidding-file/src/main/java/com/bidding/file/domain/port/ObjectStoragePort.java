package com.bidding.file.domain.port;

import java.io.InputStream;

/**
 * 对象存储（MinIO / S3 兼容）。
 */
public interface ObjectStoragePort {

    void put(String objectKey, InputStream content, long sizeBytes, String contentType);

    /** 打开对象输入流；调用方负责关闭。 */
    InputStream open(String objectKey);

    void delete(String objectKey);
}
