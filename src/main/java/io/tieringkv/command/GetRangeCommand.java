package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.Arrays;
import java.util.List;

/** GETRANGE key start end：字节区间（含负偏移，ADR-0269）。 */
public final class GetRangeCommand implements Command {

    @Override
    public String name() {
        return "getrange";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name());
        }
        try {
            long start = CommandUtil.parseLong(args.get(1));
            long end = CommandUtil.parseLong(args.get(2));
            byte[] value = storage.get(args.get(0));
            if (value == null) {
                return new RespBulkString(new byte[0]);
            }
            int len = value.length;
            long from = start < 0 ? len + start : start;
            long to = end < 0 ? len + end : end;
            from = Math.max(0, from);
            to = Math.min(len - 1L, to);
            if (from > to || from >= len) {
                return new RespBulkString(new byte[0]);
            }
            return new RespBulkString(Arrays.copyOfRange(value,
                    (int) from, (int) to + 1));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }
}
