package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.Mutation;

import java.util.ArrayList;
import java.util.List;

/** MSETNX key value [key value...]：全不存在才写入（ADR-0271）。 */
public final class MsetNxCommand implements Command {

    @Override
    public String name() {
        return "msetnx";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 2 || args.size() % 2 != 0) {
            return RespError.wrongArity(name());
        }
        for (int i = 0; i < args.size(); i += 2) {
            if (storage.exists(args.get(i))) {
                return new RespInteger(0);
            }
        }
        List<Mutation> mutations = new ArrayList<>(args.size() / 2);
        for (int i = 0; i < args.size(); i += 2) {
            mutations.add(Mutation.put(args.get(i),
                    args.get(i + 1), StorageEngine.NO_TTL));
        }
        storage.applyBatch(new BatchWriteRequest(mutations));
        return new RespInteger(1);
    }
}
