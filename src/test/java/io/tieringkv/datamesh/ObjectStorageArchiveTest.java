package io.tieringkv.datamesh;

import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 对象存储归档（ADR-0194）：上传/下载/删除 + 主权。 */
class ObjectStorageArchiveTest {

    @Test
    void uploadAndDownload() {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", "gcp-us", 42), 1000);
        assertThat(object.objectKey()).isEqualTo("obj-v1");
        ArchivedObject downloaded = archive.download(
                object.objectKey()).orElseThrow();
        assertThat(downloaded.snapshot().value()).isEqualTo(42);
    }

    @Test
    void crossResidencyRejected() {
        ObjectStorageArchive archive = archive("aws-eu");
        assertThatThrownBy(() -> archive.upload(
                snapshot("v1", "gcp-us", 42), 1000))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void missingObjectEmpty() {
        assertThat(archive("aws-us").download("missing"))
                .isEmpty();
    }

    @Test
    void deleteRemovesObject() {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", "gcp-us", 42), 1000);
        archive.delete(object.objectKey());
        assertThat(archive.download(object.objectKey())).isEmpty();
        assertThat(archive.size()).isZero();
    }

    @Test
    void nullSnapshotRejected() {
        assertThatThrownBy(() -> archive("aws-us")
                .upload(null, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleObjects() {
        ObjectStorageArchive archive = archive("aws-us");
        archive.upload(snapshot("v1", "gcp-us", 1), 1);
        archive.upload(snapshot("v2", "gcp-us", 2), 2);
        assertThat(archive.size()).isEqualTo(2);
        assertThat(archive.objectKeys()).containsExactlyInAnyOrder(
                "obj-v1", "obj-v2");
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 5.0, 100.0, 1_000.0})
    void parameterizedValues(double value) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", "gcp-us", value), 1);
        assertThat(archive.download(object.objectKey())
                .orElseThrow().snapshot().value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"gcp-us", "aws-us"})
    void parameterizedClouds(String remoteCloud) {
        ObjectStorageArchive archive = archive("aws-us");
        ArchivedObject object = archive.upload(
                snapshot("v1", remoteCloud, 1), 1);
        assertThat(object.snapshot().remoteCloud())
                .isEqualTo(remoteCloud);
    }

    @Test
    void concurrentUploadStable() throws Exception {
        ObjectStorageArchive archive = archive("aws-us");
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    archive.upload(snapshot("v" + i,
                            "gcp-us", i), i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(archive.size()).isEqualTo(50);
    }

    private static ObjectStorageArchive archive(String storageCloud) {
        return new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us",
                        "aws-eu", "eu")), storageCloud);
    }

    private static RemoteSnapshot snapshot(String viewId,
                                           String remoteCloud,
                                           double value) {
        return new RemoteSnapshot(viewId, remoteCloud, value, 1,
                false, 1);
    }
}
