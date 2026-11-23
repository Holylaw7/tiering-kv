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
import io.tieringkv.storage.types.StreamCodec;
import io.tieringkv.storage.types.StreamCodec.Entry;
import io.tieringkv.storage.types.StreamCodec.Group;
import io.tieringkv.storage.types.StreamCodec.Pending;
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
            case "xgroup" -> xgroup(args, storage);
            case "xreadgroup" -> xreadgroup(args, storage);
            case "xack" -> xack(args, storage);
            case "xpending" -> xpending(args, storage);
            case "xclaim" -> xclaim(args, storage, false);
            case "xautoclaim" -> xclaim(args, storage, true);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue xclaim(List<byte[]> args,
                             StorageEngine storage,
                             boolean auto) {
        if (args.size() < 4) {
            return RespError.wrongArity(name);
        }
        byte[] key = args.get(0);
        String groupName = CommandUtil.text(args.get(1));
        String consumer = CommandUtil.text(args.get(2));
        java.util.Set<String> targets = new java.util.HashSet<>();
        if (!auto) {
            for (int i = 4; i < args.size(); i++) {
                targets.add(CommandUtil.text(args.get(i)));
            }
        }
        List<RespValue> claimed = new ArrayList<>();
        TypeSupport.update(storage, key, current -> {
            StreamCodec.Decoded decoded = decodeAll(current);
            List<Group> groups = new ArrayList<>(decoded.groups());
            int gi = indexOfGroup(groups, groupName);
            if (gi < 0) {
                return current;
            }
            Group group = groups.get(gi);
            List<Pending> pending = new ArrayList<>();
            int deadLetters = group.deadLetters();
            for (Pending item : group.pending()) {
                boolean match = auto
                        ? item.ms() * 1_000_000 + item.seq()
                        >= CommandUtil.parseLong(args.get(3))
                        : targets.contains(item.ms() + "-"
                        + item.seq());
                if (match) {
                    if (item.consumer().equals(consumer)) {
                        deadLetters++;
                    }
                    pending.add(new Pending(item.ms(), item.seq(),
                            consumer));
                    for (Entry entry : decoded.entries()) {
                        if (entry.ms() == item.ms()
                                && entry.seq() == item.seq()) {
                            claimed.add(entryValue(entry));
                        }
                    }
                } else {
                    pending.add(item);
                }
            }
            groups.set(gi, new Group(group.name(), group.lastMs(),
                    group.lastSeq(), pending, deadLetters));
            return TypedValueCodec.encode(ValueType.STREAM,
                    StreamCodec.encode(decoded.entries(), groups));
        });
        return new RespArray(claimed);
    }

    private RespValue xgroup(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 4 && args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            String sub = CommandUtil.text(args.get(0))
                    .toLowerCase(Locale.ROOT);
            byte[] key = args.get(1);
            if ("destroy".equals(sub) && args.size() != 3) {
                return RespError.wrongArity(name);
            }
            if (!"destroy".equals(sub) && args.size() != 4) {
                return RespError.wrongArity(name);
            }
            String groupName = CommandUtil.text(args.get(2));
            if ("create".equals(sub)) {
                String id = CommandUtil.text(args.get(3));
                byte[] result = TypeSupport.update(storage, key,
                        current -> {
                            StreamCodec.Decoded decoded =
                                    decodeAll(current);
                            for (Group group : decoded.groups()) {
                                if (group.name()
                                        .equals(groupName)) {
                                    throw new GroupExistsException();
                                }
                            }
                            long[] last = id.equals("$")
                                    ? lastId(decoded.entries())
                                    : parseId(id);
                            List<Group> groups =
                                    new ArrayList<>(decoded.groups());
                            groups.add(new Group(groupName,
                                    last[0], last[1], List.of(), 0));
                            return TypedValueCodec.encode(
                                    ValueType.STREAM,
                                    StreamCodec.encode(
                                            decoded.entries(),
                                            groups));
                        });
                return new RespSimpleString("OK");
            }
            if ("destroy".equals(sub)) {
                boolean[] removedHolder = {false};
                TypeSupport.update(storage, key,
                        current -> {
                            StreamCodec.Decoded decoded =
                                    decodeAll(current);
                            List<Group> groups =
                                    new ArrayList<>(decoded.groups());
                            boolean removed = groups.removeIf(
                                    group -> group.name()
                                            .equals(groupName));
                            removedHolder[0] = removed;
                            if (!removed) {
                                return current;
                            }
                            return TypedValueCodec.encode(
                                    ValueType.STREAM,
                                    StreamCodec.encode(
                                            decoded.entries(),
                                            groups));
                        });
                return new RespInteger(removedHolder[0] ? 1 : 0);
            }
            return new RespError("ERR unknown XGROUP "
                    + "subcommand");
        } catch (GroupExistsException e) {
            return new RespError("BUSYGROUP Consumer Group "
                    + "name already exists");
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue xreadgroup(List<byte[]> args,
                                 StorageEngine storage) {
        if (args.size() < 6
                || !CommandUtil.text(args.get(0))
                .equalsIgnoreCase("group")) {
            return RespError.wrongArity(name);
        }
        String groupName = CommandUtil.text(args.get(1));
        String consumer = CommandUtil.text(args.get(2));
        int idx = 3;
        if (args.size() > idx
                && CommandUtil.text(args.get(idx))
                .equalsIgnoreCase("count")) {
            idx += 2;
        }
        if (idx >= args.size()
                || !CommandUtil.text(args.get(idx))
                .equalsIgnoreCase("streams")
                || args.size() - idx - 1 < 2) {
            return RespError.wrongArity(name);
        }
        int keys = (args.size() - idx - 1) / 2;
        List<RespValue> streams = new ArrayList<>();
        for (int i = 1; i <= keys; i++) {
            byte[] key = args.get(idx + i);
            String after = CommandUtil.text(
                    args.get(idx + i + keys));
            List<Entry> deliveredHolder = new ArrayList<>();
            TypeSupport.update(storage, key, current -> {
                        StreamCodec.Decoded decoded =
                                decodeAll(current);
                        List<Group> groups =
                                new ArrayList<>(decoded.groups());
                        int gi = indexOfGroup(groups, groupName);
                        if (gi < 0) {
                            throw new GroupNotFoundException();
                        }
                        Group group = groups.get(gi);
                        long fromMs = group.lastMs();
                        long fromSeq = group.lastSeq();
                        List<Entry> delivered = new ArrayList<>();
                        List<Pending> pending =
                                new ArrayList<>(group.pending());
                        for (Entry entry : decoded.entries()) {
                            long numeric = entry.ms()
                                    * 1_000_000 + entry.seq();
                            boolean afterLast = after.equals(">")
                                    ? entry.ms() > fromMs
                                    || (entry.ms() == fromMs
                                    && entry.seq() > fromSeq)
                                    : numeric > parseAfter(after);
                            if (afterLast) {
                                delivered.add(entry);
                                pending.add(new Pending(
                                        entry.ms(), entry.seq(),
                                        consumer));
                            }
                        }
                        long lastMs = group.lastMs();
                        long lastSeq = group.lastSeq();
                        if (!delivered.isEmpty()) {
                            Entry last = delivered.get(
                                    delivered.size() - 1);
                            lastMs = last.ms();
                            lastSeq = last.seq();
                        }
                        deliveredHolder.addAll(delivered);
                        groups.set(gi, new Group(groupName,
                                lastMs, lastSeq, pending,
                                group.deadLetters()));
                        return TypedValueCodec.encode(
                                ValueType.STREAM,
                                StreamCodec.encode(
                                        decoded.entries(), groups));
                    });
            List<RespValue> entries = new ArrayList<>();
            for (Entry entry : deliveredHolder) {
                entries.add(entryValue(entry));
            }
            streams.add(new RespArray(List.of(
                    new RespBulkString(key),
                    new RespArray(entries))));
        }
        return new RespArray(streams);
    }

    private RespValue xack(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 3) {
            return RespError.wrongArity(name);
        }
        byte[] key = args.get(0);
        String groupName = CommandUtil.text(args.get(1));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 2; i < args.size(); i++) {
            ids.add(CommandUtil.text(args.get(i)));
        }
        TypeSupport.update(storage, key, current -> {
            StreamCodec.Decoded decoded = decodeAll(current);
            List<Group> groups = new ArrayList<>(decoded.groups());
            int gi = indexOfGroup(groups, groupName);
            if (gi < 0) {
                return current;
            }
            Group group = groups.get(gi);
            List<Pending> pending = new ArrayList<>();
            for (Pending item : group.pending()) {
                if (!ids.contains(item.ms() + "-" + item.seq())) {
                    pending.add(item);
                }
            }
            groups.set(gi, new Group(group.name(), group.lastMs(),
                    group.lastSeq(), pending,
                    group.deadLetters()));
            return TypedValueCodec.encode(ValueType.STREAM,
                    StreamCodec.encode(decoded.entries(), groups));
        });
        StreamCodec.Decoded after = decodeAll(storage.get(key));
        int gi = indexOfGroup(after.groups(), groupName);
        long remaining = gi < 0 ? 0 : after.groups().get(gi)
                .pending().size();
        return new RespInteger(ids.size() - remaining);
    }

    private RespValue xpending(List<byte[]> args,
                               StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        StreamCodec.Decoded decoded =
                decodeAll(storage.get(args.get(0)));
        int gi = indexOfGroup(decoded.groups(),
                CommandUtil.text(args.get(1)));
        if (gi < 0) {
            return new RespError("NOGROUP No such consumer "
                    + "group");
        }
        List<Pending> pending = decoded.groups().get(gi)
                .pending();
        List<RespValue> items = new ArrayList<>();
        for (Pending item : pending) {
            items.add(new RespArray(List.of(
                    new RespBulkString(CommandUtil.bytes(
                            item.ms() + "-" + item.seq())),
                    new RespBulkString(CommandUtil.bytes(
                            item.consumer())),
                    new RespInteger(1))));
        }
        return new RespArray(List.of(
                new RespInteger(pending.size()),
                new RespArray(items)));
    }

    private static int indexOfGroup(List<Group> groups,
                                    String name) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static long[] lastId(List<Entry> entries) {
        if (entries.isEmpty()) {
            return new long[]{0, 0};
        }
        Entry last = entries.get(entries.size() - 1);
        return new long[]{last.ms(), last.seq()};
    }

    private static long[] parseId(String id) {
        String[] parts = id.split("-");
        long ms = Long.parseLong(parts[0]);
        long seq = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
        return new long[]{ms, seq};
    }

    private static StreamCodec.Decoded decodeAll(byte[] value) {
        if (value == null) {
            return new StreamCodec.Decoded(new ArrayList<>(),
                    new ArrayList<>());
        }
        if (TypedValueCodec.typeOf(value) != ValueType.STREAM) {
            throw TypeSupport.wrongTypeException();
        }
        return StreamCodec.decodeAll(TypedValueCodec.payload(value));
    }

    private static final class GroupExistsException
            extends RuntimeException {
    }

    private static final class GroupNotFoundException
            extends RuntimeException {
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
                        StreamCodec.Decoded decoded =
                                decodeAll(current);
                        List<Entry> entries = decoded.entries();
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
                                StreamCodec.encode(entries,
                                        decoded.groups()));
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
                StreamCodec.Decoded decoded = decodeAll(current);
                List<Entry> entries = decoded.entries();
                while (entries.size() > maxLen) {
                    entries.remove(0);
                }
                return TypedValueCodec.encode(ValueType.STREAM,
                        StreamCodec.encode(entries,
                                decoded.groups()));
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
        if (id.equals(">")) {
            return -1;
        }
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
