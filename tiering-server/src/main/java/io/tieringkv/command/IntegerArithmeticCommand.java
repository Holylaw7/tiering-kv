package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.util.List;

/** INCR/DECR/INCRBY/DECRBY 公共实现（ADR-0269）。 */
abstract class IntegerArithmeticCommand implements Command {

    private final String name;
    private final int argCount;

    IntegerArithmeticCommand(String name, int argCount) {
        this.name = name;
        this.argCount = argCount;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != argCount) {
            return RespError.wrongArity(name);
        }
        byte[] current = storage.get(args.get(0));
        if (current != null
                && TypedValueCodec.typeOf(current)
                != ValueType.STRING) {
            return TypeSupport.wrongType();
        }
        try {
            long delta = argCount == 1
                    ? fixedDelta()
                    : Math.multiplyExact(fixedDelta(),
                    CommandUtil.parseLong(args.get(1)));
            long next;
            if (storage instanceof AtomicStringOps atomic) {
                next = atomic.increment(args.get(0), delta);
            } else {
                long base = current == null ? 0
                        : CommandUtil.parseLong(current);
                next = Math.addExact(base, delta);
                storage.put(args.get(0), CommandUtil.bytes(next));
            }
            return new RespInteger(next);
        } catch (NumberFormatException | ArithmeticException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }

    long fixedDelta() {
        return 0;
    }
}
