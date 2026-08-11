package io.tieringkv.transaction.metadata;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 全局事务元数据服务（ADR-0084）：命令经 Raft 提案 + 本地日志兜底，
 * 状态机可恢复，Coordinator 崩溃后可续跑。
 */
public final class TransactionMetadataService implements AutoCloseable {

    private final java.util.function.Function<byte[], CompletableFuture<Long>>
            proposer;
    private final TransactionMetadataState state = new TransactionMetadataState();
    private final Path logPath;
    private final DataOutputStream log;

    public TransactionMetadataService(
            java.util.function.Function<byte[], CompletableFuture<Long>>
                    proposer) throws IOException {
        this(proposer, null);
    }

    public TransactionMetadataService(
            java.util.function.Function<byte[], CompletableFuture<Long>>
                    proposer,
            Path logPath) throws IOException {
        this.proposer = proposer;
        this.logPath = logPath;
        this.log = logPath == null ? null : new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(logPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)));
    }

    /** 崩溃恢复：从本地日志重建状态（不重新提案）。 */
    public static TransactionMetadataService recover(
            Path logPath,
            java.util.function.Function<byte[], CompletableFuture<Long>>
                    proposer) throws IOException {
        TransactionMetadataService service =
                new TransactionMetadataService(proposer, logPath);
        for (TxnMetaCommand command : readLog(logPath)) {
            service.state.apply(command);
        }
        return service;
    }

    public TransactionMetadataState state() {
        return state;
    }

    public CompletableFuture<Void> register(
            String txnId, byte[] primary, long startTS,
            Map<String, List<TxnMessages.Mutation>> regionMutations) {
        return propose(TxnMetaCommand.register(txnId, primary, startTS,
                regionMutations));
    }

    public CompletableFuture<Void> prepare(String txnId, long commitTS) {
        return propose(TxnMetaCommand.prepare(txnId, commitTS));
    }

    public CompletableFuture<Void> commit(String txnId, long commitTS) {
        return propose(TxnMetaCommand.commit(txnId, commitTS));
    }

    public CompletableFuture<Void> rollback(String txnId) {
        return propose(TxnMetaCommand.rollback(txnId));
    }

    /** 生命周期状态持久化（ADR-0091）。 */
    public CompletableFuture<Void> recordLifecycle(
            String txnId, long startTS,
            io.tieringkv.transaction.lifecycle.TxnLifecycleState state,
            long expireAtMillis) {
        return propose(TxnMetaCommand.lifecycle(txnId, startTS,
                state.name(), expireAtMillis));
    }

    public Map<String, io.tieringkv.transaction.lifecycle.TxnLifecycleRecord>
            lifecycleSnapshot() {
        return state.lifecycleSnapshot();
    }

    private CompletableFuture<Void> propose(TxnMetaCommand command) {
        byte[] payload = TxnMetaCodec.encode(command);
        // ADR-0087：Raft-first —— 提案成功（拿到 decisionIndex）后才 apply
        // 状态并追加本地镜像；失败不产生任何本地状态（无幻影）。
        return proposer.apply(payload).thenAccept(index -> {
            TxnMetaCommand indexed = command.withDecisionIndex(index);
            state.apply(indexed);
            appendLog(TxnMetaCodec.encode(indexed));
        });
    }

    /** 直接应用远端元数据命令（ADR-0093：MetadataRuntime 侧）。 */
    public void applyLocal(TxnMetaCommand command) {
        state.apply(command.withDecisionIndex(
                System.currentTimeMillis()));
        appendLog(TxnMetaCodec.encode(command));
    }

    /** 从 Raft 已应用命令重建状态（ADR-0087）：索引=命令在日志中的位置。 */
    public static TransactionMetadataService recoverFromRaft(
            List<byte[]> raftCommands,
            java.util.function.Function<byte[], CompletableFuture<Long>>
                    proposer,
            Path logPath) throws IOException {
        TransactionMetadataService service =
                new TransactionMetadataService(proposer, logPath);
        for (int i = 0; i < raftCommands.size(); i++) {
            service.state.apply(TxnMetaCodec.decode(raftCommands.get(i))
                    .withDecisionIndex(i));
        }
        return service;
    }

    private void appendLog(byte[] payload) {
        if (log == null) {
            return;
        }
        try {
            synchronized (log) {
                log.writeInt(payload.length);
                log.write(payload);
                log.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException("metadata log append failed", e);
        }
    }

    private static List<TxnMetaCommand> readLog(Path path) throws IOException {
        List<TxnMetaCommand> commands = new ArrayList<>();
        if (!Files.exists(path)) {
            return commands;
        }
        try (InputStream raw = Files.newInputStream(path);
             DataInputStream in = new DataInputStream(
                     new BufferedInputStream(raw))) {
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    break;
                }
                if (length <= 0 || length > 1 << 20) {
                    throw new IOException("invalid metadata record length");
                }
                byte[] payload = new byte[length];
                try {
                    in.readFully(payload);
                } catch (EOFException e) {
                    break; // 尾部截断容忍
                }
                commands.add(TxnMetaCodec.decode(payload));
            }
        }
        return commands;
    }

    @Override
    public void close() throws IOException {
        if (log != null) {
            log.close();
        }
    }
}
