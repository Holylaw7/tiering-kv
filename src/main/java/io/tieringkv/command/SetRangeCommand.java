package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** SETRANGE key offset value：零字节填充覆盖，保留 TTL（ADR-0269）。 */
public final class SetRangeCommand implements Command {

    @Override
    public String name() {
        return "setrange";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name());
        }
        try {
            long offset = CommandUtil.parseLong(args.get(1));
            if (offset < 0) {
                return new RespError("ERR offset is out of range");
            }
            if (offset + args.get(2).length > Integer.MAX_VALUE) {
                return new RespError(
                        "ERR string exceeds maximum allowed size");
            }
            byte[] current = storage.get(args.get(0));
            int needed = (int) offset + args.get(2).length;
            byte[] result = new byte[Math.max(needed,
                    current == null ? 0 : current.length)];
            if (current != null) {
                System.arraycopy(current, 0, result, 0,
                        current.length);
            }
            System.arraycopy(args.get(2), 0, result,
                    (int) offset, args.get(2).length);
            if (storage instanceof AtomicStringOps atomic) {
                atomic.getAndSetPreservingTtl(args.get(0), result);
            } else {
                storage.put(args.get(0), result);
            }
            return new RespInteger(result.length);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }
}
