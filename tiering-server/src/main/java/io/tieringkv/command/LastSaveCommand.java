package io.tieringkv.command;

import io.tieringkv.lifecycle.PhysicalSnapshotCoordinator;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;
import java.util.Objects;

/** LASTSAVE：返回最近一次物理快照成功完成的 Unix 秒级时间戳。 */
public final class LastSaveCommand implements Command {

    private final PhysicalSnapshotCoordinator coordinator;

    public LastSaveCommand(PhysicalSnapshotCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public String name() {
        return "lastsave";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(coordinator.status().lastSaveEpochSeconds());
    }
}
