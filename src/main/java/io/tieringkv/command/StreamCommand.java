package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;
import io.tieringkv.storage.types.StreamCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stream 命令族（ADR-0292）。 */
public final class StreamCommand implements Command {

    private final String name;

    public StreamCommand(String name) {
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
            case "xadd" -> xadd(args, storage);
            case "xlen" -> xlen(args, storage);
            case "xrange" -> xrange(args, storage);
            case "xtrim" -> xtrim(args, storage);
            case "xread" -> xread(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue xadd(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 4 || (args.size() - 2) % 2 != 0) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            String id = CommandUtil.text(args.get(1));
            Map<ByteArrayKey, byte[]> fields =
                    new LinkedHashMap<>();
            for (int i = 2; i < args.size(); i += 2) {
                fields.put(new ByteArrayKey(args.get(i)),
                        args.get(i + 1));
            }
            long now = System.currentTimeMillis();
            String resultId;
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        List<StreamCodec.Entry> entries =
                                decode(current);
                        long lastMs = entries.isEmpty() ? 0
                                : entries.get(entries.size() - 1)
                                .ms();
                        long lastSeq = entries.isEmpty() ? 0
                                : entries.get(entries.size() - 1)
                                .seq();
                        long ms;
                        long seq;
                        if ("*".equals(id)) {
                            ms = now;
                            seq = ms == lastMs ? lastSeq + 1 : 0;
                        } else {
                            String[] parts = id.split("-");
                            ms = Long.parseLong(parts[0]);
                            seq = parts.length > 1
                                    ? Long.parseLong(parts[1]) : 0;
                            if (ms < lastMs
                                    || (ms == lastMs
                                    && seq <= lastSeq)) {
                                throw new InvalidStreamIdException();
                            }
                        }
                        entries.add(new StreamCodec.Entry(ms, seq,
                                fields));
                        return TypedValueCodec.encode(
                                ValueType.STREAM,
                                StreamCodec.encode(entries));
                    });
            resultId = decode(result).get(
                    decode(result).size() - 1).id();
            return new RespBulkString(
                    CommandUtil.bytes(resultId));
        } catch (InvalidStreamIdException e) {
            return new RespError("ERR The ID specified in XADD "
                    + "is equal or smaller than the target stream "
                    + "top item");
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue xlen(List<byte[]> args,
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

    private RespValue xrange(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3 && args.size() != 5) {
            return RespError.wrongArity(name);
        }
        try {
            long start = parseIdBound(args.get(1), true);
            long end = parseIdBound(args.get(2), false);
            int count = Integer.MAX_VALUE;
            if (args.size() == 5) {
                String option = CommandUtil.text(args.get(3))
                        .toLowerCase(Locale.ROOT);
                if ("count".equals(option)) {
                    count = (int) CommandUtil.parseLong(
                            args.get(4));
                }
            }
            List<RespValue> result = new ArrayList<>();
            int matched = 0;
            for (StreamCodec.Entry entry
                    : decode(storage.get(args.get(0)))) {
                long numeric = entry.ms() * 1_000_000
                        + entry.seq();
                if (numeric >= start && numeric <= end
                        && matched < count) {
                    result.add(entryValue(entry));
                    matched++;
                }
            }
            return new RespArray(result);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue xtrim(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            String option = CommandUtil.text(args.get(1))
                    .toLowerCase(Locale.ROOT);
            if (!"maxlen".equals(option)) {
                return new RespError("ERR syntax error");
            }
            long maxLen = CommandUtil.parseLong(args.get(2));
            byte[] key = args.get(0);
            int before = decode(storage.get(key)).size();
            TypeSupport.update(storage, key, current -> {
                List<StreamCodec.Entry> entries = decode(current);
                while (entries.size() > maxLen) {
                    entries.remove(0);
                }
                return TypedValueCodec.encode(ValueType.STREAM,
                        StreamCodec.encode(entries));
            });
            return new RespInteger(before - Math.min(before,
                    (int) maxLen));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue xread(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() < 3
                || !CommandUtil.text(args.get(0))
                .equalsIgnoreCase("streams")) {
            return RespError.wrongArity(name);
        }
        int keys = args.size() / 2;
        List<RespValue> streams = new ArrayList<>();
        for (int i = 1; i <= keys; i++) {
            byte[] key = args.get(i);
            String after = CommandUtil.text(args.get(i + keys));
            long threshold = parseAfter(after);
            List<RespValue> entries = new ArrayList<>();
            for (StreamCodec.Entry entry
                    : decode(storage.get(key))) {
                long numeric = entry.ms() * 1_000_000
                        + entry.seq();
                if (numeric > threshold) {
                    entries.add(entryValue(entry));
                }
            }
            streams.add(new RespArray(List.of(
                    new RespBulkString(key),
                    new RespArray(entries))));
        }
        return new RespArray(streams);
    }

    private static RespArray entryValue(StreamCodec.Entry entry) {
        List<RespValue> fields = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, byte[]> field
                : entry.fields().entrySet()) {
            fields.add(new RespBulkString(field.getKey().data()));
            fields.add(new RespBulkString(field.getValue()));
        }
        return new RespArray(List.of(
                new RespBulkString(CommandUtil.bytes(entry.id())),
                new RespArray(fields)));
    }

    private static long parseIdBound(byte[] bytes,
                                     boolean min) {
        String text = CommandUtil.text(bytes);
        if (text.equals("-")) {
            return 0;
        }
        if (text.equals("+")) {
            return Long.MAX_VALUE;
        }
        String[] parts = text.split("-");
        long ms = Long.parseLong(parts[0]);
        long seq = parts.length > 1 ? Long.parseLong(parts[1])
                : (min ? 0 : Long.MAX_VALUE / 1_000_000);
        return ms * 1_000_000 + seq;
    }

    private static long parseAfter(String id) {
        if (id.equals("$")) {
            return Long.MAX_VALUE;
        }
        String[] parts = id.split("-");
        long ms = Long.parseLong(parts[0]);
        long seq = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        return ms * 1_000_000 + seq;
    }

    static List<StreamCodec.Entry> decode(byte[] value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (TypedValueCodec.typeOf(value) != ValueType.STREAM) {
            throw TypeSupport.wrongTypeException();
        }
        return StreamCodec.decode(TypedValueCodec.payload(value));
    }

    private static final class InvalidStreamIdException
            extends RuntimeException {
    }
}
