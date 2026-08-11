package io.tieringkv.sql.distributed;

/** 分片执行计划（ADR-0120）：Region + key 范围。 */
public record ShardPlan(String region, byte[] startKey, byte[] endKey) {
}
