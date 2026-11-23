package io.tieringkv.network.connection;

import io.tieringkv.protocol.RespValue;

import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 每连接响应保序器（ADR-0023）：并行执行后按请求序号释放响应，
 * 保证 RESP"响应顺序 = 请求顺序"不被破坏。
 */
public final class ResponseSequencer {

    private long nextSequence = 1;
    private final SortedMap<Long, RespValue> pending = new TreeMap<>();

    public synchronized void complete(long sequence, RespValue response, Consumer<RespValue> writer) {
        pending.put(sequence, response);
        while (pending.containsKey(nextSequence)) {
            RespValue ready = pending.remove(nextSequence);
            nextSequence++;
            writer.accept(ready);
        }
    }
}
