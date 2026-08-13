package io.tieringkv.benchmark.jmh;

import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.io.MmapSSTableReader;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** JMH 核心路径基准：SSTable mmap 随机读（ADR-0267）。 */
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xmx1g"})
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class SstableRandomReadBenchmark {

    @Param({"10000", "100000"})
    private int size;

    private MmapSSTableReader reader;
    private byte[] probeKey;
    private Path directory;
    private final AtomicLong counter = new AtomicLong();

    @Setup(Level.Trial)
    public void setup() throws Exception {
        directory = Files.createTempDirectory("jmh-sst");
        try (SSTableWriter writer = new SSTableWriter(directory,
                1, size, 10, 4096)) {
            for (int i = 0; i < size; i++) {
                writer.writeEntry(new KeyValueEntry(key(i),
                        value(), 0, 0, -1, 0, false, 0));
            }
            SSTableMeta meta = writer.finish();
            reader = MmapSSTableReader.open(meta, directory);
        }
        probeKey = key(size / 2);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        reader.close();
    }

    @Benchmark
    public KeyValueEntry randomRead() throws Exception {
        long i = counter.getAndIncrement() % size;
        return reader.get(key((int) i));
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
