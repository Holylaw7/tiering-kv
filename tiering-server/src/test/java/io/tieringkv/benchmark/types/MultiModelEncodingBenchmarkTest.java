package io.tieringkv.benchmark.types;

import io.tieringkv.storage.types.MultiModelCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v4 M2 多模型编码基准（ADR-0320）：JSON / 时序 / 向量编码吞吐。
 */
@Tag("benchmark")
class MultiModelEncodingBenchmarkTest {

    @Test
    void encodeThroughput() {
        int rounds = 20_000;

        String json = "{\"id\":1,\"name\":\"产品\",\"tags\":[1,2,3]}";
        long t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            MultiModelCodec.encodeJson(json);
        }
        double jsonOps = rounds / elapsed(t0);

        List<MultiModelCodec.TimePoint> points = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            points.add(new MultiModelCodec.TimePoint(
                    i * 1_000L, i * 0.5));
        }
        t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            MultiModelCodec.encodeTimeSeries(points);
        }
        double tsOps = rounds / elapsed(t0);

        float[] vector = new float[64];
        for (int i = 0; i < 64; i++) {
            vector[i] = i / 100.0f;
        }
        t0 = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            MultiModelCodec.encodeVector(vector);
        }
        double vecOps = rounds / elapsed(t0);

        System.out.printf(Locale.ROOT,
                "PHASE59-BENCH MULTI-MODEL rounds=%d "
                        + "jsonOps/s=%.0f timeseriesOps/s=%.0f "
                        + "vectorOps/s=%.0f%n",
                rounds, jsonOps, tsOps, vecOps);
    }

    private static double elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}
