package com.bidding.file.infrastructure.storage;

import com.bidding.file.domain.port.ObjectStoragePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存对象存储：local / 测试用，不连 MinIO。
 */
@Component
@ConditionalOnProperty(name = "bidding.storage.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryObjectStorage implements ObjectStoragePort {

    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = content.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            store.put(objectKey, buffer.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("写入内存存储失败: " + objectKey, e);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        byte[] data = store.get(objectKey);
        if (data == null) {
            throw new IllegalStateException("对象不存在: " + objectKey);
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public void delete(String objectKey) {
        store.remove(objectKey);
    }
}
