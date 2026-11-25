package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

/** S3 / MinIO 对象存储共享备份后端（N1-A）。 */
@Component
@ConditionalOnProperty(name = "console.backup.storage-type", havingValue = "s3")
public class S3BackupStorageBackend implements BackupStorageBackend {

    private static final Logger log = LoggerFactory.getLogger(S3BackupStorageBackend.class);
    private static final long DEFAULT_MULTIPART_THRESHOLD = 16L * 1024 * 1024;
    private static final int DEFAULT_PART_SIZE = 8 * 1024 * 1024;

    private final S3Client s3Client;
    private final boolean ownsClient;
    private final String bucket;
    private final String prefix;
    private final Path stagingDir;
    private final long quotaBytes;
    private final boolean autoCreateBucket;
    private final long multipartThresholdBytes;
    private final int partSizeBytes;
    private volatile boolean bucketVerified;

    @Autowired
    public S3BackupStorageBackend(ConsoleProperties properties) {
        ConsoleProperties.S3 cfg = properties.getBackup().getS3();
        this.bucket = requireBucket(cfg.getBucket());
        this.prefix = normalizePrefix(cfg.getPrefix());
        this.stagingDir = resolveStagingDir(cfg.getStagingDir());
        this.quotaBytes = Math.max(0L, cfg.getQuotaBytes());
        this.autoCreateBucket = cfg.isAutoCreateBucket();
        this.multipartThresholdBytes = DEFAULT_MULTIPART_THRESHOLD;
        this.partSizeBytes = DEFAULT_PART_SIZE;
        this.s3Client = buildClient(cfg);
        this.ownsClient = true;
    }

    public S3BackupStorageBackend(S3Client s3Client, String bucket, String prefix,
                                  Path stagingDir, long quotaBytes) {
        this(s3Client, bucket, prefix, stagingDir, quotaBytes, false,
                DEFAULT_MULTIPART_THRESHOLD, DEFAULT_PART_SIZE);
    }

    public S3BackupStorageBackend(S3Client s3Client, String bucket, String prefix,
                                  Path stagingDir, long quotaBytes,
                                  long multipartThresholdBytes, int partSizeBytes) {
        this(s3Client, bucket, prefix, stagingDir, quotaBytes, false,
                multipartThresholdBytes, partSizeBytes);
    }

    public S3BackupStorageBackend(S3Client s3Client, String bucket, String prefix,
                                  Path stagingDir, long quotaBytes, boolean autoCreateBucket,
                                  long multipartThresholdBytes, int partSizeBytes) {
        if (s3Client == null) {
            throw new IllegalArgumentException("s3Client must not be null");
        }
        this.s3Client = s3Client;
        this.ownsClient = false;
        this.bucket = requireBucket(bucket);
        this.prefix = normalizePrefix(prefix);
        this.stagingDir = stagingDir.toAbsolutePath().normalize();
        this.quotaBytes = Math.max(0L, quotaBytes);
        this.autoCreateBucket = autoCreateBucket;
        this.multipartThresholdBytes = Math.max(1024L, multipartThresholdBytes);
        this.partSizeBytes = Math.max(512, partSizeBytes);
    }

    @PreDestroy
    public void close() {
        if (ownsClient) {
            s3Client.close();
        }
    }

    @Override
    public OutputStream openTempOutput(String fileName) throws IOException {
        Files.createDirectories(stagingDir);
        return Files.newOutputStream(resolveTemp(fileName));
    }

