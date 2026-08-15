package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;
import io.tieringkv.storage.types.SetCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.protocol.RespSet;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.session.ConnectionContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Set 命令族（ADR-0279）。 */
public final class SetFamilyCommand implements Command {

    private final String name;
    private final Random random = new Random();

    public SetFamilyCommand(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        return switch (name) {
            case "sadd" -> sadd(args, storage);
            case "srem" -> srem(args, storage);
            case "sismember" -> sismember(args, storage);
            case "scard" -> scard(args, storage);
            case "smembers" -> smembers(args, storage);
            case "spop" -> spop(args, storage);
            case "srandmember" -> srandmember(args, storage);
            case "sinter" -> setOp(args, storage, "sinter");
            case "sunion" -> setOp(args, storage, "sunion");
            case "sdiff" -> setOp(args, storage, "sdiff");
            case "sinterstore" -> setOpStore(args, storage,
                    "sinter");
            case "sunionstore" -> setOpStore(args, storage,
                    "sunion");
            case "sdiffstore" -> setOpStore(args, storage,
                    "sdiff");
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue sadd(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        Set<ByteArrayKey> members = decode(current);
                        for (int i = 1; i < args.size(); i++) {
                            members.add(new ByteArrayKey(args.get(i)));
                        }
                        return TypedValueCodec.encode(
                                ValueType.SET,
                                SetCodec.encode(members));
                    });
            return new RespInteger(decode(result).size());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue srem(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            Set<ByteArrayKey> before = decode(storage.get(key));
            TypeSupport.update(storage, key, current -> {
                Set<ByteArrayKey> members = decode(current);
                for (int i = 1; i < args.size(); i++) {
                    members.remove(new ByteArrayKey(args.get(i)));
                }
                return members.isEmpty() ? null
                        : TypedValueCodec.encode(ValueType.SET,
                        SetCodec.encode(members));
            });
            Set<ByteArrayKey> after = new LinkedHashSet<>();
            byte[] value = storage.get(key);
            if (value != null) {
                after.addAll(decode(value));
            }
            long removed = before.stream()
                    .filter(member -> !after.contains(member))
                    .count();
            return new RespInteger(removed);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue sismember(List<byte[]> args,
                                StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            return new RespInteger(decode(storage.get(args.get(0)))
                    .contains(new ByteArrayKey(args.get(1)))
                    ? 1 : 0);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue scard(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name);
        }
        try {
            return new RespInteger(decode(storage.get(args.get(0)))
                    .size());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue smembers(List<byte[]> args,
                               StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name);
        }
        try {
            Set<ByteArrayKey> members =
                    decode(storage.get(args.get(0)));
            ConnectionContext context =
                    ConnectionContext.current();
            if (context != null
                    && context.version() == RespVersion.RESP3) {
                List<RespValue> values = new ArrayList<>();
                for (ByteArrayKey member : members) {
                    values.add(new RespBulkString(member.data()));
                }
                return new RespSet(values);
            }
            return toArray(members);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue spop(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 1 || args.size() > 2) {
            return RespError.wrongArity(name);
        }
        try {
            int count = args.size() == 2
                    ? (int) CommandUtil.parseLong(args.get(1))
                    : 1;
            if (count < 0) {
                return new RespError(
                        "ERR value is out of range, must be "
                                + "positive");
            }
            byte[] key = args.get(0);
            Set<ByteArrayKey> before = decode(storage.get(key));
            List<ByteArrayKey> removed = new ArrayList<>();
            List<ByteArrayKey> ordered =
                    new ArrayList<>(before);
            for (int i = 0; i < count && !ordered.isEmpty(); i++) {
                removed.add(ordered.remove(random.nextInt(
                        ordered.size())));
            }
            TypeSupport.update(storage, key, current -> {
                Set<ByteArrayKey> members = decode(current);
                removed.forEach(members::remove);
                return members.isEmpty() ? null
                        : TypedValueCodec.encode(ValueType.SET,
                        SetCodec.encode(members));
            });
            if (args.size() == 1) {
                return removed.isEmpty() ? RespNull.BULK_STRING
                        : new RespBulkString(
                        removed.get(0).data());
            }
            Set<ByteArrayKey> removedSet =
                    new LinkedHashSet<>(removed);
            ConnectionContext context =
                    ConnectionContext.current();
            if (context != null
                    && context.version() == RespVersion.RESP3) {
                return toSet(removedSet);
            }
            return toArray(removedSet);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue srandmember(List<byte[]> args,
                                  StorageEngine storage) {
        if (args.size() < 1 || args.size() > 2) {
            return RespError.wrongArity(name);
        }
        try {
            Set<ByteArrayKey> members =
                    decode(storage.get(args.get(0)));
            List<ByteArrayKey> ordered = new ArrayList<>(members);
            if (args.size() == 1) {
                return ordered.isEmpty() ? RespNull.BULK_STRING
                        : new RespBulkString(ordered.get(
                        random.nextInt(ordered.size())).data());
            }
            long count = CommandUtil.parseLong(args.get(1));
            List<ByteArrayKey> result = new ArrayList<>();
            if (count >= 0) {
                // 正值：抽后移除，保证结果去重（Redis 语义）
                List<ByteArrayKey> pool = new ArrayList<>(ordered);
                for (long i = 0; i < count && !pool.isEmpty(); i++) {
                    result.add(pool.remove(random.nextInt(
                            pool.size())));
                }
            } else {
                // 负值：允许重复元素
                for (long i = 0; i < -count; i++) {
                    result.add(ordered.get(random.nextInt(
                            ordered.size())));
                }
            }
            return toArray(result);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue setOp(List<byte[]> args,
                            StorageEngine storage, String op) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name);
        }
        try {
            Set<ByteArrayKey> result = computeOp(args, storage, op);
            ConnectionContext context =
                    ConnectionContext.current();
            if (context != null
                    && context.version() == RespVersion.RESP3) {
                return toSet(result);
            }
            return toArray(result);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue setOpStore(List<byte[]> args,
                                 StorageEngine storage,
                                 String op) {
        if (args.size() < 3) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] dest = args.get(0);
            List<byte[]> sources = args.subList(1, args.size());
            Set<ByteArrayKey> result = computeOp(sources,
                    storage, op);
            if (result.isEmpty()) {
                storage.delete(dest);
            } else {
                storage.put(dest, TypedValueCodec.encode(
                        ValueType.SET, SetCodec.encode(result)));
            }
            return new RespInteger(result.size());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private Set<ByteArrayKey> computeOp(
            List<byte[]> args, StorageEngine storage, String op) {
        List<Set<ByteArrayKey>> sets = new ArrayList<>();
        for (byte[] key : args) {
            sets.add(decode(storage.get(key)));
        }
        return switch (op) {
            case "sinter" -> {
                Set<ByteArrayKey> result =
                        new LinkedHashSet<>(sets.get(0));
                for (int i = 1; i < sets.size(); i++) {
                    result.retainAll(sets.get(i));
                }
                yield result;
            }
            case "sunion" -> {
                Set<ByteArrayKey> result =
                        new LinkedHashSet<>();
                for (Set<ByteArrayKey> set : sets) {
                    result.addAll(set);
                }
                yield result;
            }
            case "sdiff" -> {
                Set<ByteArrayKey> result =
                        new LinkedHashSet<>(sets.get(0));
                for (int i = 1; i < sets.size(); i++) {
                    result.removeAll(sets.get(i));
                }
                yield result;
            }
            default -> throw new AssertionError(op);
        };
    }

    static Set<ByteArrayKey> decode(byte[] value) {
        if (value == null) {
            return new LinkedHashSet<>();
        }
        if (TypedValueCodec.typeOf(value) != ValueType.SET) {
            throw TypeSupport.wrongTypeException();
        }
        return SetCodec.decode(TypedValueCodec.payload(value));
    }

    private static RespArray toArray(Set<ByteArrayKey> members) {
        List<RespValue> values = new ArrayList<>();
        for (ByteArrayKey member : members) {
            values.add(new RespBulkString(member.data()));
        }
        return new RespArray(values);
    }

    /** 保留重复元素的数组输出（SRANDMEMBER 负值语义）。 */
    private static RespArray toArray(List<ByteArrayKey> members) {
        List<RespValue> values = new ArrayList<>();
        for (ByteArrayKey member : members) {
            values.add(new RespBulkString(member.data()));
        }
        return new RespArray(values);
    }

    private static RespSet toSet(Set<ByteArrayKey> members) {
        List<RespValue> values = new ArrayList<>();
        for (ByteArrayKey member : members) {
            values.add(new RespBulkString(member.data()));
        }
        return new RespSet(values);
    }
}
