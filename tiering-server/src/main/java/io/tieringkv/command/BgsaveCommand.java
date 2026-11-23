package io.tieringkv.command;

import io.tieringkv.lifecycle.PhysicalSnapshotCoordinator;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;
import java.util.Objects;

/** BGSAVE：异步触发 WAL + 冷层 SSTable + 向量索引物理快照。 */
public final class BgsaveCommand implements Command {

    private final PhysicalSnapshotCoordinator coordinator;

    public BgsaveCommand(PhysicalSnapshotCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public String name() {
        return "bgsave";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        if (!coordinator.triggerAsync()) {
            return new RespError("ERR Background save already in progress");
        }
        return new RespSimpleString("Background saving started");
    }
}
