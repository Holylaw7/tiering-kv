package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** DEL key...：返回实际删除的键数量。 */
public final class DelCommand implements Command {

    @Override
    public String name() {
        return "del";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        long removed = 0;
        List<byte[]> seen = new java.util.ArrayList<>();
        for (byte[] key : args) {
            boolean duplicate = seen.stream().anyMatch(
                    other -> java.util.Arrays.equals(other, key));
            if (!duplicate && storage.delete(key)) {
                removed++;
                seen.add(key);
            }
        }
        return new RespInteger(removed);
    }
}
