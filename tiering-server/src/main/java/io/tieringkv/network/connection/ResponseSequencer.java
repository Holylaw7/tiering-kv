package io.tieringkv.network.connection;

import io.tieringkv.protocol.RespValue;

import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 每连接响应保序器（ADR-0023 / B1 优化）：并行执行后按请求序号释放响应，
 * 保证 RESP“响应顺序 = 请求顺序”不被破坏。
 *
 * <p>仅由连接所属单线程 Netty EventLoop 访问：常规在途窗口（≤ 256）走 O(1) 零装箱
 * 环形数组槽位，仅超深 Pipeline（&gt; 256）时才懒初始化稀疏 {@link TreeMap} 溢出桶。
 */
public final class ResponseSequencer {

    private static final int RING_CAPACITY = 256;
    private static final int RING_MASK = RING_CAPACITY - 1;

    private long nextSequence = 1;
    private final RespValue[] ring = new RespValue[RING_CAPACITY];
    private final long[] ringSequences = new long[RING_CAPACITY];
    private TreeMap<Long, RespValue> overflow;

    public void complete(long sequence, RespValue response, Consumer<RespValue> writer) {
        if (sequence == nextSequence) {
            // 顺序到达热路径：直接透传，零存储、零装箱
            nextSequence++;
            writer.accept(response);
            drainReady(writer);
            return;
        }
        long offset = sequence - nextSequence;
        if (offset > 0 && offset < RING_CAPACITY) {
            int slot = (int) (sequence & RING_MASK);
            ring[slot] = response;
            ringSequences[slot] = sequence;
        } else if (offset >= RING_CAPACITY) {
            if (overflow == null) {
                overflow = new TreeMap<>();
            }
            overflow.put(sequence, response);
        }
    }

    private void drainReady(Consumer<RespValue> writer) {
        while (true) {
            int slot = (int) (nextSequence & RING_MASK);
            if (ringSequences[slot] == nextSequence) {
                RespValue ready = ring[slot];
                ring[slot] = null;
                ringSequences[slot] = 0L;
                nextSequence++;
                writer.accept(ready);
                continue;
            }
            if (overflow != null && !overflow.isEmpty()
                    && overflow.firstKey().longValue() == nextSequence) {
                RespValue ready = overflow.pollFirstEntry().getValue();
                nextSequence++;
                writer.accept(ready);
                continue;
            }
            return;
        }
    }
}
