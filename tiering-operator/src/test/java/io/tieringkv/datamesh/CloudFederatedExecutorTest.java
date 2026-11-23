package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudAggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云联邦执行（ADR-0152）：跨云聚合 + 数据主权拒绝矩阵。 */
class CloudFederatedExecutorTest {

    private CloudFederatedExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new CloudFederatedExecutor(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us",
                        "gcp-us", "us",
                        "aws-eu", "eu",
                        "gcp-eu", "eu")));
    }

    @Test
    void sameResidencyCrossCloudSum() {
        CloudAggregate result = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        shard.cloud().equals("aws-us") ? 10 : 20, 1),
                Aggregate.SUM);
        assertThat(result.value()).isEqualTo(30);
        assertThat(result.clouds()).containsExactlyInAnyOrder(
                "aws-us", "gcp-us");
    }

    @Test
    void crossResidencyRejected() {
        assertThatThrownBy(() -> executor.execute("aws-us",
                shards("aws-us", "gcp-eu"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void coordinatorResidencyMismatchRejected() {
        assertThatThrownBy(() -> executor.execute("aws-eu",
                shards("aws-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void unknownCloudDefaultsRejected() {
        assertThatThrownBy(() -> executor.execute("aws-us",
                shards("aws-us", "other-cloud"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void crossCloudCount() {
        CloudAggregate result = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 0, 100),
                Aggregate.COUNT);
        assertThat(result.value()).isEqualTo(200);
    }

    @Test
    void crossCloudAverageWeighted() {
        CloudAggregate result = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        shard.cloud().equals("aws-us") ? 10 : 30,
                        shard.cloud().equals("aws-us") ? 1 : 3),
                Aggregate.AVG);
        assertThat(result.value()).isEqualTo(25);
        assertThat(result.count()).isEqualTo(4);
    }

    @Test
    void crossCloudMinMax() {
        CloudAggregate min = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        shard.cloud().equals("aws-us") ? 5 : 9, 1),
                Aggregate.MIN);
        CloudAggregate max = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        shard.cloud().equals("aws-us") ? 5 : 9, 1),
                Aggregate.MAX);
        assertThat(min.value()).isEqualTo(5);
        assertThat(max.value()).isEqualTo(9);
    }

    @Test
    void emptyShardsSumZero() {
        CloudAggregate result = executor.execute("aws-us", List.of(),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 0, 0),
                Aggregate.SUM);
        assertThat(result.value()).isZero();
        assertThat(result.clouds()).isEmpty();
    }

    @Test
    void emptyAverageZero() {
        CloudAggregate result = executor.execute("aws-us", List.of(),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 0, 0),
                Aggregate.AVG);
        assertThat(result.value()).isZero();
    }

    @Test
    void nullCoordinatorRejected() {
        assertThatThrownBy(() -> executor.execute(null,
                shards("aws-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullShardsRejected() {
        assertThatThrownBy(() -> executor.execute("aws-us", null,
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullExecutorRejected() {
        assertThatThrownBy(() -> executor.execute("aws-us",
                shards("aws-us"), null, Aggregate.SUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedAggregates(String aggregate) {
        CloudAggregate result = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 3, 1),
                Aggregate.valueOf(aggregate));
        assertThat(result.count()).isPositive();
    }

    @ParameterizedTest(name = "clouds {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedCloudCounts(int count) {
        List<CloudShard> shards = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            shards.add(new CloudShard("d" + i,
                    i % 2 == 0 ? "aws-us" : "gcp-us", "m"));
        }
        CloudAggregate result = executor.execute("aws-us", shards,
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM);
        assertThat(result.value()).isEqualTo(count);
    }

    @ParameterizedTest(name = "values {0}")
    @CsvSource({"1,2,3", "10,20,30"})
    void parameterizedSums(double a, double b, double c) {
        CloudAggregate result = executor.execute("aws-us",
                shards("aws-us", "gcp-us", "aws-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        switch (shard.domainId()) {
                            case "d0" -> a;
                            case "d1" -> b;
                            default -> c;
                        }, 1),
                Aggregate.SUM);
        assertThat(result.value()).isEqualTo(a + b + c);
    }

    @ParameterizedTest(name = "residency {0}")
    @CsvSource({"us,true", "eu,false"})
    void parameterizedResidencyMatrix(String residency, boolean allowed) {
        DataResidencyPolicy policy = new DataResidencyPolicy(Map.of(
                "aws-us", "us", "gcp-us", "us",
                "aws-eu", "eu", "gcp-eu", "eu"));
        CloudFederatedExecutor local = new CloudFederatedExecutor(
                new ComplianceValidator(), policy);
        if (!allowed) {
            assertThatThrownBy(() -> local.execute("aws-us",
                    shards("gcp-eu"),
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 1, 1),
                    Aggregate.SUM))
                    .isInstanceOf(SecurityException.class);
        } else {
            assertThat(local.execute("aws-us", shards("gcp-us"),
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 1, 1),
                    Aggregate.SUM).value()).isEqualTo(1);
        }
    }

    @Test
    void resultCarriesCloudSet() {
        CloudAggregate result = executor.execute("aws-us",
                shards("aws-us", "gcp-us"),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.COUNT);
        assertThat(result.clouds()).hasSize(2);
    }

    @Test
    void repeatedExecutionStable() {
        for (int i = 0; i < 20; i++) {
            assertThat(executor.execute("aws-us",
                    shards("aws-us", "gcp-us"),
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 1, 1),
                    Aggregate.SUM).value()).isEqualTo(2);
        }
    }

    private static List<CloudShard> shards(String... clouds) {
        List<CloudShard> shards = new java.util.ArrayList<>();
        for (int i = 0; i < clouds.length; i++) {
            shards.add(new CloudShard("d" + i, clouds[i], "m"));
        }
        return shards;
    }
}
