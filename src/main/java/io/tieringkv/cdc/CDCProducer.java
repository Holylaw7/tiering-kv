package io.tieringkv.cdc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** CDC 生产者（ADR-0105）：Raft apply 旁路生成序号化事件。 */
public final class CDCProducer {

    private final CdcLog log;
    private final AtomicLong seq = new AtomicLong();

    public CDCProducer(Path logDir) throws IOException {
        this.log = CdcLog.open(logDir);
    }

    public CDCProducer(Path logDir, int maxRecords) throws IOException {
        this.log = CdcLog.open(logDir, maxRecords);
    }

    /** 同步分配 seq + 追加：并发 emit 时顺序保持。 */
    public synchronized ChangeEvent emit(ChangeEvent.EventType type,
                                         byte[] key, byte[] value,
                                         boolean deleted, String txnId,
                                         String regionId)
            throws IOException {
        ChangeEvent event = new ChangeEvent(seq.getAndIncrement(), type,
                key, value, deleted, txnId, regionId,
                System.currentTimeMillis());
        log.append(event);
        return event;
    }

    public long watermark() {
        return seq.get() - 1;
    }
}
