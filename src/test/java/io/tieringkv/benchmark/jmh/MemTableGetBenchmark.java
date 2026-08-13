package io.tieringkv.benchmark.jmh;

import io.tieringkv.storage.memory.MemTable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;

/** JMH 核心路径基准：MemTable GET（ADR-0267，固定参数可复现）。 */
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xmx1g"})
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class MemTableGetBenchmark {

    @Param({"10000", "100000"})
    private int size;

    private MemTable table;

    @Setup(Level.Trial)
    public void setup() {
        table = MemTable.create();
        for (int i = 0; i < size; i++) {
            table.put(key(i), value());
        }
    }

    @Benchmark
    public byte[] get() {
        return table.get(key(size / 2));
    }

    private static byte[] key(int i) {
        return String.format("bench-key-%07d", i)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value() {
        return "benchmark-value-16b".getBytes(
                StandardCharsets.UTF_8);
    }
}
