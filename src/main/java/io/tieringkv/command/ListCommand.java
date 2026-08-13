package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ListCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.util.ArrayList;
import java.util.List;

/** List 命令族（ADR-0278）。 */
public final class ListCommand implements Command {

    private final String name;

    public ListCommand(String name) {
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
            case "lpush" -> push(args, storage, true);
            case "rpush" -> push(args, storage, false);
            case "lpop" -> pop(args, storage, true);
            case "rpop" -> pop(args, storage, false);
            case "llen" -> len(args, storage);
            case "lrange" -> range(args, storage);
            case "lindex" -> index(args, storage);
            case "lset" -> lset(args, storage);
            case "lrem" -> lrem(args, storage);
            case "ltrim" -> ltrim(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue push(List<byte[]> args,
                           StorageEngine storage, boolean left) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        List<byte[]> elements = decode(current);
                        for (int i = 1; i < args.size(); i++) {
                            if (left) {
                                elements.add(0, args.get(i));
                            } else {
                                elements.add(args.get(i));
                            }
                        }
                        return TypedValueCodec.encode(
                                ValueType.LIST,
                                ListCodec.encode(elements));
                    });
            return new RespInteger(decode(result).size());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue pop(List<byte[]> args,
                          StorageEngine storage, boolean left) {
        if (args.size() < 1 || args.size() > 2) {
            return RespError.wrongArity(name);
        }
        try {
            final int count;
            if (args.size() == 2) {
                count = (int) CommandUtil.parseLong(args.get(1));
                if (count < 0) {
                    return new RespError(
                            "ERR value is out of range, must "
                            + "be positive");
                }
            } else {
                count = -1;
            }
            byte[] key = args.get(0);
            List<byte[]> popped = new ArrayList<>();
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        List<byte[]> elements = decode(current);
                        if (count == -1) {
                            if (elements.isEmpty()) {
                                return null;
                            }
                            popped.add(elements.remove(left
                                    ? 0 : elements.size() - 1));
                        } else {
                            int remove = Math.min(count,
                                    elements.size());
                            for (int i = 0; i < remove; i++) {
                                popped.add(elements.remove(left
                                        ? 0
                                        : elements.size() - 1));
                            }
                        }
                        return elements.isEmpty() ? null
                                : TypedValueCodec.encode(
                                ValueType.LIST,
                                ListCodec.encode(elements));
                    });
            if (count == -1) {
                if (popped.isEmpty()) {
                    return RespNull.BULK_STRING;
                }
                return new RespBulkString(popped.get(0));
            }
            return new RespArray(popped.stream()
                    .map(RespBulkString::new)
                    .map(RespValue.class::cast)
                    .toList());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }

    private RespValue len(List<byte[]> args,
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

    private RespValue range(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            List<byte[]> elements =
                    decode(storage.get(args.get(0)));
            long start = CommandUtil.parseLong(args.get(1));
            long end = CommandUtil.parseLong(args.get(2));
            int size = elements.size();
            long from = start < 0 ? size + start : start;
            long to = end < 0 ? size + end : end;
            from = Math.max(0, from);
            to = Math.min(size - 1L, to);
            List<RespValue> values = new ArrayList<>();
            if (from <= to && from < size) {
                for (long i = from; i <= to; i++) {
                    values.add(new RespBulkString(
                            elements.get((int) i)));
                }
            }
            return new RespArray(values);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue index(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            List<byte[]> elements =
                    decode(storage.get(args.get(0)));
            long index = CommandUtil.parseLong(args.get(1));
            long actual = index < 0
                    ? elements.size() + index : index;
            if (actual < 0 || actual >= elements.size()) {
                return RespNull.BULK_STRING;
            }
            return new RespBulkString(
                    elements.get((int) actual));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue lset(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            long index = CommandUtil.parseLong(args.get(1));
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        List<byte[]> elements = decode(current);
                        long actual = index < 0
                                ? elements.size() + index : index;
                        if (actual < 0
                                || actual >= elements.size()) {
                            throw new IndexOutOfBoundsException();
                        }
                        elements.set((int) actual, args.get(2));
                        return TypedValueCodec.encode(
                                ValueType.LIST,
                                ListCodec.encode(elements));
                    });
            return new RespSimpleString("OK");
        } catch (IndexOutOfBoundsException e) {
            return new RespError(
                    "ERR index out of range");
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue lrem(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            long count = CommandUtil.parseLong(args.get(1));
            byte[] target = args.get(2);
            int before = decode(storage.get(key)).size();
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        List<byte[]> elements = decode(current);
                        int removed = 0;
                        if (count >= 0) {
                            long limit = count == 0
                                    ? elements.size() : count;
                            for (int i = 0; i < elements.size()
                                    && removed < limit; ) {
                                if (java.util.Arrays.equals(
                                        elements.get(i), target)) {
                                    elements.remove(i);
                                    removed++;
                                } else {
                                    i++;
                                }
                            }
                        } else {
                            long limit = -count;
                            for (int i = elements.size() - 1;
                                 i >= 0 && removed < limit; i--) {
                                if (java.util.Arrays.equals(
                                        elements.get(i), target)) {
                                    elements.remove(i);
                                    removed++;
                                }
                            }
                        }
                        return elements.isEmpty() ? null
                                : TypedValueCodec.encode(
                                ValueType.LIST,
                                ListCodec.encode(elements));
                    });
            List<byte[]> after = result == null
                    ? List.of() : decode(result);
            return new RespInteger(before - after.size());
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue ltrim(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            long start = CommandUtil.parseLong(args.get(1));
            long end = CommandUtil.parseLong(args.get(2));
            TypeSupport.update(storage, key, current -> {
                List<byte[]> elements = decode(current);
                int size = elements.size();
                long from = start < 0 ? size + start : start;
                long to = end < 0 ? size + end : end;
                from = Math.max(0, from);
                to = Math.min(size - 1L, to);
                if (from > to || from >= size) {
                    return null;
                }
                List<byte[]> trimmed = new ArrayList<>();
                for (long i = from; i <= to; i++) {
                    trimmed.add(elements.get((int) i));
                }
                return TypedValueCodec.encode(ValueType.LIST,
                        ListCodec.encode(trimmed));
            });
            return new RespSimpleString("OK");
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    static List<byte[]> decode(byte[] value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (TypedValueCodec.typeOf(value) != ValueType.LIST) {
            throw TypeSupport.wrongTypeException();
        }
        return ListCodec.decode(TypedValueCodec.payload(value));
    }
}
