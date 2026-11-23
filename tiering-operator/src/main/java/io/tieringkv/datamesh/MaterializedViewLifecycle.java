package io.tieringkv.datamesh;

import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;

import java.util.ArrayList;
import java.util.List;

/** 物化视图生命周期（ADR-0181）：TTL 过期 + 归档恢复。 */
public final class MaterializedViewLifecycle {

    /** 归档快照：视图数据 + 归档时间。 */
    public record ArchivedView(String viewId, String remoteCloud,
                               double value, long count,
                               boolean stale,
                               long refreshedAtMillis,
                               long archivedAtMillis) {
    }

    /** TTL 过期判定：refreshedAt + ttl < now。 */
    public boolean expired(RemoteSnapshot snapshot, long ttlMillis,
                           long nowMillis) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "snapshot required");
        }
        if (ttlMillis < 0) {
            throw new IllegalArgumentException(
                    "ttl must be non-negative");
        }
        return snapshot.refreshedAtMillis() + ttlMillis
                < nowMillis;
    }

    /** 归档：快照导出（可恢复）。 */
    public ArchivedView archive(RemoteSnapshot snapshot,
                                long archivedAtMillis) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "snapshot required");
        }
        return new ArchivedView(snapshot.viewId(),
                snapshot.remoteCloud(), snapshot.value(),
                snapshot.count(), snapshot.stale(),
                snapshot.refreshedAtMillis(), archivedAtMillis);
    }

    /** 恢复：归档 → 快照。 */
    public RemoteSnapshot restore(ArchivedView archived) {
        if (archived == null) {
            throw new IllegalArgumentException(
                    "archived required");
        }
        return new RemoteSnapshot(archived.viewId(),
                archived.remoteCloud(), archived.value(),
                archived.count(), archived.stale(),
                archived.refreshedAtMillis());
    }

    /** 扫描过期视图（不删除，由调用方处理）。 */
    public List<String> sweep(RemoteMaterializationManager manager,
                              long ttlMillis, long nowMillis) {
        if (manager == null) {
            throw new IllegalArgumentException(
                    "manager required");
        }
        List<String> expired = new ArrayList<>();
        for (String viewId : manager.viewIds()) {
            if (expired(manager.snapshot(viewId), ttlMillis,
                    nowMillis)) {
                expired.add(viewId);
            }
        }
        return expired;
    }
}
