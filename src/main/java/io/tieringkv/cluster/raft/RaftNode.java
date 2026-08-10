package io.tieringkv.cluster.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 最小真实 Raft 节点（ADR-0037/0038）：角色切换、随机化选举、
 * 心跳、日志复制（prevLog 校验 + nextIndex 回退）、commit 与状态机 apply。
 * 原型为进程内传输 + 内存日志（网络/持久化留后续）。
 */
public final class RaftNode implements AutoCloseable {

    private final String id;
    private final List<RaftNode> peers;
    private final BiConsumer<Long, byte[]> stateMachine;
    private final LeaderElection election;
    private final long heartbeatIntervalMillis;
    private final long tickIntervalMillis;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final List<LogEntry> log = new ArrayList<>();
    private final ReplicationManager replication = new ReplicationManager();
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

    public RaftNode(
            String id,
            List<RaftNode> peers,
            BiConsumer<Long, byte[]> stateMachine,
            LeaderElection election,
            long heartbeatIntervalMillis,
            long tickIntervalMillis) {
        this.id = id;
        // 共享可变列表：允许测试/启动时先建节点后组网（节点自身会被跳过）
        this.peers = peers;
        this.stateMachine = stateMachine;
        this.election = election;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.tickIntervalMillis = tickIntervalMillis;
        this.electionTimeoutMillis = election.nextTimeoutMillis();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "raft-" + id);
            thread.setDaemon(true);
            return thread;
        });
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
            if (request.term() > currentTerm) {
                currentTerm = request.term();
                votedFor = null;
            }
            state = RaftState.FOLLOWER;
            leaderId = request.leaderId();
            lastHeartbeat = System.currentTimeMillis();
            electionTimeoutMillis = election.nextTimeoutMillis();

            if (request.prevLogIndex() > lastLogIndex()) {
                return new AppendEntriesResponse(currentTerm, false, 0);
            }
            if (request.prevLogIndex() >= 0
                    && logAt(request.prevLogIndex()).term() != request.prevLogTerm()) {
                return new AppendEntriesResponse(currentTerm, false, 0);
            }
            int index = (int) request.prevLogIndex() + 1;
            for (LogEntry entry : request.entries()) {
                if (index < log.size()) {
                    if (log.get(index).term() != entry.term()) {
                        while (log.size() > index) {
                            log.remove(log.size() - 1);
                        }
                        log.add(entry);
                    }
                } else {
                    log.add(entry);
                }
                index++;
            }
            if (request.leaderCommit() > commitIndex) {
                commitIndex = Math.min(request.leaderCommit(), lastLogIndex());
                applyCommittedLocked();
            }
            return new AppendEntriesResponse(currentTerm, true, lastLogIndex());
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
            entry = new LogEntry(currentTerm, log.size(), command);
            log.add(entry);
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
                // 提案尚未提交但已失去领导权：快速失败，避免客户端无限等待
                future.completeExceptionally(new IllegalStateException(
                        "leadership lost before commit, term=" + currentTerm));
            } else {
                pendingCommits.put(entry.index(), future);
            }
        }
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
            // 挂起节点模拟崩溃/不可达：不参与心跳、选举与复制
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
            }
        }
        if (shouldStartElection) {
            VoteRequest request = new VoteRequest(electionTerm, id, lastIndex, lastTerm);
            List<VoteResponse> responses = new ArrayList<>();
            for (RaftNode peer : peers) {
                if (peer != this) {
                    responses.add(peer.receive(request));
                }
            }
            synchronized (lock) {
                int votes = 1;
                for (VoteResponse response : responses) {
                    if (response.term() > currentTerm) {
                        currentTerm = response.term();
                        votedFor = null;
                        state = RaftState.FOLLOWER;
                        return;
                    }
                    if (response.granted() && response.term() == currentTerm) {
                        votes++;
                    }
                }
                if (votes > peers.size() / 2) {
                    becomeLeaderLocked();
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
        }
    }

    private void becomeLeaderLocked() {
        state = RaftState.LEADER;
        leaderId = id;
        for (RaftNode peer : peers) {
            if (peer == this) {
                continue;
            }
            replication.initialize(peer.id, lastLogIndex() + 1);
        }
    }

    private List<PeerCall> buildReplicationLocked() {
        List<PeerCall> calls = new ArrayList<>();
        for (RaftNode peer : peers) {
            if (peer == this) {
                continue;
            }
            long next = replication.nextIndex(peer.id);
            long prevIndex = next - 1;
            long prevTerm = prevIndex < 0 ? 0 : logAt(prevIndex).term();
            List<LogEntry> entries = next <= lastLogIndex()
                    ? List.copyOf(log.subList((int) next, log.size()))
                    : List.of();
            calls.add(new PeerCall(peer, new AppendEntriesRequest(
                    currentTerm, id, prevIndex, prevTerm, entries, commitIndex)));
        }
        return calls;
    }

    private List<PeerResult> sendToPeers(List<PeerCall> calls) {
        List<PeerResult> results = new ArrayList<>(calls.size());
        for (PeerCall call : calls) {
            results.add(new PeerResult(call.peer(), call.peer().receive(call.request())));
        }
        return results;
    }

    private void applyReplicationResultsLocked(List<PeerResult> results) {
        for (PeerResult result : results) {
            if (result.response().term() > currentTerm) {
                currentTerm = result.response().term();
                votedFor = null;
                state = RaftState.FOLLOWER;
                return;
            }
            if (result.response().success()) {
                replication.onSuccess(result.peer().id(), result.response().matchIndex());
            } else {
                replication.onFailure(result.peer().id());
            }
        }
    }

    private void maybeCommitLocked() {
        if (state != RaftState.LEADER) {
            return;
        }
        // peers 列表含自身（共享引用模型）：组大小 = peers.size()
        int majority = peers.size() / 2 + 1;
        for (long index = commitIndex + 1; index <= lastLogIndex(); index++) {
            if (logAt(index).term() != currentTerm) {
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
        applyCommittedLocked();
    }

    private void applyCommittedLocked() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry entry = logAt(lastApplied);
            stateMachine.accept(entry.index(), entry.command());
            CompletableFuture<Long> pending = pendingCommits.remove(entry.index());
            if (pending != null) {
                pending.complete(entry.index());
            }
        }
    }

    private LogEntry logAt(long index) {
        return log.get((int) index);
    }

    private long lastLogIndex() {
        return log.size() - 1;
    }

    private long lastLogTerm() {
        return log.isEmpty() ? 0 : log.get(log.size() - 1).term();
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
            return log.size();
        }
    }

    public List<LogEntry> logSnapshot() {
        synchronized (lock) {
            return List.copyOf(log);
        }
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
    }

    private record PeerCall(RaftNode peer, AppendEntriesRequest request) {
    }

    private record PeerResult(RaftNode peer, AppendEntriesResponse response) {
    }
}
