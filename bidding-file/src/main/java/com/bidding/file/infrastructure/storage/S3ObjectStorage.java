package com.bidding.file.infrastructure.storage;

import com.bidding.file.domain.port.ObjectStoragePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;

/**
 * MinIO（S3 兼容）对象存储实现。
 */
@Component
@ConditionalOnProperty(name = "bidding.storage.type", havingValue = "s3")
public class S3ObjectStorage implements ObjectStoragePort {

    private final S3Client s3Client;
    private final String bucket;

    public S3ObjectStorage(
            @Value("${bidding.storage.s3.endpoint}") String endpoint,
            @Value("${bidding.storage.s3.access-key}") String accessKey,
            @Value("${bidding.storage.s3.secret-key}") String secretKey,
            @Value("${bidding.storage.s3.bucket}") String bucket,
            @Value("${bidding.storage.s3.region:us-east-1}") String region) {
        this.bucket = bucket;
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Override
    public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey);
        if (contentType != null && !contentType.isBlank()) {
            builder.contentType(contentType);
        }
        s3Client.putObject(builder.build(), RequestBody.fromInputStream(content, sizeBytes));
    }

    @Override
    public InputStream open(String objectKey) {
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }

    @Override
    public void delete(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }
}
