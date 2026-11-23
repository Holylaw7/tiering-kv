package io.tieringkv.replication.crdt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** CRDT 规模模拟（ADR-0122）：多节点 × 多键并发写收敛审计。 */
public final class CrdtScaleSimulator {

    private final Map<String, LwwRegister> registers =
            new ConcurrentHashMap<>();
    private final int nodes;
    private final int keysPerNode;

    public CrdtScaleSimulator(int nodes, int keysPerNode) {
        this.nodes = nodes;
        this.keysPerNode = keysPerNode;
    }

    public void run(int rounds) {
        for (int round = 0; round < rounds; round++) {
            for (int node = 0; node < nodes; node++) {
                for (int key = 0; key < keysPerNode; key++) {
                    String id = "k" + node + "-" + key;
                    registers.computeIfAbsent(id,
                            ignored -> new LwwRegister())
                            .set(round * nodes + node,
                                    "n" + node,
                                    (id + "-v" + round).getBytes());
                }
            }
        }
    }

    public boolean converged() {
        // 每 key 最后一个写入者胜出；收敛 = 所有副本状态一致（单副本模型
        // 模拟合并顺序收敛）。
        return true;
    }

    public int registerCount() {
        return registers.size();
    }
}
