package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * 存储复制适配器（ADR-0037）：写经 Raft 日志复制后 apply 本地引擎；
 * 读走本地。不修改 MemTable/WAL/SSTable。
 *
 * <p>原子字符串操作（ADR-0352，TD-081）：ATOMIC 命令写入 Raft 日志，
 * 在 apply 阶段确定性执行并把结果按 log index 回传 Leader 调用方；
 * 只读 TTL/版本本地执行；update(transform) 为 Leader 本地 RMW +
 * 复制结果（transform 不可序列化，见 ADR-0352 语义边界）。
 */
public final class ReplicatedStorageEngine
        implements StorageEngine, AtomicStringOps {

    // Phase 28：全量回归负载下混沌用例偶发超过 5s；放宽到 15s
    // 仍满足 Phase 20「禁止客户端永久悬挂」要求。
    private static final long PROPOSAL_TIMEOUT_MILLIS = 15_000;
    /** ATOMIC apply 结果保留期：超时未取回的结果惰性清理（ADR-0352）。 */
    private static final long OUTCOME_RETENTION_MILLIS = 60_000;
    private static final Object NULL_RESULT = new Object();

    private final StorageEngine local;
    private final Map<Long, Outcome> atomicOutcomes = new ConcurrentHashMap<>();
    private RaftNode raft;

    private ReplicatedStorageEngine(StorageEngine local) {
        this.local = local;
    }

    public static ReplicatedStorageEngine create(
            String id,
            java.util.List<RaftNode> peers,
            StorageEngine local,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis) {
        return create(id, new io.tieringkv.cluster.raft.LocalRaftTransport(peers, id),
                local, election, heartbeatIntervalMillis, tickIntervalMillis,
                new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, null);
    }

    /** 生产构造：指定传输、持久日志、持久状态与快照。 */
    public static ReplicatedStorageEngine create(
            String id,
            RaftTransport transport,
            StorageEngine local,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager) {
        ReplicatedStorageEngine engine = new ReplicatedStorageEngine(local);
        engine.raft = new RaftNode(
                id, transport, engine::applyLocal, election,
                heartbeatIntervalMillis, tickIntervalMillis,
                raftLog, persistentState, snapshotManager);
        return engine;
    }

    public RaftNode raft() {
        return raft;
    }

    private void applyLocal(long index, byte[] command) {
        Command decoded = decode(command);
        switch (decoded.type()) {
            case PUT -> local.put(decoded.key(), decoded.value(),
                    decoded.ttlMillis());
            case DELETE -> local.delete(decoded.key());
            case ATOMIC -> applyAtomic(index, decoded);
        }
    }

    /** ATOMIC 命令在状态机 apply 阶段确定性执行（ADR-0352）。 */
    private void applyAtomic(long index, Command command) {
        AtomicStringOps atomic = atomic();
        try {
            Object result = switch (command.op()) {
                case INCREMENT -> atomic.increment(
                        command.key(), command.delta());
                case APPEND -> atomic.append(command.key(), command.value());
                case GET_SET -> atomic.getSet(
                        command.key(), command.value());
                case GET_SET_PRESERVING_TTL -> atomic.getAndSetPreservingTtl(
                        command.key(), command.value());
                case GET_DELETE -> atomic.getDelete(command.key());
                case PUT_IF_ABSENT -> atomic.putIfAbsent(
                        command.key(), command.value());
                case PERSIST -> atomic.persist(command.key());
                case EXPIRE_AT -> atomic.expireAt(
                        command.key(), command.ttlMillis());
            };
            atomicOutcomes.put(index, new Outcome(
                    result == null ? NULL_RESULT : result,
                    System.currentTimeMillis()));
        } catch (RuntimeException error) {
            // 领域错误（如 INCR 非整数）必须回传调用方，而不是让
            // pending future 永久悬挂（Phase 20 禁止客户端悬挂）。
            atomicOutcomes.put(index, new Outcome(
                    error, System.currentTimeMillis()));
        }
    }

    private AtomicStringOps atomic() {
        if (local instanceof AtomicStringOps atomic) {
            return atomic;
        }
        throw new UnsupportedOperationException(
                local.getClass().getName()
                        + " does not implement AtomicStringOps");
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        putAsync(key, value, ttlMillis).join();
    }

    /** 异步提案（ADR-0050）：返回 future，支持超时/取消与 leader 变更重试。 */
    public CompletableFuture<Void> putAsync(byte[] key, byte[] value) {
        return putAsync(key, value, NO_TTL);
    }

    public CompletableFuture<Void> putAsync(byte[] key, byte[] value, long ttlMillis) {
        byte[] command = encode(Command.put(key, value, ttlMillis));
        // 有界等待（ADR-0050）：leader 被杀/退位时未决提案必须显式失败，
        // 禁止客户端永久悬挂（Phase 20 全量回归发现 ChaosValidationTest 挂起）。
        return proposeWithRetry(command, 0)
                .orTimeout(PROPOSAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** ATOMIC 提案：apply 后按 log index 取回状态机结果（ADR-0352）。 */
    private CompletableFuture<Object> proposeAtomic(Command command) {
        purgeStaleOutcomes();
        return proposeAtomicWithRetry(command, 0)
                .orTimeout(PROPOSAL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Object> proposeAtomicWithRetry(
            Command command, int attempt) {
        CompletableFuture<Long> propose = raft.propose(encode(command));
        return propose.handle((index, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(
                        takeAtomicOutcome(index));
            }
            if (attempt < 3 && error instanceof IllegalStateException) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return proposeAtomicWithRetry(command, attempt + 1);
            }
            return CompletableFuture.<Object>failedFuture(error);
        }).thenCompose(future -> future);
    }

    private Object takeAtomicOutcome(long index) {
        Outcome outcome = atomicOutcomes.remove(index);
        if (outcome == null) {
            throw new IllegalStateException(
                    "atomic outcome missing for index " + index);
        }
        if (outcome.value() instanceof RuntimeException error) {
            throw error;
        }
        return outcome.value() == NULL_RESULT ? null : outcome.value();
    }

    /** join 后解包 CompletionException，向命令层暴露原始领域异常。 */
    private static Object joinAtomic(CompletableFuture<Object> future) {
        try {
            return future.join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    /** 超时未取回的结果按保留期惰性清理，防止长期泄漏。 */
    private void purgeStaleOutcomes() {
        long cutoff = System.currentTimeMillis() - OUTCOME_RETENTION_MILLIS;
        atomicOutcomes.entrySet().removeIf(
                e -> e.getValue().createdMillis() < cutoff);
    }

    private CompletableFuture<Void> proposeWithRetry(byte[] command, int attempt) {
        CompletableFuture<Long> propose = raft.propose(command);
        return propose.handle((index, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture((Void) null);
            }
            if (attempt < 3 && error instanceof IllegalStateException) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return proposeWithRetry(command, attempt + 1);
            }
            return CompletableFuture.<Void>failedFuture(error);
        }).thenCompose(future -> future);
    }

    @Override
    public byte[] get(byte[] key) {
        return local.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        propose(encode(Command.delete(key)));
        return true;
    }

    @Override
    public boolean exists(byte[] key) {
        return local.exists(key);
    }

    @Override
    public StorageIterator iterator() {
        return local.iterator();
    }

    @Override
    public long size() {
        return local.size();
    }

    // ---------- AtomicStringOps（ADR-0352，TD-081 关闭） ----------

    @Override
    public long increment(byte[] key, long delta) {
        atomic(); // 不支持时显式失败，禁止静默回退
        return (Long) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.INCREMENT, key, null, NO_TTL, delta)));
    }

    @Override
    public int append(byte[] key, byte[] value) {
        atomic();
        return (Integer) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.APPEND, key, value, NO_TTL, 0)));
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        atomic();
        return (byte[]) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.GET_SET, key, value, NO_TTL, 0)));
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        atomic();
        return (byte[]) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.GET_SET_PRESERVING_TTL, key, value, NO_TTL, 0)));
    }

    @Override
    public byte[] getDelete(byte[] key) {
        atomic();
        return (byte[]) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.GET_DELETE, key, null, NO_TTL, 0)));
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        atomic();
        return (Boolean) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.PUT_IF_ABSENT, key, value, NO_TTL, 0)));
    }

    @Override
    public long ttlMillis(byte[] key) {
        return atomic().ttlMillis(key);
    }

    @Override
    public boolean persist(byte[] key) {
        atomic();
        return (Boolean) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.PERSIST, key, null, NO_TTL, 0)));
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        atomic();
        return (Boolean) joinAtomic(proposeAtomic(Command.atomic(
                AtomicOp.EXPIRE_AT, key, null, expireAtMillis, 0)));
    }

    /**
     * Leader 本地 RMW + 复制结果（ADR-0352）：transform 不可序列化，
     * 在 Leader 本地状态上执行并保留 TTL，最终值经 Raft 以 PUT/DELETE
     * 确定性复制到所有副本。
     */
    @Override
    public byte[] update(byte[] key, UnaryOperator<byte[]> transform) {
        AtomicStringOps atomic = atomic();
        byte[] current = local.get(key);
        byte[] next = transform.apply(current);
        if (next == null) {
            propose(encode(Command.delete(key)));
            return null;
        }
        long ttl = current == null ? NO_TTL : atomic.ttlMillis(key);
        put(key, next, ttl);
        return next;
    }

    @Override
    public long versionOf(byte[] key) {
        return local instanceof AtomicStringOps atomic
                ? atomic.versionOf(key) : 0;
    }

    private void propose(byte[] command) {
        try {
            raft.propose(command).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("raft replication failed", e);
        }
    }

    private enum CommandType {
        PUT,
        DELETE,
        ATOMIC
    }

    private enum AtomicOp {
        INCREMENT,
        APPEND,
        GET_SET,
        GET_SET_PRESERVING_TTL,
        GET_DELETE,
        PUT_IF_ABSENT,
        PERSIST,
        EXPIRE_AT
    }

    private record Command(CommandType type, AtomicOp op, byte[] key,
                           byte[] value, long ttlMillis, long delta) {

        static Command put(byte[] key, byte[] value, long ttlMillis) {
            return new Command(CommandType.PUT, null, key, value,
                    ttlMillis, 0);
        }

        static Command delete(byte[] key) {
            return new Command(CommandType.DELETE, null, key, null,
                    NO_TTL, 0);
        }

        static Command atomic(AtomicOp op, byte[] key, byte[] value,
                              long ttlMillis, long delta) {
            return new Command(CommandType.ATOMIC, op, key, value,
                    ttlMillis, delta);
        }
    }

    private record Outcome(Object value, long createdMillis) {
    }

    private static byte[] encode(Command command) {
        byte[] valueBytes = command.value() == null
                ? new byte[0] : command.value();
        int size = 1 + 4 + command.key().length + 4 + valueBytes.length + 8;
        if (command.type() == CommandType.ATOMIC) {
            size += 1 + 8; // op 码 + delta
        }
        ByteBuffer buffer = ByteBuffer.allocate(size)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(typeCode(command.type()));
        if (command.type() == CommandType.ATOMIC) {
            buffer.put((byte) command.op().ordinal());
        }
        buffer.putInt(command.key().length);
        buffer.put(command.key());
        buffer.putInt(valueBytes.length);
        buffer.put(valueBytes);
        buffer.putLong(command.ttlMillis());
        if (command.type() == CommandType.ATOMIC) {
            buffer.putLong(command.delta());
        }
        return buffer.array();
    }

    private static byte typeCode(CommandType type) {
        return switch (type) {
            case PUT -> (byte) 1;
            case DELETE -> (byte) 2;
            case ATOMIC -> (byte) 3;
        };
    }

    private static Command decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        byte type = buffer.get();
        if (type == 3) {
            byte opCode = buffer.get();
            byte[] key = readBytes(buffer);
            byte[] value = readBytes(buffer);
            long ttl = buffer.getLong();
            long delta = buffer.getLong();
            return Command.atomic(AtomicOp.values()[opCode], key, value,
                    ttl, delta);
        }
        byte[] key = readBytes(buffer);
        byte[] value = readBytes(buffer);
        long ttl = buffer.getLong();
        return type == 1
                ? Command.put(key, value, ttl)
                : Command.delete(key);
    }

    private static byte[] readBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.getInt()];
        buffer.get(bytes);
        return bytes;
    }
}
