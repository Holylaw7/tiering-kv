package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.config.ProductionConfigGuard;
import io.tieringkv.webconsole.domain.BackupCapacity;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupReservation;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupCapacityRepository;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.BackupReservationRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** N1-A：S3/MinIO 对象存储后端、孤儿回收、远端配额与防穿越测试。 */
class S3BackupStorageBackendTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadDownloadExistsDeleteAndListObjectsWorkEndToEnd() throws Exception {
        Map<String, byte[]> bucketStore = new ConcurrentHashMap<>();
        S3Client s3 = mockInMemoryS3(bucketStore, "tiering-backups");
        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "tiering-backups", "/backups", tempDir.resolve("staging"), 100_000L);

        assertThat(backend.storageKey()).isEqualTo("s3://tiering-backups/backups");
        assertThat(backend.remoteQuotaBytes()).hasValue(100_000L);
        assertThat(backend.usableSpaceBytes()).isPresent();

        String fileName = "backup-1-test.jsonl.gz";
        byte[] content = "hello-s3-backup".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = backend.openTempOutput(fileName)) {
            out.write(content);
        }
        assertThat(Files.exists(tempDir.resolve("staging").resolve(fileName + ".tmp"))).isTrue();

        long publishedSize = backend.publish(fileName);
        assertThat(publishedSize).isEqualTo(content.length);
        assertThat(Files.exists(tempDir.resolve("staging").resolve(fileName + ".tmp"))).isFalse();
        assertThat(bucketStore).containsKey("backups/" + fileName);
        assertThat(backend.exists(fileName)).isTrue();
        assertThat(backend.exists("missing.jsonl.gz")).isFalse();

        try (InputStream in = backend.openInput(fileName)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }

        // 列举对象应剥离 prefix 并跳过目录标记及嵌套子目录
        bucketStore.put("backups/", new byte[0]);
        bucketStore.put("backups/nested/ignored.jsonl", new byte[1]);
        bucketStore.put("backups/orphan.jsonl", "old".getBytes(StandardCharsets.UTF_8));
        List<BackupStorageBackend.StoredObject> listed = backend.listObjects();
        assertThat(listed).extracting(BackupStorageBackend.StoredObject::name)
                .containsExactlyInAnyOrder(fileName, "orphan.jsonl");

        assertThat(backend.deleteIfExists(fileName)).isTrue();
        assertThat(backend.deleteIfExists(fileName)).isFalse();
        assertThat(backend.exists(fileName)).isFalse();
    }

    @Test
    void largeBackupUsesMultipartUploadAndAbortsOnFailure() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("u-1").build());
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(UploadPartResponse.builder().eTag("etag-part").build());
        when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().build());

        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "bkt", "", tempDir.resolve("staging"), 0L, 1024L, 512);
        assertThat(backend.storageKey()).isEqualTo("s3://bkt");

        byte[] payload = new byte[1500];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 127);
        }
        try (OutputStream out = backend.openTempOutput("big.jsonl.gz")) {
            out.write(payload);
        }
        assertThat(backend.publish("big.jsonl.gz")).isEqualTo(1500L);
        verify(s3).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        verify(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));

        // 测试分片中途失败触发 abort 且 temp 可清理
        when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("network reset").statusCode(500).build());
        when(s3.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenReturn(AbortMultipartUploadResponse.builder().build());

        try (OutputStream out = backend.openTempOutput("big-fail.jsonl.gz")) {
            out.write(payload);
        }
        assertThatThrownBy(() -> backend.publish("big-fail.jsonl.gz"))
                .isInstanceOf(IOException.class);
        verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        backend.deleteTempQuietly("big-fail.jsonl.gz");
        assertThat(Files.exists(tempDir.resolve("staging").resolve("big-fail.jsonl.gz.tmp"))).isFalse();
    }

    @Test
    void rejectsPathTraversalAndMissingKeyThrows404() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "tiering-backups", "backups/", tempDir.resolve("staging"), 0L);

        for (String bad : List.of("", " ", ".", "..", "../secret.jsonl", "a/b.jsonl", "a\\b.jsonl", "bad..file")) {
            assertThatThrownBy(() -> backend.openTempOutput(bad))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法备份路径");
            assertThatThrownBy(() -> backend.exists(bad))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法备份路径");
        }

        assertThatThrownBy(() -> backend.openInput("absent.jsonl.gz"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(404));
    }

    @Test
    void retentionAndCapacityUseBackendAbstractionForS3Mode() throws Exception {
        BackupStorageBackend backend = mock(BackupStorageBackend.class);
        when(backend.storageKey()).thenReturn("s3://tiering-backups/backups");
        when(backend.usableSpaceBytes()).thenReturn(OptionalLong.of(10L * 1024 * 1024 * 1024));
        when(backend.remoteQuotaBytes()).thenReturn(OptionalLong.of(2048L));
        when(backend.listObjects()).thenReturn(List.of(
                new BackupStorageBackend.StoredObject("existing.jsonl.gz", 1500L,
                        System.currentTimeMillis() - 10_000L),
                new BackupStorageBackend.StoredObject("orphan-old.jsonl.gz", 100L,
                        System.currentTimeMillis() - 3L * 3600_000L)));
        when(backend.deleteIfExists("orphan-old.jsonl.gz")).thenReturn(true);

        // Capacity：1500 + 1024 > quota 2048 -> 507 拒绝
        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().getCapacity().setMinFreeBytes(0);
        props.getBackup().getCapacity().setEstimateFloorBytes(1024);
        BackupReservationRepository resRepo = mock(BackupReservationRepository.class);
        BackupCapacityRepository capRepo = mock(BackupCapacityRepository.class);
        BackupRecordRepository recRepo = mock(BackupRecordRepository.class);
        when(capRepo.lockByKey("s3://tiering-backups/backups")).thenReturn(Optional.empty());
        when(capRepo.saveAndFlush(any(BackupCapacity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(resRepo.sumReserved("s3://tiering-backups/backups")).thenReturn(0L);

        BackupCapacityService capService = new BackupCapacityService(
                resRepo, capRepo, recRepo, props, backend);
        assertThat(capService.view().storageKey()).isEqualTo("s3://tiering-backups/backups");
        assertThatThrownBy(() -> capService.reserve(1L, 10L, 1024L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(507));

        // Retention：通过 SPI 删除过期孤儿对象，不触碰不存在于桶内的记录
        DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
        DbInstance inst = new DbInstance();
        inst.setId(1L);
        inst.setName("s3-node");
        when(instRepo.findAll()).thenReturn(List.of(inst));
        BackupRecord keep = new BackupRecord();
        keep.setId(1L);
        keep.setInstanceId(1L);
        keep.setFileName("existing.jsonl.gz");
        keep.setStatus(BackupStatus.SUCCESS);
        keep.setRestoreStatus(RestoreStatus.NONE);
        keep.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(recRepo.findByInstanceId(1L)).thenReturn(List.of(keep));
        when(recRepo.existsByFileName("existing.jsonl.gz")).thenReturn(true);
        when(recRepo.existsByFileName("orphan-old.jsonl.gz")).thenReturn(false);

        BackupRetentionService retention = new BackupRetentionService(
                recRepo, instRepo, props, backend);
        var report = retention.purge();
        assertThat(report.orphanRemoved()).isEqualTo(1);
        verify(backend).deleteIfExists("orphan-old.jsonl.gz");
    }

    @Test
    void productionGuardValidatesS3ConfigWithoutLeakingSecrets() {
        ConsoleProperties props = new ConsoleProperties();
        props.getJwt().setSecret("Zk8Qv2LmN7pR4tXyA9bC3dE6fG1hJ5kM0nP8sU2w");
        props.getBootstrap().setAdminPassword("AdminPass!2345");
        props.getBackup().setStorageType("s3");
        props.getBackup().getS3().setAccessKey("ak-only");
        props.getBackup().getS3().setSecretKey("");

        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "S3cure-db-pass")
                .withProperty("spring.datasource.username", "console_app")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/tiering_console")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false");

        ProductionConfigGuard guard = new ProductionConfigGuard(props, env);
        List<ProductionConfigGuard.Finding> findings = guard.check();
        assertThat(findings)
                .anyMatch(f -> "FATAL".equals(f.level()) && "console.backup.s3.bucket".equals(f.key()))
                .anyMatch(f -> "FATAL".equals(f.level()) && "console.backup.s3.credentials".equals(f.key()));
        assertThat(findings.toString()).doesNotContain("ak-only");
    }

    @Test
    void autoCreateBucketRunsOnceWhenMissing() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("missing").build());
        when(s3.createBucket(any(CreateBucketRequest.class)))
                .thenReturn(CreateBucketResponse.builder().build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag").build());

        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "new-bucket", "backups/", tempDir.resolve("staging"), 0L, true, 1024L * 1024L, 512);
        try (OutputStream out = backend.openTempOutput("b1.jsonl.gz")) {
            out.write("x".getBytes(StandardCharsets.UTF_8));
        }
        backend.publish("b1.jsonl.gz");
        verify(s3).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void transientHeadBucketFailureDoesNotPoisonAutoCreateRetry() throws Exception {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(SdkClientException.create("Connection refused: minio:9000"))
                .thenThrow(NoSuchBucketException.builder().message("missing").build());
        when(s3.createBucket(any(CreateBucketRequest.class)))
                .thenReturn(CreateBucketResponse.builder().build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-retry").build());

        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "retry-bucket", "backups/", tempDir.resolve("staging"), 0L, true, 1024L * 1024L, 512);
        try (OutputStream out = backend.openTempOutput("b-retry.jsonl.gz")) {
            out.write("payload".getBytes(StandardCharsets.UTF_8));
        }

        // 首次连接失败应抛出 IOException，且不标记 bucketVerified=true
        assertThatThrownBy(() -> backend.publish("b-retry.jsonl.gz"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("连接 S3 存储桶失败");

        // 重试时重新检测桶并完成自动建桶与上传
        assertThat(backend.publish("b-retry.jsonl.gz")).isEqualTo(7L);
        verify(s3).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void s3AccessDeniedAndNetworkErrorsWrapIntoSanitizedIOException() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(S3Exception.builder()
                        .statusCode(403)
                        .message("AccessDenied Credential=AKIASECRET123/20261125/us-east-1/s3/aws4_request")
                        .build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Read timed out"));
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("NoSuchBucket").build());

        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "denied-bucket", "backups/", tempDir.resolve("staging"), 0L, true, 1024L * 1024L, 512);

        assertThatThrownBy(backend::listObjects)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("检查 S3 存储桶失败")
                .hasMessageContaining("Credential=***")
                .hasMessageNotContaining("AKIASECRET123");

        assertThatThrownBy(() -> backend.deleteIfExists("a.jsonl.gz"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("删除 S3 备份对象失败");

        assertThatThrownBy(() -> backend.openInput("a.jsonl.gz"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(404));
    }

    @Test
    void noSuchBucketReturnsFalseOnExistsAndPropertiesBuildClientCleanly() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("NoSuchBucket").build());
        S3BackupStorageBackend backend = new S3BackupStorageBackend(
                s3, "absent-bucket", "backups/", tempDir.resolve("staging"), 0L);
        assertThat(backend.exists("any.jsonl.gz")).isFalse();

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().getS3().setEndpoint("http://127.0.0.1:9000");
        props.getBackup().getS3().setBucket("unit-test-bucket");
        props.getBackup().getS3().setAccessKey("minioadmin");
        props.getBackup().getS3().setSecretKey("minioadmin");
        props.getBackup().getS3().setConnectTimeoutMs(2000);
        props.getBackup().getS3().setSocketTimeoutMs(5000);
        props.getBackup().getS3().setApiCallTimeoutMs(10000);
        S3BackupStorageBackend built = new S3BackupStorageBackend(props);
        try {
            assertThat(built.storageKey()).isEqualTo("s3://unit-test-bucket/backups");
        } finally {
            built.close();
        }
    }

    private static S3Client mockInMemoryS3(Map<String, byte[]> store, String bucket) {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            PutObjectRequest req = inv.getArgument(0);
            RequestBody body = inv.getArgument(1);
            assertThat(req.bucket()).isEqualTo(bucket);
            byte[] bytes = body.contentStreamProvider().newStream().readAllBytes();
            store.put(req.key(), bytes);
            return PutObjectResponse.builder().eTag("etag-1").build();
        });
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(inv -> {
            HeadObjectRequest req = inv.getArgument(0);
            byte[] data = store.get(req.key());
            if (data == null) {
                throw NoSuchKeyException.builder().message("not found").build();
            }
            return HeadObjectResponse.builder().contentLength((long) data.length).build();
        });
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            GetObjectRequest req = inv.getArgument(0);
            byte[] data = store.get(req.key());
            if (data == null) {
                throw NoSuchKeyException.builder().message("not found").build();
            }
            return new ResponseInputStream<>(
                    GetObjectResponse.builder().contentLength((long) data.length).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(data)));
        });
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(inv -> {
            DeleteObjectRequest req = inv.getArgument(0);
            store.remove(req.key());
            return DeleteObjectResponse.builder().build();
        });
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(inv -> {
            ListObjectsV2Request req = inv.getArgument(0);
            String pfx = req.prefix() == null ? "" : req.prefix();
            List<S3Object> objs = store.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(pfx))
                    .map(e -> S3Object.builder()
                            .key(e.getKey())
                            .size((long) e.getValue().length)
                            .lastModified(Instant.now())
                            .build())
                    .toList();
            return ListObjectsV2Response.builder().contents(objs).isTruncated(false).build();
        });
        return s3;
    }
}
