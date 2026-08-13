package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;
import io.tieringkv.storage.types.HashCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.protocol.RespMap;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.session.ConnectionContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hash 命令族（ADR-0277）。 */
public final class HashCommand implements Command {

    private final String name;

    public HashCommand(String name) {
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
            case "hset" -> hset(args, storage, false);
            case "hmset" -> hset(args, storage, true);
            case "hget" -> hget(args, storage);
            case "hdel" -> hdel(args, storage);
            case "hexists" -> hexists(args, storage);
            case "hlen" -> hlen(args, storage);
            case "hkeys" -> hfields(args, storage, true);
            case "hvals" -> hfields(args, storage, false);
            case "hgetall" -> hgetall(args, storage);
            case "hmget" -> hmget(args, storage);
            case "hincrby" -> hincrby(args, storage);
            case "hsetnx" -> hsetnx(args, storage);
            case "hscan" -> hscan(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue hscan(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            long cursor = CommandUtil.parseLong(args.get(1));
            String match = null;
            int count = 10;
            for (int i = 2; i + 1 < args.size(); i += 2) {
                String option = CommandUtil.text(args.get(i))
                        .toLowerCase(java.util.Locale.ROOT);
                if ("match".equals(option)) {
                    match = CommandUtil.text(args.get(i + 1));
                } else if ("count".equals(option)) {
                    count = (int) CommandUtil.parseLong(
                            args.get(i + 1));
                }
            }
            if (count <= 0) {
                count = 10;
            }
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, args.get(0));
            List<RespValue> flat = new ArrayList<>();
            for (Map.Entry<ByteArrayKey, byte[]> entry
                    : fields.entrySet()) {
                boolean matches = match == null
                        || ScanCommand.globMatches(match,
                        entry.getKey().data());
                if (matches) {
                    flat.add(new RespBulkString(
                            entry.getKey().data()));
                    flat.add(new RespBulkString(
                            entry.getValue()));
                }
            }
            return new RespArray(List.of(
                    new RespBulkString(CommandUtil.bytes(0)),
                    new RespArray(flat)));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hset(List<byte[]> args,
                           StorageEngine storage, boolean hmset) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, key);
            int added = 0;
            for (int i = 1; i < args.size(); i += 2) {
                ByteArrayKey field = new ByteArrayKey(args.get(i));
                if (!fields.containsKey(field)) {
                    added++;
                }
                fields.put(field, args.get(i + 1));
            }
            saveHash(storage, key, fields);
            return hmset ? new RespSimpleString("OK")
                    : new RespInteger(added);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hget(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, args.get(0));
            byte[] value = fields.get(new ByteArrayKey(args.get(1)));
            return value == null ? RespNull.BULK_STRING
                    : new RespBulkString(value);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hdel(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, key);
            int removed = 0;
            for (int i = 1; i < args.size(); i++) {
                if (fields.remove(new ByteArrayKey(args.get(i)))
                        != null) {
                    removed++;
                }
            }
            saveHash(storage, key, fields);
            return new RespInteger(removed);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hexists(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, args.get(0));
            return new RespInteger(fields.containsKey(
                    new ByteArrayKey(args.get(1))) ? 1 : 0);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hlen(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name);
        }
        try {
            return new RespInteger(loadHash(storage,
                    args.get(0)).size());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hfields(List<byte[]> args,
                              StorageEngine storage,
                              boolean keys) {
        if (args.size() != 1) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, args.get(0));
            List<RespValue> values = new ArrayList<>();
            for (Map.Entry<ByteArrayKey, byte[]> entry
                    : fields.entrySet()) {
                values.add(new RespBulkString(keys
                        ? entry.getKey().data()
                        : entry.getValue()));
            }
            return new RespArray(values);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hgetall(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, args.get(0));
            ConnectionContext context = ConnectionContext.current();
            if (context != null
                    && context.version() == RespVersion.RESP3) {
                List<RespValue> pairs = new ArrayList<>(
                        fields.size() * 2);
                for (Map.Entry<ByteArrayKey, byte[]> entry
                        : fields.entrySet()) {
                    pairs.add(new RespBulkString(
                            entry.getKey().data()));
                    pairs.add(new RespBulkString(entry.getValue()));
                }
                return new RespMap(pairs);
            }
            List<RespValue> values = new ArrayList<>(
                    fields.size() * 2);
            for (Map.Entry<ByteArrayKey, byte[]> entry
                    : fields.entrySet()) {
                values.add(new RespBulkString(
                        entry.getKey().data()));
                values.add(new RespBulkString(entry.getValue()));
            }
            return new RespArray(values);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hmget(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, args.get(0));
            List<RespValue> values = new ArrayList<>();
            for (int i = 1; i < args.size(); i++) {
                byte[] value = fields.get(
                        new ByteArrayKey(args.get(i)));
                values.add(value == null ? RespNull.BULK_STRING
                        : new RespBulkString(value));
            }
            return new RespArray(values);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue hincrby(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            long delta = CommandUtil.parseLong(args.get(2));
            byte[] key = args.get(0);
            ByteArrayKey field = new ByteArrayKey(args.get(1));
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        if (current != null
                                && TypedValueCodec.typeOf(current)
                                != ValueType.HASH) {
                            throw TypeSupport
                                    .wrongTypeException();
                        }
                        Map<ByteArrayKey, byte[]> fields =
                                current == null
                                ? new LinkedHashMap<>()
                                : HashCodec.decode(
                                TypedValueCodec.payload(current));
                        byte[] existing = fields.get(field);
                        long base = existing == null ? 0
                                : CommandUtil.parseLong(existing);
                        long next = Math.addExact(base, delta);
                        fields.put(field,
                                CommandUtil.bytes(next));
                        return TypedValueCodec.encode(
                                ValueType.HASH,
                                HashCodec.encode(fields));
                    });
            Map<ByteArrayKey, byte[]> fields =
                    HashCodec.decode(TypedValueCodec.payload(result));
            return new RespInteger(CommandUtil.parseLong(
                    fields.get(field)));
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        } catch (NumberFormatException | ArithmeticException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }

    private RespValue hsetnx(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            Map<ByteArrayKey, byte[]> fields =
                    loadHash(storage, key);
            ByteArrayKey field = new ByteArrayKey(args.get(1));
            if (fields.containsKey(field)) {
                return new RespInteger(0);
            }
            fields.put(field, args.get(2));
            saveHash(storage, key, fields);
            return new RespInteger(1);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private static Map<ByteArrayKey, byte[]> loadHash(
            StorageEngine storage, byte[] key) {
        byte[] value = storage.get(key);
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (TypedValueCodec.typeOf(value) != ValueType.HASH) {
            throw TypeSupport.wrongTypeException();
        }
        return HashCodec.decode(TypedValueCodec.payload(value));
    }

    private static void saveHash(StorageEngine storage,
                                 byte[] key,
                                 Map<ByteArrayKey, byte[]> fields) {
        TypeSupport.update(storage, key, current -> {
            if (current != null
                    && TypedValueCodec.typeOf(current)
                    != ValueType.HASH) {
                throw TypeSupport.wrongTypeException();
            }
            return TypedValueCodec.encode(ValueType.HASH,
                    HashCodec.encode(fields));
        });
    }
}
