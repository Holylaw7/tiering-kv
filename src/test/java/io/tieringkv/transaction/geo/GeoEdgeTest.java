package io.tieringkv.transaction.geo;

import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Geo 事务边缘（ADR-0109）：决策日志、重试矩阵、空事务。 */
class GeoEdgeTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 50})
    void decisionLogVolume(int count) throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("vol"));
        for (int i = 0; i < count; i++) {
            log.append(new GeoDecision("t" + i,
                    GeoDecision.Decision.COMMIT, i));
        }
        assertThat(log.readAll()).hasSize(count);
    }

    @Test
    void decisionLogMissingFileEmpty() throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("empty"));
        assertThat(log.readAll()).isEmpty();
    }

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(ints = {0, 1, 3})
    void clientRetryMatrix(int retries) {
        AtomicInteger attempts = new AtomicInteger();
        GeoRpcTransport transport = new GeoRpcTransport() {
            @Override
            public CompletableFuture<TxnMessages.Response> prewrite(
                    String region, TxnMessages.Prewrite request) {
                return CompletableFuture.completedFuture(
                        attempts.incrementAndGet() == 1
                                ? TxnMessages.Response.conflict("busy")
                                : TxnMessages.Response.ok());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> commit(
                    String region, TxnMessages.Commit request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }

            @Override
            public CompletableFuture<TxnMessages.Response> rollback(
                    String region, TxnMessages.Rollback request) {
                return CompletableFuture.completedFuture(
                        TxnMessages.Response.ok());
            }
        };
        GeoRegionTxnClient client = new GeoRegionTxnClient("r1",
                transport, retries);
        TxnMessages.Response response = client.prewrite(
                new TxnMessages.Prewrite("t1", 1, bytes("a"),
                        List.of())).join();
        if (retries >= 1) {
            assertThat(response.succeeded()).isTrue();
            assertThat(attempts.get()).isEqualTo(2);
        } else {
            assertThat(response.succeeded()).isFalse();
        }
    }

    @Test
    void duplicateRegionCommit() throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("dup"));
        log.append(new GeoDecision("t1", GeoDecision.Decision.COMMIT, 1));
        log.append(new GeoDecision("t1", GeoDecision.Decision.COMMIT, 1));
        assertThat(log.readAll()).hasSize(2); // 幂等重放由恢复层处理
    }

    @ParameterizedTest(name = "name {0}")
    @ValueSource(strings = {"", "geo-txn"})
    void decisionTxnIdBoundaries(String txnId) throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("id"));
        log.append(new GeoDecision(txnId, GeoDecision.Decision.ROLLBACK, 0));
        assertThat(log.readAll().get(0).txnId()).isEqualTo(txnId);
    }

    @Test
    void decisionLongTxnId() throws Exception {
        String txnId = "txn-" + "x".repeat(256);
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("long"));
        log.append(new GeoDecision(txnId, GeoDecision.Decision.COMMIT, 1));
        assertThat(log.readAll().get(0).txnId()).isEqualTo(txnId);
    }

    @Test
    void emptyMutationsNoDecision() throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("none"));
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(log, java.util.Map.of(),
                        key -> true);
        coordinator.commit(coordinator.begin(List.of()));
        assertThat(log.readAll()).isEmpty();
    }

    @Test
    void unknownRegionThrows() {
        GeoDecisionLog log;
        try {
            log = GeoDecisionLog.open(dir.resolve("unknown"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(log, java.util.Map.of(),
                        key -> true);
        GeoTransactionCoordinator.GeoTransaction txn =
                coordinator.begin(List.of(new TxnMessages.Mutation(
                        bytes("k"), bytes("v"), false)));
        assertThatThrownBy(() -> coordinator.commit(txn))
                .isInstanceOf(Exception.class);
    }

    @Test
    void decisionCommitTsOrdered() throws Exception {
        GeoDecisionLog log = GeoDecisionLog.open(dir.resolve("order"));
        for (int i = 0; i < 5; i++) {
            log.append(new GeoDecision("t" + i,
                    GeoDecision.Decision.COMMIT, i * 10));
        }
        List<GeoDecision> decisions = log.readAll();
        for (int i = 1; i < decisions.size(); i++) {
            assertThat(decisions.get(i).commitTS())
                    .isGreaterThan(decisions.get(i - 1).commitTS());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
