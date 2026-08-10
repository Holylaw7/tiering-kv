package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 最小真实 Raft 节点（ADR-0037~0044）：角色切换、随机化选举、心跳、
 * 批量/流水线日志复制（batch AppendEntries + inflight 上限 + group
 * commit）、commit 与状态机 apply；日志/状态可持久化，支持 Snapshot
 * 与 InstallSnapshot，传输可替换（本地/Netty TCP）。
 */
public final class RaftNode implements AutoCloseable {

    private static final long RPC_TIMEOUT_MILLIS = 3_000;
    private static final int SNAPSHOT_THRESHOLD = 1_024;

    private final String id;
    private final RaftTransport transport;
    private final BiConsumer<Long, byte[]> stateMachine;
    private final LeaderElection election;
    private final long heartbeatIntervalMillis;
    private final long tickIntervalMillis;
    private final RaftReplicationConfig replicationConfig;
    private final ReplicationController replicationController;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService flushScheduler;
    private final RaftLog raftLog;
    private final RaftPersistentState persistentState;
    private final SnapshotManager snapshotManager;
    private final CommitNotifier commitNotifier = new CommitNotifier();
    private final java.util.concurrent.atomic.AtomicLong flushCount =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile Throwable lastFlushError;

    private final Object lock = new Object();
    private final ReplicationTracker replication = new ReplicationTracker();
    private final List<LogEntry> logCache = new ArrayList<>();
    private long cacheBase;
    private RaftState state = RaftState.FOLLOWER;
    private long currentTerm;
    private String votedFor;
    private String leaderId;
    private long commitIndex = -1;
    private long lastApplied = -1;
    private long lastHeartbeat = System.currentTimeMillis();
    private long electionTimeoutMillis;
    private boolean running;
    private volatile boolean suspended;

    /** Phase 11 兼容构造：进程内传输 + 内存日志（单元测试/本地原型）。 */
    public RaftNode(
            String id,
            List<RaftNode> peers,
            BiConsumer<Long, byte[]> stateMachine,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis) {
        this(id, new LocalRaftTransport(peers, id), stateMachine, election,
                heartbeatIntervalMillis, tickIntervalMillis,
                new MemoryRaftLog(), null, null, RaftReplicationConfig.defaults());
    }

    /** 生产构造：可指定传输、持久日志、持久状态与快照管理器。 */
    public RaftNode(
            String id,
            RaftTransport transport,
            BiConsumer<Long, byte[]> stateMachine,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager) {
        this(id, transport, stateMachine, election, heartbeatIntervalMillis,
                tickIntervalMillis, raftLog, persistentState, snapshotManager,
                RaftReplicationConfig.defaults(), null);
    }

    /** 完整构造：额外指定批量复制配置（ADR-0044）。 */
    public RaftNode(
            String id,
            RaftTransport transport,
            BiConsumer<Long, byte[]> stateMachine,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager,
            RaftReplicationConfig replicationConfig) {
        this(id, transport, stateMachine, election, heartbeatIntervalMillis,
                tickIntervalMillis, raftLog, persistentState, snapshotManager,
                replicationConfig, null);
    }