    @Override
    public long publish(String fileName) throws IOException {
        Path temp = resolveTemp(fileName);
        if (!Files.isRegularFile(temp)) {
            throw new IOException("临时备份文件不存在：" + temp.getFileName());
        }
        long size = Files.size(temp);
        String key = objectKey(fileName);
        try {
            ensureBucketReady();
            if (size <= multipartThresholdBytes) {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .contentType("application/octet-stream")
                                .build(),
                        RequestBody.fromFile(temp));
            } else {
                uploadMultipart(temp, key);
            }
        } catch (SdkException ex) {
            throw new IOException("上传备份至 S3 失败：" + ex.getMessage(), ex);
        }
        deleteTempQuietly(fileName);
        return size;
    }

    private void uploadMultipart(Path temp, String key) throws IOException {
        CreateMultipartUploadResponse created = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/octet-stream")
                        .build());
        String uploadId = created.uploadId();
        List<CompletedPart> completedParts = new ArrayList<>();
        try (InputStream in = Files.newInputStream(temp)) {
            byte[] buffer = new byte[partSizeBytes];
            int partNumber = 1;
            int read;
            while ((read = readFullChunk(in, buffer)) > 0) {
                byte[] payload = read == buffer.length ? buffer : Arrays.copyOf(buffer, read);
                UploadPartResponse uploaded = s3Client.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket)
                                .key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength((long) read)
                                .build(),
                        RequestBody.fromBytes(payload));
                completedParts.add(CompletedPart.builder()
                        .partNumber(partNumber)
                        .eTag(uploaded.eTag())
                        .build());
                partNumber++;
            }
            s3Client.completeMultipartUpload(
                    CompleteMultipartUploadRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .uploadId(uploadId)
                            .multipartUpload(CompletedMultipartUpload.builder()
                                    .parts(completedParts)
                                    .build())
                            .build());
        } catch (IOException | RuntimeException ex) {
            abortQuietly(key, uploadId);
            if (ex instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("分片上传备份至 S3 失败：" + ex.getMessage(), ex);
        }
    }

    private static int readFullChunk(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int n = in.read(buffer, offset, buffer.length - offset);
            if (n < 0) {
                break;
            }
            offset += n;
        }
        return offset;
    }

    private void abortQuietly(String key, String uploadId) {
        try {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("终止 S3 分片上传失败 key={} uploadId={}", key, uploadId, ex);
        }
    }

    @Override
    public void deleteTempQuietly(String fileName) {
        try {
            Files.deleteIfExists(resolveTemp(fileName));
        } catch (IOException | RuntimeException ex) {
            log.warn("删除 S3 临时暂存文件失败：{}", fileName, ex);
        }
    }

    @Override
    public InputStream openInput(String fileName) throws IOException {
        String key = objectKey(fileName);
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (NoSuchKeyException | NoSuchBucketException ex) {
            throw BusinessException.notFound("备份文件不存在：" + fileName);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw BusinessException.notFound("备份文件不存在：" + fileName);
            }
            throw new IOException("读取 S3 备份失败：" + sanitizeMessage(ex), ex);
        } catch (SdkException ex) {
            throw new IOException("读取 S3 备份失败：" + sanitizeMessage(ex), ex);
        }
    }

    @Override
    public boolean exists(String fileName) {
        String key = objectKey(fileName);
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException | NoSuchBucketException ex) {
            return false;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return false;
            }
            throw new IllegalStateException("查询 S3 备份对象状态失败：" + sanitizeMessage(ex), ex);
        } catch (SdkException ex) {
            throw new IllegalStateException("查询 S3 备份对象状态失败：" + sanitizeMessage(ex), ex);
        }
    }

    @Override
    public boolean deleteIfExists(String fileName) throws IOException {
        String key = objectKey(fileName);
        try {
            if (!exists(fileName)) {
                return false;
            }
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (IllegalStateException | SdkException ex) {
            throw new IOException("删除 S3 备份对象失败：" + sanitizeMessage(ex), ex);
        }
    }

    @Override
    public List<StoredObject> listObjects() throws IOException {
        List<StoredObject> objects = new ArrayList<>();
        String continuationToken = null;
        try {
            ensureBucketReady();
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix);
                if (continuationToken != null) {
                    req.continuationToken(continuationToken);
                }
                ListObjectsV2Response resp = s3Client.listObjectsV2(req.build());
                if (resp.contents() != null) {
                    for (S3Object obj : resp.contents()) {
                        String key = obj.key();
                        if (key == null || !key.startsWith(prefix)) {
                            continue;
                        }
                        String name = key.substring(prefix.length());
                        if (name.isEmpty() || name.contains("/") || name.endsWith(".tmp")) {
                            continue;
                        }
                        long modifiedMillis = obj.lastModified() == null
                                ? 0L
                                : obj.lastModified().toEpochMilli();
                        long size = obj.size() == null ? 0L : obj.size();
                        objects.add(new StoredObject(name, size, modifiedMillis));
                    }
                }
                continuationToken = Boolean.TRUE.equals(resp.isTruncated())
                        ? resp.nextContinuationToken()
                        : null;
            } while (continuationToken != null);
        } catch (SdkException ex) {
            throw new IOException("列举 S3 备份对象失败：" + ex.getMessage(), ex);
        }
        return objects;
    }

    @Override
    public String storageKey() {
        if (prefix.isEmpty()) {
            return "s3://" + bucket;
        }
        String trimmed = prefix.substring(0, prefix.length() - 1);
        return "s3://" + bucket + "/" + trimmed;
    }

    @Override
    public OptionalLong usableSpaceBytes() {
        try {
            Files.createDirectories(stagingDir);
            return OptionalLong.of(Files.getFileStore(stagingDir).getUsableSpace());
        } catch (IOException ex) {
            log.warn("读取 S3 暂存目录可用空间失败：{}", stagingDir, ex);
            return OptionalLong.empty();
        }
    }

    @Override
    public OptionalLong remoteQuotaBytes() {
        return quotaBytes > 0 ? OptionalLong.of(quotaBytes) : OptionalLong.empty();
    }

    private void ensureBucketReady() throws IOException {
        if (bucketVerified || !autoCreateBucket) {
            return;
        }
        synchronized (this) {
            if (bucketVerified || !autoCreateBucket) {
                return;
            }
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                bucketVerified = true;
            } catch (NoSuchBucketException ex) {
                createBucketIfMissing();
                bucketVerified = true;
            } catch (S3Exception ex) {
                if (ex.statusCode() == 404) {
                    createBucketIfMissing();
                    bucketVerified = true;
                } else {
                    throw new IOException("检查 S3 存储桶失败：" + sanitizeMessage(ex), ex);
                }
            } catch (SdkException ex) {
                throw new IOException("连接 S3 存储桶失败：" + sanitizeMessage(ex), ex);
            }
        }
    }

    private void createBucketIfMissing() throws IOException {
        try {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("已自动创建 S3 备份存储桶：{}", bucket);
        } catch (BucketAlreadyExistsException | BucketAlreadyOwnedByYouException ex) {
            log.debug("S3 存储桶已存在：{}", bucket);
        } catch (SdkException ex) {
            throw new IOException("自动创建 S3 存储桶失败：" + sanitizeMessage(ex), ex);
        }
    }

    private static String sanitizeMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        // 避免长堆栈或签名/密钥片段透出至前端错误提示
        String cleaned = msg.replaceAll("(?i)(AWSAccessKeyId|Credential|Signature|SecretKey)=[^&\\s,;]+", "$1=***");
        return cleaned.length() > 240 ? cleaned.substring(0, 240) + "..." : cleaned;
    }

    private String objectKey(String fileName) {
        validateFileName(fileName);
        return prefix + fileName;
    }

    private Path resolveTemp(String fileName) {
        validateFileName(fileName);
        Path temp = stagingDir.resolve(fileName + ".tmp").normalize();
        if (!temp.startsWith(stagingDir) || temp.equals(stagingDir)) {
            throw BusinessException.badRequest("非法备份路径");
        }
        return temp;
    }

    private static S3Client buildClient(ConsoleProperties.S3 cfg) {
        String region = (cfg.getRegion() == null || cfg.getRegion().isBlank())
                ? "us-east-1"
                : cfg.getRegion().trim();
        int connectMs = Math.max(500, cfg.getConnectTimeoutMs());
        int socketMs = Math.max(1000, cfg.getSocketTimeoutMs());
        int apiMs = Math.max(socketMs, cfg.getApiCallTimeoutMs());
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(connectMs))
                        .socketTimeout(Duration.ofMillis(socketMs)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(Duration.ofMillis(socketMs))
                        .apiCallTimeout(Duration.ofMillis(apiMs))
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .forcePathStyle(cfg.isPathStyleAccess())
                .credentialsProvider(credentialsProvider(cfg));
        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(cfg.getEndpoint().trim()));
        }
        return builder.build();
    }

    private static AwsCredentialsProvider credentialsProvider(ConsoleProperties.S3 cfg) {
        boolean hasAccess = cfg.getAccessKey() != null && !cfg.getAccessKey().isBlank();
        boolean hasSecret = cfg.getSecretKey() != null && !cfg.getSecretKey().isBlank();
        if (hasAccess != hasSecret) {
            throw new IllegalStateException("console.backup.s3.access-key 与 secret-key 必须同时配置");
        }
        if (hasAccess) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(cfg.getAccessKey().trim(), cfg.getSecretKey().trim()));
        }
        return DefaultCredentialsProvider.create();
    }

    private static String requireBucket(String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("启用 storage-type=s3 时必须配置 console.backup.s3.bucket");
        }
        return bucket.trim();
    }

    private static Path resolveStagingDir(String raw) {
        String dir = (raw == null || raw.isBlank())
                ? System.getProperty("java.io.tmpdir", "/tmp") + "/tiering-console-staging"
                : raw.trim();
        return Paths.get(dir).toAbsolutePath().normalize();
    }

    static String normalizePrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }
}
