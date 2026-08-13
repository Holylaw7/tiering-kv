package io.tieringkv.benchmark.jmh;

import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JMH 核心路径基准：WAL append（ADR-0267，NO fsync 口径）。 */
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xmx1g"})
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
public class WalAppendBenchmark {

    private WALManager manager;
    private Path directory;
    private long sequence;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        directory = Files.createTempDirectory("jmh-wal");
        manager = new WALManager(new WALConfig(directory,
                64L * 1024 * 1024, WALConfig.FsyncPolicy.NO));
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        manager.close();
    }

    @Benchmark
    public void append() {
        manager.append(WALEntry.put(System.currentTimeMillis(),
                "bench-key".getBytes(StandardCharsets.UTF_8),
                "benchmark-value".getBytes(StandardCharsets.UTF_8),
                -1, sequence++));
    }
}
