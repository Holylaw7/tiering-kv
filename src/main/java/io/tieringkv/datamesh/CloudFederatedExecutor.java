package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 跨云联邦执行（ADR-0152）：域 → 云/地域分片聚合；
 * 跨驻留边界默认拒绝（数据主权联动）。
 */
public final class CloudFederatedExecutor {

    /** 跨云分片：域 + 云 + 地域。 */
    public record CloudShard(String domainId, String cloud,
                             String metric) {
    }

    /** 单云分片结果。 */
    public record CloudResult(String domainId, String cloud,
                              double value, long count) {
    }

    /** 跨云聚合结果。 */
    public record CloudAggregate(double value, long count,
                                 Set<String> clouds) {
    }

    public enum Aggregate {
        SUM,
        COUNT,
        AVG,
        MIN,
        MAX
    }

    private final ComplianceValidator validator;
    private final DataResidencyPolicy policy;

    public CloudFederatedExecutor(ComplianceValidator validator,
                                  DataResidencyPolicy policy) {
        this.validator = validator;
        this.policy = policy;
    }

    /** 跨云聚合：先主权校验（跨驻留边界拒绝），再分片执行。 */
    public CloudAggregate execute(String coordinatorCloud,
                                  List<CloudShard> shards,
                                  Function<CloudShard, CloudResult>
                                          shardExecutor,
                                  Aggregate aggregate) {
        if (coordinatorCloud == null || shards == null
                || shardExecutor == null) {
            throw new IllegalArgumentException(
                    "coordinator, shards and executor required");
        }
        requireSingleResidency(coordinatorCloud, shards);
        List<CloudResult> results = shards.stream()
                .map(shardExecutor).toList();
        return switch (aggregate) {
            case SUM -> new CloudAggregate(
                    results.stream().mapToDouble(
                            CloudResult::value).sum(),
                    results.size(), clouds(shards));
            case COUNT -> {
                long count = results.stream().mapToLong(
                        CloudResult::count).sum();
                yield new CloudAggregate(count, count, clouds(shards));
            }
            case AVG -> {
                long count = results.stream().mapToLong(
                        CloudResult::count).sum();
                double total = results.stream()
                        .mapToDouble(result -> result.value()
                                * result.count()).sum();
                yield count == 0
                        ? new CloudAggregate(0, 0, clouds(shards))
                        : new CloudAggregate(total / count, count,
                        clouds(shards));
            }
            case MIN -> new CloudAggregate(
                    results.stream().mapToDouble(
                            CloudResult::value).min().orElse(0),
                    results.size(), clouds(shards));
            case MAX -> new CloudAggregate(
                    results.stream().mapToDouble(
                            CloudResult::value).max().orElse(0),
                    results.size(), clouds(shards));
        };
    }

    private void requireSingleResidency(String coordinatorCloud,
                                        List<CloudShard> shards) {
        Set<String> residencies = shards.stream()
                .map(shard -> policy.required(shard.cloud()))
                .collect(Collectors.toSet());
        residencies.add(policy.required(coordinatorCloud));
        if (residencies.size() > 1) {
            throw new SecurityException(
                    "cross-residency federation denied: "
                            + residencies);
        }
        for (CloudShard shard : shards) {
            validator.validate(policy, coordinatorCloud,
                    shard.cloud());
        }
    }

    private static Set<String> clouds(List<CloudShard> shards) {
        return shards.stream().map(CloudShard::cloud)
                .collect(Collectors.toUnmodifiableSet());
    }
}
