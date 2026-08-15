package io.tieringkv.observability;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * 多模型指标（ADR-0344）：JSON/TS/多模型字节计数。
 * 命令喂数接入列为 Phase 增量（本期提供 record API）。
 */
public final class MultiModelMetricsRegistry {

    private final LongAdder jsonWrites = new LongAdder();
    private final LongAdder jsonValidationErrors = new LongAdder();
    private final LongAdder tsWrites = new LongAdder();
    private final LongAdder multimodelBytes = new LongAdder();

    public void recordJsonWrite() {
        jsonWrites.increment();
    }

    public void recordJsonValidationError() {
        jsonValidationErrors.increment();
    }

    public void recordTsWrite() {
        tsWrites.increment();
    }

    public void recordMultiModelBytes(long bytes) {
        multimodelBytes.add(bytes);
    }

    public Snapshot snapshot() {
        return new Snapshot(jsonWrites.sum(), jsonValidationErrors.sum(),
                tsWrites.sum(), multimodelBytes.sum());
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "multimodel_json_writes:%d\r\n"
                        + "multimodel_json_validation_errors:%d\r\n"
                        + "multimodel_ts_writes:%d\r\n"
                        + "multimodel_bytes:%d\r\n",
                s.jsonWrites(), s.jsonValidationErrors(),
                s.tsWrites(), s.multimodelBytes());
    }

    public record Snapshot(long jsonWrites, long jsonValidationErrors,
                           long tsWrites, long multimodelBytes) {
    }
}
