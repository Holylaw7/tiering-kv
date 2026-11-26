package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 多模型工作台请求与响应 DTO（v6.2.0 C1：Vector HNSW / TimeSeries / JSON Path）。 */
public final class MultiModelDtos {

    private MultiModelDtos() {
    }

    // ==================== Vector HNSW ====================

    public record VectorCollectionSummary(String name, long count) {
    }

    public record VectorCollectionsView(
            boolean supported,
            String message,
            List<VectorCollectionSummary> collections,
            long totalVectors) {
    }

    public record VectorAddRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @Size(max = 64, message = "集合名长度不能超过 64") String collection,
            @NotBlank(message = "向量 ID 不能为空") @Size(max = 128, message = "向量 ID 长度不能超过 128") String id,
            @NotEmpty(message = "向量维度数组不能为空") @Size(max = 2048, message = "单条向量维度上限为 2048")
            List<Double> values) {
    }

    public record VectorSearchRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @Size(max = 64, message = "集合名长度不能超过 64") String collection,
            @NotEmpty(message = "查询向量不能为空") @Size(max = 2048, message = "查询向量维度上限为 2048")
            List<Double> vector,
            @Min(value = 1, message = "TopK 至少为 1") @Max(value = 200, message = "TopK 不能超过 200")
            Integer topK) {
    }

    public record VectorSearchHit(String id, double score) {
    }

    public record VectorSearchResultView(
            boolean supported,
            String message,
            String collection,
            int topK,
            long costMs,
            List<VectorSearchHit> hits) {
    }

    // ==================== TimeSeries ====================

    public record TimeSeriesPoint(long timestamp, double value) {
    }

    public record TimeSeriesSummary(String key, long length, Long latestTimestamp, Double latestValue) {
    }

    public record TimeSeriesKeysView(
            boolean supported,
            String message,
            List<TimeSeriesSummary> series) {
    }

    public record TimeSeriesAddRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @NotBlank(message = "时序键名不能为空") @Size(max = 256, message = "键名长度不能超过 256") String key,
            Long timestamp,
            @NotNull(message = "采样数值不能为空") Double value) {
    }

    public record TimeSeriesIncrByRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @NotBlank(message = "时序键名不能为空") @Size(max = 256, message = "键名长度不能超过 256") String key,
            @NotNull(message = "增量值不能为空") Double delta,
            Long timestamp) {
    }

    public record TimeSeriesRangeView(
            boolean supported,
            String message,
            String key,
            long totalLen,
            String aggregation,
            Long bucketMs,
            List<TimeSeriesPoint> points,
            Double reducedValue) {
    }

    // ==================== JSON Path ====================

    public record JsonKeysView(
            boolean supported,
            String message,
            List<String> keys) {
    }

    public record JsonQueryView(
            boolean supported,
            String message,
            boolean exists,
            String key,
            String path,
            String valueType,
            String rawJson,
            String prettyJson,
            List<String> objectKeys) {
    }

    public record JsonSetRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @NotBlank(message = "JSON 键名不能为空") @Size(max = 256, message = "键名长度不能超过 256") String key,
            @Size(max = 256, message = "路径长度不能超过 256") String path,
            @NotBlank(message = "JSON 内容不能为空") String value) {
    }

    public record JsonNumIncrRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @NotBlank(message = "JSON 键名不能为空") @Size(max = 256) String key,
            @NotBlank(message = "JSON 路径不能为空") @Size(max = 256) String path,
            @NotNull(message = "增量值不能为空") Double delta) {
    }

    public record JsonArrAppendRequest(
            @NotNull(message = "实例 ID 不能为空") Long instanceId,
            @NotBlank(message = "JSON 键名不能为空") @Size(max = 256) String key,
            @Size(max = 256) String path,
            @NotBlank(message = "追加 JSON 元素不能为空") String value) {
    }
}
