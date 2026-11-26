package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.service.MultiModelService;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonArrAppendRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonNumIncrRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonQueryView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonSetRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesIncrByRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesPoint;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesRangeView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorCollectionsView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchResultView;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 多模型工作台接口（v6.2.0 C1：Vector HNSW / TimeSeries / JSON Path）。 */
@RestController
@RequestMapping("/api/v1/multimodel")
public class MultiModelController {

    private final MultiModelService multiModelService;

    public MultiModelController(MultiModelService multiModelService) {
        this.multiModelService = multiModelService;
    }

    // ==================== Vector HNSW ====================

    @GetMapping("/vector/collections")
    public ApiResponse<VectorCollectionsView> vectorCollections(@RequestParam Long instanceId) {
        return ApiResponse.ok(multiModelService.listVectorCollections(instanceId));
    }

    @PostMapping("/vector/add")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> addVector(@Valid @RequestBody VectorAddRequest request) {
        multiModelService.addVector(request);
        return ApiResponse.ok();
    }

    @PostMapping("/vector/search")
    public ApiResponse<VectorSearchResultView> searchVector(@Valid @RequestBody VectorSearchRequest request) {
        return ApiResponse.ok(multiModelService.searchVector(request));
    }

    @DeleteMapping("/vector/item")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Boolean> deleteVector(@RequestParam Long instanceId,
                                             @RequestParam(required = false) String collection,
                                             @RequestParam String id) {
        return ApiResponse.ok(multiModelService.deleteVector(instanceId, collection, id));
    }

    @DeleteMapping("/vector/collection")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Boolean> dropVectorCollection(@RequestParam Long instanceId,
                                                     @RequestParam String collection) {
        return ApiResponse.ok(multiModelService.dropVectorCollection(instanceId, collection));
    }

    @PostMapping("/vector/checkpoint")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<String> checkpointVector(@RequestParam Long instanceId,
                                                @RequestParam(required = false) String collection) {
        return ApiResponse.ok(multiModelService.checkpointVector(instanceId, collection));
    }

    // ==================== TimeSeries ====================

    @GetMapping("/timeseries/keys")
    public ApiResponse<TimeSeriesKeysView> timeSeriesKeys(@RequestParam Long instanceId) {
        return ApiResponse.ok(multiModelService.listTimeSeries(instanceId));
    }

    @PostMapping("/timeseries/add")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<TimeSeriesPoint> addTimeSeriesPoint(@Valid @RequestBody TimeSeriesAddRequest request) {
        return ApiResponse.ok(multiModelService.addTimeSeriesPoint(request));
    }

    @PostMapping("/timeseries/incrby")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Long> incrTimeSeries(@Valid @RequestBody TimeSeriesIncrByRequest request) {
        return ApiResponse.ok(multiModelService.incrTimeSeries(request));
    }

    @GetMapping("/timeseries/range")
    public ApiResponse<TimeSeriesRangeView> queryTimeSeriesRange(
            @RequestParam Long instanceId,
            @RequestParam String key,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String aggregation,
            @RequestParam(required = false) Long bucketMs,
            @RequestParam(required = false) Integer count) {
        return ApiResponse.ok(multiModelService.queryTimeSeriesRange(
                instanceId, key, from, to, aggregation, bucketMs, count));
    }

    // ==================== JSON Path ====================

    @GetMapping("/json/keys")
    public ApiResponse<JsonKeysView> jsonKeys(@RequestParam Long instanceId,
                                              @RequestParam(required = false) String pattern,
                                              @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(multiModelService.listJsonKeys(instanceId, pattern, limit));
    }

    @GetMapping("/json/get")
    public ApiResponse<JsonQueryView> getJson(@RequestParam Long instanceId,
                                              @RequestParam String key,
                                              @RequestParam(required = false) String path) {
        return ApiResponse.ok(multiModelService.getJson(instanceId, key, path));
    }

    @PutMapping("/json/set")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Void> setJson(@Valid @RequestBody JsonSetRequest request) {
        multiModelService.setJson(request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/json/del")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Long> deleteJson(@RequestParam Long instanceId,
                                        @RequestParam String key,
                                        @RequestParam(required = false) String path) {
        return ApiResponse.ok(multiModelService.deleteJson(instanceId, key, path));
    }

    @PostMapping("/json/numincrby")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<String> numIncrJson(@Valid @RequestBody JsonNumIncrRequest request) {
        return ApiResponse.ok(multiModelService.numIncrJson(request));
    }

    @PostMapping("/json/arrappend")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ApiResponse<Long> arrAppendJson(@Valid @RequestBody JsonArrAppendRequest request) {
        return ApiResponse.ok(multiModelService.arrAppendJson(request));
    }
}
