package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.Mutation;

import java.util.ArrayList;
import java.util.List;

/** MSET key value [key value...]：批量写（ADR-0271）。 */
public final class MsetCommand implements Command {

    @Override
    public String name() {
        return "mset";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 2 || args.size() % 2 != 0) {
            return RespError.wrongArity(name());
        }
        List<Mutation> mutations = new ArrayList<>(args.size() / 2);
        for (int i = 0; i < args.size(); i += 2) {
            mutations.add(Mutation.put(args.get(i),
                    args.get(i + 1), StorageEngine.NO_TTL));
        }
        storage.applyBatch(new BatchWriteRequest(mutations));
        return new RespSimpleString("OK");
    }
}
