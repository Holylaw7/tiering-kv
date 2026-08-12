package io.tieringkv.datamesh;

import io.tieringkv.datamesh.FederatedExecutor.AggregateResult;
import io.tieringkv.datamesh.FederatedExecutor.JoinRow;
import io.tieringkv.datamesh.FederatedExecutor.Row;
import io.tieringkv.datamesh.FederatedExecutor.ShardResult;
import io.tieringkv.datamesh.FederatedPlanner.Aggregate;
import io.tieringkv.datamesh.FederatedPlanner.Plan;
import io.tieringkv.datamesh.FederatedPlanner.Shard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据网格联邦执行（ADR-0148）：跨域聚合 + JOIN。 */
class FederatedExecutorTest {

    @Test
    void sumAcrossDomains() {
        AggregateResult result = new FederatedExecutor().execute(
                plan("revenue", Aggregate.SUM, 3),
                shard -> new ShardResult(shard.domainId(),
                        shard.domainId().equals("d0") ? 10 : 20,
                        1));
        assertThat(result.value()).isEqualTo(50);
        assertThat(result.count()).isEqualTo(3);
    }

    @Test
    void countAcrossDomains() {
        AggregateResult result = new FederatedExecutor().execute(
                plan("events", Aggregate.COUNT, 2),
                shard -> new ShardResult(shard.domainId(), 0, 100));
        assertThat(result.value()).isEqualTo(200);
        assertThat(result.count()).isEqualTo(200);
    }

    @Test
    void averageAcrossDomains() {
        AggregateResult result = new FederatedExecutor().execute(
                plan("latency", Aggregate.AVG, 2),
                shard -> new ShardResult(shard.domainId(),
                        shard.domainId().equals("d0") ? 10 : 30,
                        1));
        assertThat(result.value()).isEqualTo(20);
        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void averageWeightedByCount() {
        AggregateResult result = new FederatedExecutor().execute(
                plan("latency", Aggregate.AVG, 2),
                shard -> new ShardResult(shard.domainId(),
                        shard.domainId().equals("d0") ? 10 : 30,
                        shard.domainId().equals("d0") ? 1 : 3));
        assertThat(result.value()).isEqualTo(25);
        assertThat(result.count()).isEqualTo(4);
    }

    @Test
    void minAcrossDomains() {
        AggregateResult result = new FederatedExecutor().execute(
                plan("latency", Aggregate.MIN, 3),
                shard -> new ShardResult(shard.domainId(),
                        switch (shard.domainId()) {
                            case "d0" -> 9;
                            case "d1" -> 3;
                            default -> 7;
                        }, 1));
        assertThat(result.value()).isEqualTo(3);
    }

    @Test
    void maxAcrossDomains() {
        AggregateResult result = new FederatedExecutor().execute(
                plan("latency", Aggregate.MAX, 3),
                shard -> new ShardResult(shard.domainId(),
                        switch (shard.domainId()) {
                            case "d0" -> 9;
                            case "d1" -> 3;
                            default -> 7;
                        }, 1));
        assertThat(result.value()).isEqualTo(9);
    }

    @Test
    void emptyPlanSumZero() {
        AggregateResult result = new FederatedExecutor().execute(
                new Plan("revenue", Aggregate.SUM, List.of()),
                shard -> new ShardResult(shard.domainId(), 0, 0));
        assertThat(result.value()).isZero();
        assertThat(result.count()).isZero();
    }

    @Test
    void emptyAverageZero() {
        AggregateResult result = new FederatedExecutor().execute(
                new Plan("latency", Aggregate.AVG, List.of()),
                shard -> new ShardResult(shard.domainId(), 0, 0));
        assertThat(result.value()).isZero();
        assertThat(result.count()).isZero();
    }

    @Test
    void nullPlanRejected() {
        assertThatThrownBy(() -> new FederatedExecutor()
                .execute(null, shard -> new ShardResult("d", 0, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullExecutorRejected() {
        assertThatThrownBy(() -> new FederatedExecutor()
                .execute(plan("revenue", Aggregate.SUM, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void innerJoinMatchesSharedKeys() {
        FederatedExecutor executor = new FederatedExecutor();
        List<JoinRow> joined = executor.join(
                List.of(new Row("a", "orders", 10),
                        new Row("b", "orders", 20),
                        new Row("c", "orders", 30)),
                List.of(new Row("a", "payments", 100),
                        new Row("b", "payments", 200)));
        assertThat(joined).extracting(JoinRow::key)
                .containsExactly("a", "b");
        assertThat(joined.get(0).right()).isEqualTo(100);
    }

    @Test
    void innerJoinDisjointKeysEmpty() {
        FederatedExecutor executor = new FederatedExecutor();
        assertThat(executor.join(
                List.of(new Row("x", "orders", 1)),
                List.of(new Row("y", "payments", 2)))).isEmpty();
    }

    @Test
    void joinResultCombinesValues() {
        FederatedExecutor executor = new FederatedExecutor();
        List<JoinRow> joined = executor.join(
                List.of(new Row("k", "orders", 5)),
                List.of(new Row("k", "payments", 7)));
        assertThat(joined.get(0).left()).isEqualTo(5);
        assertThat(joined.get(0).right()).isEqualTo(7);
    }

    @Test
    void nullJoinRowsRejected() {
        FederatedExecutor executor = new FederatedExecutor();
        assertThatThrownBy(() -> executor.join(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.join(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void joinSortsByKey() {
        FederatedExecutor executor = new FederatedExecutor();
        List<JoinRow> joined = executor.join(
                List.of(new Row("c", "orders", 3),
                        new Row("a", "orders", 1),
                        new Row("b", "orders", 2)),
                List.of(new Row("a", "payments", 1),
                        new Row("b", "payments", 2),
                        new Row("c", "payments", 3)));
        assertThat(joined).extracting(JoinRow::key)
                .containsExactly("a", "b", "c");
    }

    @ParameterizedTest(name = "values {0}")
    @CsvSource({"1,2,3", "10,20,30", "100,200,300"})
    void parameterizedSums(double a, double b, double c) {
        AggregateResult result = new FederatedExecutor().execute(
                plan("revenue", Aggregate.SUM, 3),
                shard -> new ShardResult(shard.domainId(),
                        switch (shard.domainId()) {
                            case "d0" -> a;
                            case "d1" -> b;
                            default -> c;
                        }, 1));
        assertThat(result.value()).isEqualTo(a + b + c);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100, 1000})
    void parameterizedCounts(int count) {
        AggregateResult result = new FederatedExecutor().execute(
                plan("events", Aggregate.COUNT, 2),
                shard -> new ShardResult(shard.domainId(), 0, count));
        assertThat(result.value()).isEqualTo(2L * count);
    }

    private static Plan plan(String metric, Aggregate aggregate,
                             int shardCount) {
        List<Shard> shards = new java.util.ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            shards.add(new Shard("d" + i, metric));
        }
        return new Plan(metric, aggregate, shards);
    }
}
