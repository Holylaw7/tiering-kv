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
 * 最小真实 Raft 节点（ADR-0037/0038/0039~0042）：角色切换、随机化选举、
 * 心跳、日志复制（prevLog 校验 + nextIndex 回退）、commit 与状态机 apply；
 * 日志可持久化（RaftLog）、状态可持久化（term/votedFor/commitIndex）、
 * 支持 Snapshot 压缩与 InstallSnapshot、传输可替换（本地/Netty TCP）。
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
    private final ScheduledExecutorService scheduler;
    private final RaftLog raftLog;
    private final RaftPersistentState persistentState;
    private final SnapshotManager snapshotManager;
    private final CommitNotifier commitNotifier = new CommitNotifier();

    private final Object lock = new Object();
    private final ReplicationTracker replication = new ReplicationTracker();
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
                new MemoryRaftLog(), null, null);
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
        this.id = id;
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.election = election;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.tickIntervalMillis = tickIntervalMillis;
        this.raftLog = raftLog;
        this.persistentState = persistentState;
        this.snapshotManager = snapshotManager;
        this.electionTimeoutMillis = election.nextTimeoutMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "raft-" + id);
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
                applyCommittedLocked();
            }
        }
    }

    public void start() {
        synchronized (lock) {
            running = true;
        }
        scheduler.scheduleWithFixedDelay(this::tick, tickIntervalMillis, tickIntervalMillis,
                TimeUnit.MILLISECONDS);
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
                if (entry.index() < raftLog.firstIndex()) {
                    continue; // 已被快照压缩，无需处理
                }
                if (entry.index() <= raftLog.lastIndex()) {
                    if (raftLog.termAt(entry.index()) != entry.term()) {
                        raftLog.truncateFrom(entry.index());
                        raftLog.append(entry);
                    }
                } else if (entry.index() == raftLog.lastIndex() + 1) {
                    raftLog.append(entry);
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

    /** Leader 提交命令：append → 复制 → commit → apply。 */
    public CompletableFuture<Long> propose(byte[] command) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        List<PeerCall> calls;
        LogEntry entry;
        synchronized (lock) {
            if (state != RaftState.LEADER) {
                future.completeExceptionally(new IllegalStateException("not leader"));
                return future;
            }
            entry = new LogEntry(currentTerm, raftLog.lastIndex() + 1, command);
            raftLog.append(entry);
            calls = buildReplicationLocked();
        }
        List<PeerResult> results = sendToPeers(calls);
        synchronized (lock) {
            applyReplicationResultsLocked(results);
            maybeCommitLocked();
            if (entry.index() <= commitIndex) {
                applyCommittedLocked();
                future.complete(entry.index());
            } else if (state != RaftState.LEADER) {
                future.completeExceptionally(new IllegalStateException(
                        "leadership lost before commit, term=" + currentTerm));
            } else {
                pendingCommits.put(entry.index(), future);
            }
        }
        notifyCommit();
        return future;
    }

    private final java.util.Map<Long, CompletableFuture<Long>> pendingCommits =
            new java.util.concurrent.ConcurrentHashMap<>();

    private void tick() {
        boolean shouldStartElection = false;
        List<PeerCall> calls = null;
        long electionTerm = 0;
        long lastIndex = 0;
        long lastTerm = 0;
        synchronized (lock) {
            if (!running || suspended) {
                return;
            }
            long now = System.currentTimeMillis();
            if (state == RaftState.LEADER) {
                calls = buildReplicationLocked();
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
            return;
        }
        if (calls != null) {
            List<PeerResult> results = sendToPeers(calls);
            synchronized (lock) {
                applyReplicationResultsLocked(results);
                maybeCommitLocked();
            }
            notifyCommit();
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

    private List<PeerCall> buildReplicationLocked() {
        List<PeerCall> calls = new ArrayList<>();
        for (String peer : transport.peerIds()) {
            if (peer.equals(id)) {
                continue;
            }
            long next = replication.nextIndex(peer);
            if (snapshotManager != null && snapshotManager.hasSnapshot()
                    && next <= snapshotManager.metadata().lastIncludedIndex()) {
                calls.add(new PeerCall(peer, null,
                        new InstallSnapshotRequest(currentTerm, id,
                                snapshotManager.metadata().lastIncludedIndex(),
                                snapshotManager.metadata().lastIncludedTerm(),
                                snapshotManager.data())));
                continue;
            }
            long prevIndex = next - 1;
            List<LogEntry> entries = next <= raftLog.lastIndex()
                    ? raftLog.entriesFrom(next)
                    : List.of();
            calls.add(new PeerCall(peer,
                    new AppendEntriesRequest(currentTerm, id, prevIndex,
                            prevTermLocked(prevIndex), entries, commitIndex), null));
        }
        return calls;
    }

    private long prevTermLocked(long prevIndex) {
        if (prevIndex >= raftLog.firstIndex()) {
            return raftLog.termAt(prevIndex);
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
        if (prevIndex >= raftLog.firstIndex()) {
            return raftLog.termAt(prevIndex) == prevTerm;
        }
        if (snapshotManager != null && snapshotManager.hasSnapshot()
                && prevIndex == snapshotManager.metadata().lastIncludedIndex()) {
            return snapshotManager.metadata().lastIncludedTerm() == prevTerm;
        }
        return false; // 无快照覆盖的边界索引无法校验，拒绝
    }

    private List<PeerResult> sendToPeers(List<PeerCall> calls) {
        List<PeerResult> results = new ArrayList<>(calls.size());
        for (PeerCall call : calls) {
            try {
                if (call.snapshotRequest() != null) {
                    InstallSnapshotResponse response = transport
                            .installSnapshot(call.peer(), call.snapshotRequest())
                            .get(RPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    results.add(new PeerResult(call.peer(), null, response,
                            call.snapshotRequest()));
                } else {
                    AppendEntriesResponse response = transport
                            .appendEntries(call.peer(), call.appendRequest())
                            .get(RPC_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                    results.add(new PeerResult(call.peer(), response, null, null));
                }
            } catch (Exception e) {
                results.add(new PeerResult(call.peer(),
                        new AppendEntriesResponse(0, false, 0), null, null));
            }
        }
        return results;
    }

    private void applyReplicationResultsLocked(List<PeerResult> results) {
        for (PeerResult result : results) {
            if (result.snapshotResponse() != null) {
                if (result.snapshotResponse().term() > currentTerm) {
                    stepDownLocked(result.snapshotResponse().term());
                    return;
                }
                if (result.snapshotResponse().success()) {
                    replication.onSuccess(result.peer(),
                            result.snapshotRequest().lastIncludedIndex());
                } else {
                    replication.onFailure(result.peer());
                }
            } else {
                if (result.appendResponse().term() > currentTerm) {
                    stepDownLocked(result.appendResponse().term());
                    return;
                }
                if (result.appendResponse().success()) {
                    replication.onSuccess(result.peer(), result.appendResponse().matchIndex());
                } else {
                    replication.onFailure(result.peer());
                }
            }
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
            if (raftLog.termAt(index) != currentTerm) {
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
                || commitIndex < raftLog.firstIndex()) {
            return;
        }
        long snapshotIndex = commitIndex;
        long snapshotTerm = raftLog.termAt(snapshotIndex);
        if (snapshotManager.create(snapshotIndex, snapshotTerm)) {
            raftLog.installSnapshot(snapshotIndex);
        }
    }

    private void applyCommittedLocked() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = raftLog.entryAt(lastApplied);
            stateMachine.accept(entry.index(), entry.command());
            CompletableFuture<Long> pending = pendingCommits.remove(entry.index());
            if (pending != null) {
                pending.complete(entry.index());
            }
        }
    }

    /** 提交后立即补发 commitIndex（ADR-0042），锁外发送。 */
    private void notifyCommit() {
        List<PeerCall> calls;
        synchronized (lock) {
            if (!running || suspended || state != RaftState.LEADER
                    || !commitNotifier.mark(commitIndex)) {
                return;
            }
            calls = new ArrayList<>();
            for (String peer : transport.peerIds()) {
                if (peer.equals(id)) {
                    continue;
                }
                long next = replication.nextIndex(peer);
                calls.add(new PeerCall(peer,
                        new AppendEntriesRequest(currentTerm, id, next - 1,
                                prevTermLocked(next - 1), List.of(), commitIndex), null));
            }
        }
        if (calls != null) {
            sendToPeers(calls);
        }
    }

    private void persistStateLocked(boolean force) {
        if (persistentState != null) {
            persistentState.persist(currentTerm, votedFor, commitIndex, force);
        }
    }

    private long lastLogIndex() {
        return raftLog.lastIndex();
    }

    private long lastLogTerm() {
        return raftLog.lastTerm();
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
            return raftLog.entriesFrom(raftLog.firstIndex());
        }
    }

    public ReplicationTracker replication() {
        return replication;
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

    private record PeerResult(
            String peer,
            AppendEntriesResponse appendResponse,
            InstallSnapshotResponse snapshotResponse,
            InstallSnapshotRequest snapshotRequest) {
    }
}
