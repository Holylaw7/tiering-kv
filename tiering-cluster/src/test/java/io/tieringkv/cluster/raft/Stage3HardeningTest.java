package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.rpc.security.NonceCache;
import io.tieringkv.cluster.scheduler.RegulatoryComplianceCertificate;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Stage3HardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void nonceCachePreventsPipeCollisionAndEnforcesBoundedSize() {
        NonceCache cache = new NonceCache(60_000L, 3);
        long now = 1_000_000L;

        assertThat(cache.tryConsume("a|b", "c", now, 5_000L, now)).isTrue();
        assertThat(cache.tryConsume("a", "b|c", now, 5_000L, now)).isTrue();
        assertThat(cache.tryConsume("a|b", "c", now, 5_000L, now)).isFalse();

        for (int i = 0; i < 10; i++) {
            assertThat(cache.tryConsume("client-" + i, "nonce-" + i, now, 5_000L, now)).isTrue();
        }
        assertThat(cache.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void regulatoryCertificateUsesKeyedHmacAndRejectsForgedSha256() throws Exception {
        RegulatoryComplianceCertificate issuer = new RegulatoryComplianceCertificate();
        RegulatoryComplianceCertificate.Certificate cert1 = issuer.issue("digest-1", "auditor-a");
        issuer.rotateKey();
        RegulatoryComplianceCertificate.Certificate cert2 = issuer.issue("digest-2", "auditor-b");

        assertThat(issuer.verify(cert1)).isTrue();
        assertThat(issuer.verify(cert2)).isTrue();

        // Raw SHA-256 over payload without HMAC key must fail verification
        String rawPayload = cert1.chainDigest() + "|" + cert1.issuedAt()
                + "|" + cert1.issuer() + "|" + cert1.keyVersion();
        byte[] rawSha = MessageDigest.getInstance("SHA-256")
                .digest(rawPayload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : rawSha) {
            hex.append(String.format("%02x", b));
        }
        RegulatoryComplianceCertificate.Certificate forged = new RegulatoryComplianceCertificate.Certificate(
                cert1.chainDigest(), cert1.issuedAt(), cert1.issuer(), hex.toString(), cert1.keyVersion());
        assertThat(issuer.verify(forged)).isFalse();
    }

    @Test
    void mvccScanHandlesNullBoundsWithoutNpe() {
        MvccStorageEngine mvcc = new MvccStorageEngine(MemTable.create());
        mvcc.putVersion("k1".getBytes(StandardCharsets.UTF_8),
                "v1".getBytes(StandardCharsets.UTF_8), 10L, 10L, WriteType.PUT);
        mvcc.putVersion("k2".getBytes(StandardCharsets.UTF_8),
                "v2".getBytes(StandardCharsets.UTF_8), 20L, 20L, WriteType.PUT);

        Map<byte[], byte[]> scanned = mvcc.scan(null, null, 25L);
        assertThat(scanned).hasSize(2);
    }

    @Test
    void fileRaftLogAsyncInstallSnapshotPreservesSuffixSafely() throws Exception {
        Path logDir = tempDir.resolve("raft-log");
        try (FileRaftLog log = FileRaftLog.open(logDir, Durability.ASYNC, 512, 10)) {
            for (int i = 0; i < 60; i++) {
                log.append(new LogEntry(1L, i, ("cmd-" + i).getBytes(StandardCharsets.UTF_8)));
            }
            log.installSnapshot(39L);
            for (int i = 60; i < 80; i++) {
                log.append(new LogEntry(1L, i, ("cmd-" + i).getBytes(StandardCharsets.UTF_8)));
            }
            assertThat(log.firstIndex()).isEqualTo(40L);
            assertThat(log.lastIndex()).isEqualTo(79L);
            assertThat(log.entryAt(40L).command()).isEqualTo("cmd-40".getBytes(StandardCharsets.UTF_8));
            assertThat(log.entryAt(79L).command()).isEqualTo("cmd-79".getBytes(StandardCharsets.UTF_8));
        }

        try (FileRaftLog reopened = FileRaftLog.open(logDir, Durability.SYNC)) {
            assertThat(reopened.firstIndex()).isEqualTo(40L);
            assertThat(reopened.lastIndex()).isEqualTo(79L);
            assertThat(reopened.entryAt(40L).command()).isEqualTo("cmd-40".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void raftNodeCloseFailsPendingProposeFuturesImmediately() throws Exception {
        Path logDir = tempDir.resolve("node-log");
        try (FileRaftLog log = FileRaftLog.open(logDir, Durability.SYNC)) {
            RaftTransport stalledTransport = new RaftTransport() {
                @Override
                public List<String> peerIds() {
                    return List.of("n1", "n2", "n3");
                }

                @Override
                public CompletableFuture<VoteResponse> requestVote(String peer, VoteRequest request) {
                    return CompletableFuture.completedFuture(new VoteResponse(request.term(), true));
                }

                @Override
                public CompletableFuture<AppendEntriesResponse> appendEntries(String peer, AppendEntriesRequest request) {
                    return new CompletableFuture<>();
                }

                @Override
                public CompletableFuture<InstallSnapshotResponse> installSnapshot(String peer, InstallSnapshotRequest request) {
                    return new CompletableFuture<>();
                }
            };
            RaftNode node = new RaftNode(
                    "n1", stalledTransport, (idx, cmd) -> { },
                    new LeaderElection(20L, 40L), 10L, 20L,
                    log, null, null);
            node.start();

            long deadline = System.currentTimeMillis() + 3_000L;
            while (node.state() != RaftState.LEADER && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(node.state()).isEqualTo(RaftState.LEADER);

            node.close();

            CompletableFuture<Long> afterClose = node.propose("payload".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> afterClose.get(1, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(node.lastFlushError()).isNull();
        }
    }
}
