package io.tieringkv.cdc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** CDC 消费者（ADR-0105）：从检查点后消费，应用后推进检查点。 */
public final class CDCConsumer {

    private final CdcLog log;
    private final CDCCheckpoint checkpoint;

    public CDCConsumer(Path logDir, Path checkpointDir)
            throws IOException {
        this.log = CdcLog.open(logDir);
        this.checkpoint = CDCCheckpoint.open(checkpointDir);
    }

    public synchronized long consume(Consumer<ChangeEvent> sink)
            throws IOException {
        long from = checkpoint.seq() + 1;
        List<ChangeEvent> events = log.readAll();
        long last = checkpoint.seq();
        for (ChangeEvent event : events) {
            if (event.seq() < from) {
                continue; // 已消费，幂等跳过
            }
            sink.accept(event);
            last = event.seq();
            checkpoint.advance(last);
        }
        return last;
    }

    public long checkpoint() throws IOException {
        return checkpoint.seq();
    }
}
