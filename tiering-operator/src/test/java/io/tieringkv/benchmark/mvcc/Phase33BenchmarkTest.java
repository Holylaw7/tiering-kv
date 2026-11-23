package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor;
import io.tieringkv.capacity.ai.TrendPredictor;
import io.tieringkv.datamesh.DomainCatalog;
import io.tieringkv.datamesh.FederatedExecutor;
import io.tieringkv.datamesh.FederatedPlanner;
import io.tieringkv.datamesh.FederatedPlanner.Aggregate;
import io.tieringkv.datamesh.FederatedPlanner.Plan;
import io.tieringkv.datamesh.FederatedPlanner.Query;
import io.tieringkv.datamesh.DomainCatalog.Domain;
import io.tieringkv.gateway.PriorityRouter;
import io.tieringkv.gateway.RegionAffinityRouter;
import io.tieringkv.gateway.RegionQuota;
import io.tieringkv.gateway.TrafficPolicy;
import io.tieringkv.gateway.TrafficPolicy.PolicyEntry;
import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.replication.active.RaftAwareLeaderSelector;
import io.tieringkv.replication.active.RaftAwareLeaderSelector.RegionState;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import io.tieringkv.sql.txn.SqlTxnCoordinatorAdapter;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.geo.GeoDecisionLog;
import io.tieringkv.transaction.geo.GeoRegionTxnClient;
import io.tieringkv.transaction.geo.GeoTransactionCoordinator;
import io.tieringkv.transaction.geo.LocalGeoRpcTransport;
import io.tieringkv.transaction.participant.TransactionParticipant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 33 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase33BenchmarkTest {

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {50, 200})
    void sql2pcRealCoordinatorThroughput(int txns) throws Exception {
        GeoDecisionLog decisionLog = GeoDecisionLog.open(
                Files.createTempDirectory("phase33-bench"));
        LocalGeoRpcTransport transport = new LocalGeoRpcTransport();
        Map<String, GeoRegionTxnClient> clients =
                new LinkedHashMap<>();
        for (int i = 1; i <= 2; i++) {
            String region = "r" + i;
            MvccStorageEngine engine = new MvccStorageEngine(
                    MemTable.create());
            transport.register(region, new TransactionParticipant(
                    region, engine, new LockTable(), 60_000));
            clients.put(region, new GeoRegionTxnClient(region,
                    transport));
        }
        GeoTransactionCoordinator coordinator =
                new GeoTransactionCoordinator(decisionLog, clients,
                        key -> key[0] == 'b');
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        SqlTxnCoordinatorAdapter adapter =
                new SqlTxnCoordinatorAdapter(coordinator, credentials);
        long start = System.nanoTime();
        for (int i = 0; i < txns; i++) {
            adapter.begin(token);
            adapter.write(bytes(i % 2 == 0 ? "a" + i : "b" + i),
                    bytes("v"), false);
            assertThat(adapter.commit()).isTrue();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE33-BENCH SQL2PC-COORD %d -> %d txn/s%n",
                txns, txns * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "selections {0}")
    @ValueSource(ints = {1000, 10000})
    void raftTermLeaderSelectionThroughput(int count) {
        RaftAwareLeaderSelector selector =
                new RaftAwareLeaderSelector(Map.of(
                        "r1", new RegionState(5, true),
                        "r2", new RegionState(5, true),
                        "r3", new RegionState(4, true)), "r1");
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            selector.selectLeader();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE33-BENCH RAFT-LEADER %d -> %d ops/s%n",
                count, count * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10000})
    void federatedQueryThroughput(int rows) {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN")));
        catalog.register(new Domain("payments", "team-b",
                Set.of("ADMIN")));
        FederatedPlanner planner = new FederatedPlanner(catalog);
        Plan plan = planner.plan(new Query("revenue", "SUM",
                List.of("orders", "payments")), "ADMIN");
        FederatedExecutor executor = new FederatedExecutor();
        long start = System.nanoTime();
        for (int i = 0; i < rows; i++) {
            executor.execute(plan, shard -> new FederatedExecutor
                    .ShardResult(shard.domainId(), 1, 1));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE33-BENCH FEDERATED %d -> %d ops/s%n",
                rows, rows * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "routes {0}")
    @ValueSource(ints = {1000, 10000})
    void trafficGovernanceThroughput(int routes) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1_000_000);
        quota.setQuota("r2", 1_000_000);
        PriorityRouter router = new PriorityRouter(
                new RegionAffinityRouter(List.of("r1", "r2")),
                quota, List.of("r1", "r2"), true);
        long start = System.nanoTime();
        for (int i = 0; i < routes; i++) {
            router.route(bytes("k" + i), PriorityRouter.Priority.NORMAL);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE33-BENCH TRAFFIC %d -> %d ops/s%n",
                routes, routes * 1_000L / elapsedMs);
    }

    @Test
    void capacityAdvisorLatency() {
        AutoCapacityAdvisor advisor = new AutoCapacityAdvisor(
                new CapacityPlanner(), new TrendPredictor());
        List<TrendPredictor.Point> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            history.add(new TrendPredictor.Point(i, 100 + 20.0 * i));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            advisor.adviseAuto("qps", history, 30, 8, 1000,
                    2, 500, 100);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE33-BENCH CAPACITY %d ms%n", elapsedMs);
    }

    @Test
    void trafficPolicyQuotaMapping() {
        TrafficPolicy policy = new TrafficPolicy(Map.of(
                PriorityRouter.Priority.LOW,
                new PolicyEntry(1000, 0.1),
                PriorityRouter.Priority.NORMAL,
                new PolicyEntry(5000, 0.5),
                PriorityRouter.Priority.HIGH,
                new PolicyEntry(2000, 0.4)));
        assertThat(policy.quotaFor(1000, PriorityRouter.Priority.HIGH))
                .isEqualTo(400);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