    /** 完整构造：额外指定自适应复制控制器（ADR-0050）。 */
    public RaftNode(
            String id,
            RaftTransport transport,
            BiConsumer<Long, byte[]> stateMachine,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis,
            RaftLog raftLog,
            RaftPersistentState persistentState,
            SnapshotManager snapshotManager,
            RaftReplicationConfig replicationConfig,
            ReplicationController replicationController) {
        this.id = id;
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.election = election;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.tickIntervalMillis = tickIntervalMillis;
        this.replicationConfig = replicationConfig;
        this.replicationController = replicationController;
        this.raftLog = raftLog;
        this.persistentState = persistentState;
        this.snapshotManager = snapshotManager;
        this.electionTimeoutMillis = election.nextTimeoutMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "raft-" + id);
            thread.setDaemon(true);
            return thread;
        });
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "raft-flush-" + id);
            thread.setDaemon(true);
            return thread;
        });
        restorePersistentState();
    }

    /** 启动恢复：加载快照 → 恢复 term/votedFor/commitIndex → 重放剩余日志。 */
    private void restorePersistentState() {
        synchronized (lock) {
            if (snapshotManager != null && snapshotManager.hasSnapshot()) {
                long snapshotIndex = snapshotManager.metadata().lastIncludedIndex();
                raftLog.installSnapshot(snapshotIndex);
                if (commitIndex < snapshotIndex) {
                    commitIndex = snapshotIndex;
                }
                if (lastApplied < snapshotIndex) {
                    lastApplied = snapshotIndex;
                }
            }
            if (persistentState != null) {
                currentTerm = persistentState.term();
                votedFor = persistentState.votedFor();
                if (commitIndex < persistentState.commitIndex()) {
                    commitIndex = Math.min(persistentState.commitIndex(), raftLog.lastIndex());
                }
            }
            rebuildCacheLocked();
            applyCommittedLocked();
        }
    }

    private void rebuildCacheLocked() {
        logCache.clear();
        cacheBase = raftLog.firstIndex();
        logCache.addAll(raftLog.entriesFrom(cacheBase));
    }

    private void cacheAppendLocked(LogEntry entry) {
        if (logCache.isEmpty()) {
            cacheBase = entry.index();
        } else if (entry.index() <= cacheLastIndexLocked()) {
            throw new IllegalArgumentException("cache append out of order: " + entry.index());
        }
        logCache.add(entry);
    }

    private void cacheTruncateLocked(long from) {
        if (from <= cacheBase) {
            logCache.clear();
            cacheBase = from;
            return;
        }
        int keep = (int) (from - cacheBase);
        if (keep >= logCache.size()) {
            return;
        }
        logCache.subList(keep, logCache.size()).clear();
    }

    private void cacheInstallLocked(long lastIncludedIndex) {
        int drop = (int) (lastIncludedIndex - cacheBase + 1);
        if (drop >= logCache.size()) {
            logCache.clear();
        } else if (drop > 0) {
            logCache.subList(0, drop).clear();
        }
        cacheBase = lastIncludedIndex + 1;
    }

    private LogEntry cacheEntryLocked(long index) {
        return logCache.get((int) (index - cacheBase));
    }

    private long cacheLastIndexLocked() {
        return logCache.isEmpty() ? cacheBase - 1 : cacheBase + logCache.size() - 1;
    }

    private long cacheLastTermLocked() {
        return logCache.isEmpty() ? 0 : logCache.get(logCache.size() - 1).term();
    }

    public void start() {
        synchronized (lock) {
            running = true;
        }
        scheduler.scheduleWithFixedDelay(this::tick, tickIntervalMillis, tickIntervalMillis,
                TimeUnit.MILLISECONDS);
        scheduleNextFlush();
    }

    private void scheduleNextFlush() {
        long interval = replicationController != null
                ? replicationController.flushIntervalMillis()
                : replicationConfig.flushIntervalMillis();
        flushScheduler.schedule(this::flushTick, interval, TimeUnit.MILLISECONDS);
    }

    private void flushTick() {
        try {
            flushReplication();
        } finally {
            scheduleNextFlush();
        }
    }

    /** 模拟节点崩溃/不可用（测试与故障转移场景）。 */
    public void suspend() {
        synchronized (lock) {
            suspended = true;
            if (state == RaftState.LEADER) {
                state = RaftState.FOLLOWER;
            }
        }
    }

    public void resume() {
        suspended = false;
    }

    public VoteResponse receive(VoteRequest request) {
        synchronized (lock) {
            if (suspended) {
                return new VoteResponse(currentTerm, false);
            }
            if (request.term() < currentTerm) {
                return new VoteResponse(currentTerm, false);
            }
            boolean termAdvanced = request.term() > currentTerm;
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                votedFor = null;
                if (state != RaftState.FOLLOWER) {
                    state = RaftState.FOLLOWER;
                }
            }
            boolean grant = (votedFor == null || votedFor.equals(request.candidateId()))
                    && (request.lastLogTerm() > lastLogTerm()
                    || (request.lastLogTerm() == lastLogTerm()
                    && request.lastLogIndex() >= lastLogIndex()));
            if (grant) {
                votedFor = request.candidateId();
                lastHeartbeat = System.currentTimeMillis();
            }
            persistStateLocked(termAdvanced || grant);
            return new VoteResponse(currentTerm, grant);
        }
    }

    public AppendEntriesResponse receive(AppendEntriesRequest request) {
        synchronized (lock) {
            if (suspended) {
                return new AppendEntriesResponse(currentTerm, false, 0);
            }
            if (request.term() < currentTerm) {
                return new AppendEntriesResponse(currentTerm, false, 0);
            }
            boolean termAdvanced = request.term() > currentTerm;
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                votedFor = null;
            }
            state = RaftState.FOLLOWER;
            leaderId = request.leaderId();
            lastHeartbeat = System.currentTimeMillis();
            electionTimeoutMillis = election.nextTimeoutMillis();

            if (request.prevLogIndex() > lastLogIndex()) {
                persistStateLocked(termAdvanced);
                return new AppendEntriesResponse(currentTerm, false, 0);
            }
            if (!prevLogMatchesLocked(request.prevLogIndex(), request.prevLogTerm())) {
                persistStateLocked(termAdvanced);
                return new AppendEntriesResponse(currentTerm, false, 0);
            }
            for (LogEntry entry : request.entries()) {
                if (entry.index() < cacheBase) {
                    continue; // 已被快照压缩，无需处理
                }
                if (entry.index() <= cacheLastIndexLocked()) {
                    if (cacheEntryLocked(entry.index()).term() != entry.term()) {
                        raftLog.truncateFrom(entry.index());
                        cacheTruncateLocked(entry.index());
                        raftLog.append(entry);
                        cacheAppendLocked(entry);
                        // 截断的未提交提案必须失败，禁止被新条目虚假完成
                        failPendingFromLocked(entry.index());
                    }
                } else if (entry.index() == cacheLastIndexLocked() + 1) {
                    raftLog.append(entry);
                    cacheAppendLocked(entry);
                } else {
                    persistStateLocked(termAdvanced);
                    return new AppendEntriesResponse(currentTerm, false, 0);
                }
            }
            if (request.leaderCommit() > commitIndex) {
                commitIndex = Math.min(request.leaderCommit(), lastLogIndex());
                applyCommittedLocked();
            }
            persistStateLocked(termAdvanced);
            return new AppendEntriesResponse(currentTerm, true, lastLogIndex());
        }
    }

    public InstallSnapshotResponse receive(InstallSnapshotRequest request) {
        synchronized (lock) {
            if (suspended) {
                return new InstallSnapshotResponse(currentTerm, false);
            }
            if (request.term() < currentTerm) {
                return new InstallSnapshotResponse(currentTerm, false);
            }
            boolean termAdvanced = request.term() > currentTerm;
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                votedFor = null;
            }
            state = RaftState.FOLLOWER;
            leaderId = request.leaderId();
            lastHeartbeat = System.currentTimeMillis();
            electionTimeoutMillis = election.nextTimeoutMillis();
            if (snapshotManager == null
                    || !snapshotManager.install(request.lastIncludedIndex(),
                    request.lastIncludedTerm(), request.data())) {
                return new InstallSnapshotResponse(currentTerm, false);
            }
            raftLog.installSnapshot(request.lastIncludedIndex());
            cacheInstallLocked(request.lastIncludedIndex());
            if (commitIndex < request.lastIncludedIndex()) {
                commitIndex = request.lastIncludedIndex();
            }
            if (lastApplied < request.lastIncludedIndex()) {
                lastApplied = request.lastIncludedIndex();
            }
            persistStateLocked(termAdvanced);
            return new InstallSnapshotResponse(currentTerm, true);
        }
    }

    /**
     * Leader 提交命令：append 日志 → 批量复制流水线（异步）→ 多数派 ack
     * → commit → apply → 完成 future。
     */
    public CompletableFuture<Long> propose(byte[] command) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        boolean batchReady;
        boolean solo;
        synchronized (lock) {
            if (state != RaftState.LEADER) {
                future.completeExceptionally(new IllegalStateException("not leader"));
                return future;
            }
            LogEntry entry = new LogEntry(currentTerm, lastLogIndex() + 1, command);
            raftLog.append(entry);
            cacheAppendLocked(entry);
            pendingCommits.put(entry.index(), future);
            batchReady = batchFullLocked() || idlePeerLocked();
            solo = transport.peerIds().size() <= 1;
            if (solo) {
                // 无 peers：无异步响应可触发提交，直接走 commit 路径
                maybeCommitLocked();
            }
        }
        if (batchReady && !solo) {
            flushReplication();
        }
        return future;
    }

    /**
     * 批量提案（ADR-0054）：一次加锁追加 N 条日志、单次复制 flush，
     * 全部提交后按序完成 futures；非 leader 时整批显式失败。
     * 复制与提交语义与单条 propose 完全一致，不修改共识协议。
     */
    public List<CompletableFuture<Long>> proposeBatch(List<byte[]> commands) {
        if (commands.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Long>> futures = new ArrayList<>(commands.size());
        boolean batchReady;
        boolean solo;
        synchronized (lock) {
            if (state != RaftState.LEADER) {
                for (int i = 0; i < commands.size(); i++) {
                    CompletableFuture<Long> future = new CompletableFuture<>();
                    future.completeExceptionally(
                            new IllegalStateException("not leader"));
                    futures.add(future);
                }
                return futures;
            }
            for (byte[] command : commands) {
                LogEntry entry = new LogEntry(currentTerm, lastLogIndex() + 1, command);
                raftLog.append(entry);
                cacheAppendLocked(entry);
                CompletableFuture<Long> future = new CompletableFuture<>();
                pendingCommits.put(entry.index(), future);
                futures.add(future);
            }
            batchReady = batchFullLocked() || idlePeerLocked();
            solo = transport.peerIds().size() <= 1;
            if (solo) {
                maybeCommitLocked();
            }
        }
        if (batchReady && !solo) {
            flushReplication();
        }
        return futures;
    }

    /**
     * 真实领导权交接（ADR-0064）：仅当 target 日志追平时发送 TimeoutNow，
     * 目标立即发起选举；返回是否被接受（目标离线/滞后返回 false）。
     */
    public CompletableFuture<Boolean> transferLeadership(String target) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        boolean caughtUp;
        synchronized (lock) {
            if (state != RaftState.LEADER) {
                future.complete(false);
                return future;
            }
            if (!transport.peerIds().contains(target) || target.equals(id)) {
                future.complete(false);
                return future;
            }
            long match = replication.matchIndex(target);
            caughtUp = match >= lastLogIndex();
        }
        if (!caughtUp) {
            future.complete(false);
            return future;
        }
        transport.timeoutNow(target, new TimeoutNowRequest(currentTerm(), id))
                .whenComplete((response, error) -> {
                    if (error != null || response == null || !response.accepted()) {
                        future.complete(false);
                    } else {
                        future.complete(true);
                    }
                });
        return future;
    }

    /** TimeoutNow（ADR-0064）：term 校验后立即发起选举。 */
    public TimeoutNowResponse receiveTimeoutNow(TimeoutNowRequest request) {
        long electionTerm;
        long lastIndex;
        long lastTerm;
        synchronized (lock) {
            if (suspended) {
                return new TimeoutNowResponse(currentTerm, false);
            }
            if (request.term() < currentTerm) {
                return new TimeoutNowResponse(currentTerm, false);
            }
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                votedFor = null;
            }
            state = RaftState.CANDIDATE;
            currentTerm++;
            votedFor = id;
            lastHeartbeat = System.currentTimeMillis();
            electionTimeoutMillis = election.nextTimeoutMillis();
            electionTerm = currentTerm;
            lastIndex = lastLogIndex();
            lastTerm = lastLogTerm();
            persistStateLocked(true);
        }
        long term = electionTerm;
        scheduler.execute(() -> startElection(term, lastIndex, lastTerm));
        return new TimeoutNowResponse(electionTerm, true);
    }

    private final java.util.Map<Long, CompletableFuture<Long>> pendingCommits =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void tick() {
        boolean shouldStartElection = false;
        boolean heartbeatDue = false;
        long electionTerm = 0;
        long lastIndex = 0;
        long lastTerm = 0;
        synchronized (lock) {
            if (!running || suspended) {
                return;
            }
            long now = System.currentTimeMillis();
            if (state == RaftState.LEADER) {
                if (now - lastHeartbeat >= heartbeatIntervalMillis) {
                    lastHeartbeat = now;
                    heartbeatDue = true;
                }
            } else if (now - lastHeartbeat > electionTimeoutMillis) {
                shouldStartElection = true;
                currentTerm++;
                votedFor = id;
                state = RaftState.CANDIDATE;
                lastHeartbeat = now;
                electionTimeoutMillis = this.election.nextTimeoutMillis();
                electionTerm = currentTerm;
                lastIndex = lastLogIndex();
                lastTerm = lastLogTerm();
                persistStateLocked(true);
            }
        }
        if (shouldStartElection) {
            startElection(electionTerm, lastIndex, lastTerm);
            return;
        }
        if (heartbeatDue) {
            sendHeartbeats();
        }
    }

    private void startElection(long electionTerm, long lastIndex, long lastTerm) {
        VoteRequest request = new VoteRequest(electionTerm, id, lastIndex, lastTerm);
        List<VoteResponse> responses = new ArrayList<>();
        for (String peer : transport.peerIds()) {
            if (peer.equals(id)) {
                continue;
            }
            try {
                responses.add(transport.requestVote(peer, request)
                        .get(RPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            } catch (Exception ignored) {
                // 网络失败视为未投票
            }
        }
        synchronized (lock) {
            int votes = 1;
            for (VoteResponse response : responses) {
                if (response.term() > currentTerm) {
                    currentTerm = response.term();
                    votedFor = null;
                    state = RaftState.FOLLOWER;
                    persistStateLocked(true);
                    return;
                }
                if (response.granted() && response.term() == currentTerm) {
                    votes++;
                }
            }
            if (votes > transport.peerIds().size() / 2) {
                becomeLeaderLocked();
                persistStateLocked(true);
            }
        }
    }

    private void becomeLeaderLocked() {
        state = RaftState.LEADER;
        leaderId = id;
        for (String peer : transport.peerIds()) {
            if (!peer.equals(id)) {
                replication.initialize(peer, lastLogIndex() + 1);
            }
        }
    }

    /** 批量复制：为每个有未确认新条目的 peer 构建一个请求（受 inflight 限制）。 */
    private List<PeerCall> buildBatchCallsLocked() {
        List<PeerCall> calls = new ArrayList<>();
        for (String peer : transport.peerIds()) {
            if (peer.equals(id)) {
                continue;
            }
            FollowerProgress progress = replication.progress(peer);
            if (progress == null
                    || progress.inflight() >= replicationConfig.maxInflight()) {
                continue;
            }
            long next = progress.nextIndex();
            if (next > lastLogIndex()) {
                continue;
            }
            if (progress.inflight() > 0 && progress.lastSentIndex() >= lastLogIndex()) {
                continue; // 请求在途且无新条目，避免重复发送
            }
            if (snapshotManager != null && snapshotManager.hasSnapshot()
                    && next <= snapshotManager.metadata().lastIncludedIndex()) {
                InstallSnapshotRequest snapshot = new InstallSnapshotRequest(
                        currentTerm, id,
                        snapshotManager.metadata().lastIncludedIndex(),
                        snapshotManager.metadata().lastIncludedTerm(),
                        snapshotManager.data());
                replication.onSend(peer, snapshotManager.metadata().lastIncludedIndex());
                calls.add(new PeerCall(peer, null, snapshot));
                continue;
            }
            List<LogEntry> entries = batchEntriesLocked(next);
            if (entries.isEmpty()) {
                continue;
            }
            long prevIndex = next - 1;
            AppendEntriesRequest request = new AppendEntriesRequest(
                    currentTerm, id, prevIndex, prevTermLocked(prevIndex),
                    entries, commitIndex);
            long sentUpTo = entries.get(entries.size() - 1).index();
            replication.onSend(peer, sentUpTo);
            calls.add(new PeerCall(peer, request, null));
        }
        return calls;
    }

    private List<LogEntry> batchEntriesLocked(long next) {
        List<LogEntry> entries = new ArrayList<>();
        long bytes = 0;
        int maxEntries = replicationController != null
                ? replicationController.batchSize()
                : replicationConfig.maxBatchEntries();
        for (long index = next;
             index <= lastLogIndex()
                     && entries.size() < maxEntries
                     && bytes < replicationConfig.maxBatchBytes();
             index++) {
            LogEntry entry = cacheEntryLocked(index);
            entries.add(entry);
            bytes += 32 + entry.command().length;
        }
        return entries;
    }

    private boolean batchFullLocked() {
        for (String peer : transport.peerIds()) {
            if (peer.equals(id)) {
                continue;
            }
            long next = replication.nextIndex(peer);
            if (next <= lastLogIndex()
                    && lastLogIndex() - next + 1 >= (replicationController != null
                    ? replicationController.batchSize()
                    : replicationConfig.maxBatchEntries())) {
                return true;
            }
        }
        return false;
    }

    /** 任一 peer 空闲（无 in-flight 且有未发送条目）→ 立即 flush 降低顺序写延迟。 */
    private boolean idlePeerLocked() {
        for (String peer : transport.peerIds()) {
            if (peer.equals(id)) {
                continue;
            }
            FollowerProgress progress = replication.progress(peer);
            if (progress != null && progress.inflight() == 0
                    && progress.nextIndex() <= lastLogIndex()) {
                return true;
            }
        }
        return false;
    }

    private void flushReplication() {
        try {
            List<PeerCall> calls;
            synchronized (lock) {
                if (!running || suspended || state != RaftState.LEADER) {
                    return;
                }
                calls = buildBatchCallsLocked();
            }
            for (PeerCall call : calls) {
                sendAsync(call);
            }
            flushCount.incrementAndGet();
        } catch (Throwable t) {
            lastFlushError = t;
        }
    }

    private void sendAsync(PeerCall call) {
        long sentAt = System.nanoTime();
        CompletableFuture<AppendEntriesResponse> future;
        if (call.snapshotRequest() != null) {
            future = transport.installSnapshot(call.peer(), call.snapshotRequest())
                    .thenApply(response -> new AppendEntriesResponse(
                            response.term(), response.success(),
                            call.snapshotRequest().lastIncludedIndex()));
        } else {
            future = transport.appendEntries(call.peer(), call.appendRequest());
        }
        future.orTimeout(RPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((response, error) ->
                        onReplicationResponse(call, response, error, sentAt));
    }

    private void onReplicationResponse(PeerCall call, AppendEntriesResponse response,
                                       Throwable error, long sentAt) {
        if (replicationController != null) {
            replicationController.recordRttNanos(System.nanoTime() - sentAt);
            long maxMatch = replication.matchIndexSnapshot().values().stream()
                    .mapToLong(Long::longValue).max().orElse(-1);
            replicationController.setPendingEntries(Math.max(0, lastLogIndex() - maxMatch));
        }
        AppendEntriesResponse result = response != null
                ? response : new AppendEntriesResponse(0, false, 0);
        synchronized (lock) {
            replication.onResponse(call.peer());
            applyReplicationResultLocked(call, result);
            maybeCommitLocked();
        }
        notifyCommit();
        // 不在此处同步 flush：本地传输的 future 立即完成会触发
        // 无限同步递归；由 flushScheduler（flushInterval）负责下一批发送
    }

    private void applyReplicationResultLocked(PeerCall call, AppendEntriesResponse response) {
        if (response.term() > currentTerm) {
            stepDownLocked(response.term());
            return;
        }
        if (response.success()) {
            replication.onSuccess(call.peer(), response.matchIndex());
        } else {
            replication.onFailure(call.peer());
        }
    }

    private void stepDownLocked(long newTerm) {
        currentTerm = newTerm;
        votedFor = null;
        state = RaftState.FOLLOWER;
        persistStateLocked(true);
    }

    private void maybeCommitLocked() {
        if (state != RaftState.LEADER) {
            return;
        }
        int majority = transport.peerIds().size() / 2 + 1;
        for (long index = commitIndex + 1; index <= lastLogIndex(); index++) {
            if (cacheEntryLocked(index).term() != currentTerm) {
                continue;
            }
            int matched = 1;
            for (String peer : replication.matchIndexSnapshot().keySet()) {
                if (replication.matchIndex(peer) >= index) {
                    matched++;
                }
            }
            if (matched >= majority) {
                commitIndex = index;
            }
        }
        if (commitIndex > lastApplied) {
            applyCommittedLocked();
            persistStateLocked(false);
            maybeSnapshotLocked();
        }
    }

    private void maybeSnapshotLocked() {
        if (snapshotManager == null
                || raftLog.size() < SNAPSHOT_THRESHOLD
                || commitIndex < cacheBase) {
            return;
        }
        long snapshotIndex = commitIndex;
        long snapshotTerm = cacheEntryLocked(snapshotIndex).term();
        if (snapshotManager.create(snapshotIndex, snapshotTerm)) {
            raftLog.installSnapshot(snapshotIndex);
            cacheInstallLocked(snapshotIndex);
        }
    }

    private void applyCommittedLocked() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = cacheEntryLocked(lastApplied);
            stateMachine.accept(entry.index(), entry.command());
            CompletableFuture<Long> pending = pendingCommits.remove(entry.index());
            if (pending != null) {
                pending.complete(entry.index());
            }
        }
    }

    /** 冲突截断时，index >= from 的未提交提案全部失败（Phase 15 混沌验证发现）。 */
    private void failPendingFromLocked(long fromIndex) {
        for (Long index : pendingCommits.keySet()) {
            if (index >= fromIndex) {
                CompletableFuture<Long> future = pendingCommits.remove(index);
                if (future != null) {
                    future.completeExceptionally(
                            new IllegalStateException("entry superseded"));
                }
            }
        }
    }

    /** 提交后立即补发 commitIndex（ADR-0042），异步心跳。 */
    private void notifyCommit() {
        List<PeerCall> calls;
        synchronized (lock) {
            if (!running || suspended || state != RaftState.LEADER
                    || !commitNotifier.mark(commitIndex)) {
                return;
            }
            calls = buildHeartbeatCallsLocked();
        }
        for (PeerCall call : calls) {
            sendHeartbeatAsync(call);
        }
    }

    private void sendHeartbeats() {
        List<PeerCall> calls;
        synchronized (lock) {
            if (!running || suspended || state != RaftState.LEADER) {
                return;
            }
            calls = buildHeartbeatCallsLocked();
        }
        for (PeerCall call : calls) {
            sendHeartbeatAsync(call);
        }
    }

    private List<PeerCall> buildHeartbeatCallsLocked() {
        List<PeerCall> calls = new ArrayList<>();
        for (String peer : transport.peerIds()) {
            if (peer.equals(id)) {
                continue;
            }
            long next = replication.nextIndex(peer);
            calls.add(new PeerCall(peer,
                    new AppendEntriesRequest(currentTerm, id, next - 1,
                            prevTermLocked(next - 1), List.of(), commitIndex), null));
        }
        return calls;
    }

    private void sendHeartbeatAsync(PeerCall call) {
        transport.appendEntries(call.peer(), call.appendRequest())
                .orTimeout(RPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((response, error) -> {
                    if (error != null || response == null) {
                        return;
                    }
                    if (response.term() > currentTerm()) {
                        synchronized (lock) {
                            stepDownLocked(response.term());
                        }
                        return;
                    }
                    if (!response.success()) {
                        // 日志不匹配：回退 nextIndex，使后续数据 flush 回填
                        // （Phase 16 混沌发现：新 leader 以非空日志当选后，
                        //  无新写入时滞后副本永远无法追平）
                        synchronized (lock) {
                            if (running && state == RaftState.LEADER) {
                                replication.onFailure(call.peer());
                            }
                        }
                        flushReplication();
                    }
                });
    }

    private long prevTermLocked(long prevIndex) {
        if (prevIndex >= cacheBase && prevIndex <= cacheLastIndexLocked()) {
            return cacheEntryLocked(prevIndex).term();
        }
        if (snapshotManager != null && snapshotManager.hasSnapshot()
                && prevIndex == snapshotManager.metadata().lastIncludedIndex()) {
            return snapshotManager.metadata().lastIncludedTerm();
        }
        return 0;
    }

    private boolean prevLogMatchesLocked(long prevIndex, long prevTerm) {
        if (prevIndex < 0) {
            return true; // 空日志边界
        }
        if (prevIndex >= cacheBase && prevIndex <= cacheLastIndexLocked()) {
            return cacheEntryLocked(prevIndex).term() == prevTerm;
        }
        if (snapshotManager != null && snapshotManager.hasSnapshot()
                && prevIndex == snapshotManager.metadata().lastIncludedIndex()) {
            return snapshotManager.metadata().lastIncludedTerm() == prevTerm;
        }
        return false; // 无快照覆盖的边界索引无法校验，拒绝
    }

    private void persistStateLocked(boolean force) {
        if (persistentState != null) {
            persistentState.persist(currentTerm, votedFor, commitIndex, force);
        }
    }

    private long lastLogIndex() {
        return cacheLastIndexLocked();
    }

    private long lastLogTerm() {
        return cacheLastTermLocked();
    }

    public String id() {
        return id;
    }

    public RaftState state() {
        synchronized (lock) {
            return state;
        }
    }

    public long currentTerm() {
        synchronized (lock) {
            return currentTerm;
        }
    }

    public String leaderId() {
        synchronized (lock) {
            return leaderId;
        }
    }

    /** 节点是否可参与集群活动（未被挂起且未关闭）。 */
    public boolean active() {
        synchronized (lock) {
            return running && !suspended;
        }
    }

    public long commitIndex() {
        synchronized (lock) {
            return commitIndex;
        }
    }

    public long lastApplied() {
        synchronized (lock) {
            return lastApplied;
        }
    }

    public long logSize() {
        synchronized (lock) {
            return raftLog.size();
        }
    }

    public List<LogEntry> logSnapshot() {
        synchronized (lock) {
            return List.copyOf(logCache);
        }
    }

    public ReplicationTracker replication() {
        return replication;
    }

    public long flushCount() {
        return flushCount.get();
    }

    public Throwable lastFlushError() {
        return lastFlushError;
    }

    @Override
    public void close() {
        synchronized (lock) {
            running = false;
            if (state == RaftState.LEADER) {
                state = RaftState.FOLLOWER;
            }
        }
        scheduler.shutdownNow();
        flushScheduler.shutdownNow();
        raftLog.close();
        if (persistentState != null) {
            persistentState.close();
        }
    }

    private record PeerCall(
            String peer,
            AppendEntriesRequest appendRequest,
            InstallSnapshotRequest snapshotRequest) {
    }
}
