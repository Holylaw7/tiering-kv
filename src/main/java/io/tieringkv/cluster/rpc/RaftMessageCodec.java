package io.tieringkv.cluster.rpc;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Raft 消息二进制编解码（ADR-0041）：版本化、无 Java 序列化。 */
public final class RaftMessageCodec {

    private RaftMessageCodec() {
    }

    public static byte[] encode(VoteRequest request) {
        byte[] candidate = utf8(request.candidateId());
        ByteBuffer buffer = ByteBuffer.allocate(8 + 2 + candidate.length + 8 + 8)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(request.term());
        buffer.putShort((short) candidate.length);
        buffer.put(candidate);
        buffer.putLong(request.lastLogIndex());
        buffer.putLong(request.lastLogTerm());
        return buffer.array();
    }

    public static VoteRequest decodeVoteRequest(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long term = buffer.getLong();
        String candidate = readString(buffer);
        long lastLogIndex = buffer.getLong();
        long lastLogTerm = buffer.getLong();
        return new VoteRequest(term, candidate, lastLogIndex, lastLogTerm);
    }

    public static byte[] encode(VoteResponse response) {
        ByteBuffer buffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(response.term());
        buffer.put(response.granted() ? (byte) 1 : (byte) 0);
        return buffer.array();
    }

    public static VoteResponse decodeVoteResponse(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return new VoteResponse(buffer.getLong(), buffer.get() == 1);
    }

    public static byte[] encode(AppendEntriesRequest request) {
        byte[] leader = utf8(request.leaderId());
        int entryBytes = 0;
        for (LogEntry entry : request.entries()) {
            entryBytes += 8 + 8 + 4 + entry.command().length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(
                8 + 2 + leader.length + 8 + 8 + 8 + 4 + entryBytes)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(request.term());
        buffer.putShort((short) leader.length);
        buffer.put(leader);
        buffer.putLong(request.prevLogIndex());
        buffer.putLong(request.prevLogTerm());
        buffer.putLong(request.leaderCommit());
        buffer.putInt(request.entries().size());
        for (LogEntry entry : request.entries()) {
            buffer.putLong(entry.term());
            buffer.putLong(entry.index());
            buffer.putInt(entry.command().length);
            buffer.put(entry.command());
        }
        return buffer.array();
    }

    public static AppendEntriesRequest decodeAppendEntriesRequest(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long term = buffer.getLong();
        String leader = readString(buffer);
        long prevLogIndex = buffer.getLong();
        long prevLogTerm = buffer.getLong();
        long leaderCommit = buffer.getLong();
        int count = buffer.getInt();
        List<LogEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long entryTerm = buffer.getLong();
            long entryIndex = buffer.getLong();
            int length = buffer.getInt();
            byte[] command = new byte[length];
            buffer.get(command);
            entries.add(new LogEntry(entryTerm, entryIndex, command));
        }
        return new AppendEntriesRequest(term, leader, prevLogIndex, prevLogTerm,
                entries, leaderCommit);
    }

    public static byte[] encode(AppendEntriesResponse response) {
        ByteBuffer buffer = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(response.term());
        buffer.put(response.success() ? (byte) 1 : (byte) 0);
        buffer.putLong(response.matchIndex());
        return buffer.array();
    }

    public static AppendEntriesResponse decodeAppendEntriesResponse(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return new AppendEntriesResponse(buffer.getLong(), buffer.get() == 1, buffer.getLong());
    }

    public static byte[] encode(InstallSnapshotRequest request) {
        byte[] leader = utf8(request.leaderId());
        ByteBuffer buffer = ByteBuffer.allocate(
                8 + 2 + leader.length + 8 + 8 + 4 + request.data().length)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(request.term());
        buffer.putShort((short) leader.length);
        buffer.put(leader);
        buffer.putLong(request.lastIncludedIndex());
        buffer.putLong(request.lastIncludedTerm());
        buffer.putInt(request.data().length);
        buffer.put(request.data());
        return buffer.array();
    }

    public static InstallSnapshotRequest decodeInstallSnapshotRequest(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long term = buffer.getLong();
        String leader = readString(buffer);
        long lastIncludedIndex = buffer.getLong();
        long lastIncludedTerm = buffer.getLong();
        int length = buffer.getInt();
        byte[] data = new byte[length];
        buffer.get(data);
        return new InstallSnapshotRequest(term, leader, lastIncludedIndex,
                lastIncludedTerm, data);
    }

    public static byte[] encode(InstallSnapshotResponse response) {
        ByteBuffer buffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(response.term());
        buffer.put(response.success() ? (byte) 1 : (byte) 0);
        return buffer.array();
    }

    public static InstallSnapshotResponse decodeInstallSnapshotResponse(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        return new InstallSnapshotResponse(buffer.getLong(), buffer.get() == 1);
    }

    private static String readString(ByteBuffer buffer) {
        int length = buffer.getShort() & 0xFFFF;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
