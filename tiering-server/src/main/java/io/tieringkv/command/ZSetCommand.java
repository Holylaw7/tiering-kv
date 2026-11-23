package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.storage.types.ZSetCodec;
import io.tieringkv.storage.types.ZSetCodec.Member;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ZSet 命令族（ADR-0280）。 */
public final class ZSetCommand implements Command {

    private final String name;

    public ZSetCommand(String name) {
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
            case "zadd" -> zadd(args, storage);
            case "zscore" -> zscore(args, storage);
            case "zrange" -> zrange(args, storage, false);
            case "zrevrange" -> zrange(args, storage, true);
            case "zrem" -> zrem(args, storage);
            case "zcard" -> zcard(args, storage);
            case "zincrby" -> zincrby(args, storage);
            case "zrangebyscore" -> zrangebyscore(args, storage);
            case "zcount" -> zcount(args, storage);
            case "zrank" -> zrank(args, storage, false);
            case "zrevrank" -> zrank(args, storage, true);
            case "zrangebylex" -> zrangebylex(args, storage);
            case "zlexcount" -> zlexcount(args, storage);
            case "zremrangebylex" -> zremrangebylex(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue zrangebylex(List<byte[]> args,
                                  StorageEngine storage) {
        if (args.size() < 3) {
            return RespError.wrongArity(name);
        }
        try {
            LexBound min = parseLexBound(args.get(1));
            LexBound max = parseLexBound(args.get(2));
            List<Member> sorted = sorted(decode(
                    storage.get(args.get(0))), false);
            List<Member> matched = new ArrayList<>();
            for (Member member : sorted) {
                if (inLexRange(member.member(), min, max)) {
                    matched.add(member);
                }
            }
            List<RespValue> values = new ArrayList<>();
            for (Member member : matched) {
                values.add(new RespBulkString(
                        member.member().data()));
            }
            return new RespArray(values);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zlexcount(List<byte[]> args,
                                StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            LexBound min = parseLexBound(args.get(1));
            LexBound max = parseLexBound(args.get(2));
            long count = sorted(decode(storage.get(args.get(0))),
                    false).stream()
                    .filter(member -> inLexRange(
                            member.member(), min, max))
                    .count();
            return new RespInteger(count);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zremrangebylex(List<byte[]> args,
                                     StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            LexBound min = parseLexBound(args.get(1));
            LexBound max = parseLexBound(args.get(2));
            byte[] key = args.get(0);
            int before = decode(storage.get(key)).size();
            TypeSupport.update(storage, key, current -> {
                List<Member> members = decode(current);
                members.removeIf(member -> inLexRange(
                        member.member(), min, max));
                return members.isEmpty() ? null
                        : TypedValueCodec.encode(ValueType.ZSET,
                        ZSetCodec.encode(members));
            });
            int after = decode(storage.get(key)).size();
            return new RespInteger(before - after);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private record LexBound(byte[] value, boolean inclusive,
                            boolean infinity,
                            boolean negativeInfinity) {
    }

    private static LexBound parseLexBound(byte[] bytes) {
        String text = CommandUtil.text(bytes);
        if (text.equals("-")) {
            return new LexBound(new byte[0], false, true,
                    true);
        }
        if (text.equals("+")) {
            return new LexBound(new byte[0], false, true,
                    false);
        }
        boolean inclusive = text.startsWith("[");
        if (inclusive || text.startsWith("(")) {
            text = text.substring(1);
        }
        return new LexBound(text.getBytes(
                java.nio.charset.StandardCharsets.UTF_8),
                inclusive, false, false);
    }

    private static boolean inLexRange(ByteArrayKey member,
                                      LexBound min,
                                      LexBound max) {
        int cmpMin = min.infinity()
                ? (min.negativeInfinity() ? -1 : 1)
                : java.util.Arrays.compareUnsigned(member.data(),
                min.value());
        int cmpMax = max.infinity()
                ? (max.negativeInfinity() ? -1 : 1)
                : java.util.Arrays.compareUnsigned(member.data(),
                max.value());
        boolean aboveMin = min.infinity()
                ? min.negativeInfinity() || cmpMin > 0
                : min.inclusive() ? cmpMin >= 0 : cmpMin > 0;
        boolean belowMax = max.infinity()
                ? !max.negativeInfinity() || cmpMax < 0
                : max.inclusive() ? cmpMax <= 0 : cmpMax < 0;
        return aboveMin && belowMax;
    }

    private RespValue zadd(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            Map<ByteArrayKey, Double> pairs =
                    new LinkedHashMap<>();
            for (int i = 1; i < args.size(); i += 2) {
                double score = TypeSupport.parseDouble(args.get(i));
                if (Double.isNaN(score)) {
                    return new RespError(
                            "ERR value is not a valid float");
                }
                pairs.put(new ByteArrayKey(args.get(i + 1)),
                        score);
            }
            long[] addedHolder = {0};
            TypeSupport.update(storage, key,
                    current -> {
                        List<Member> members = decode(current);
                        Map<ByteArrayKey, Double> existing =
                                toMap(members);
                        long added = 0;
                        for (Map.Entry<ByteArrayKey, Double> entry
                                : pairs.entrySet()) {
                            if (!existing.containsKey(
                                    entry.getKey())) {
                                added++;
                            }
                            existing.put(entry.getKey(),
                                    entry.getValue());
                        }
                        addedHolder[0] = added;
                        return TypedValueCodec.encode(
                                ValueType.ZSET,
                                ZSetCodec.encode(toMembers(
                                        existing)));
                    });
            return new RespInteger(addedHolder[0]);
        } catch (NumberFormatException e) {
            return new RespError(
                    "ERR value is not a valid float");
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zscore(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            Double score = scoreOf(storage.get(args.get(0)),
                    new ByteArrayKey(args.get(1)));
            return score == null ? RespNull.BULK_STRING
                    : new RespBulkString(CommandUtil.bytes(
                    TypeSupport.formatScore(score)));
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zrange(List<byte[]> args,
                             StorageEngine storage,
                             boolean reverse) {
        if (args.size() < 3 || args.size() > 4) {
            return RespError.wrongArity(name);
        }
        try {
            boolean withScores = args.size() == 4
                    && CommandUtil.text(args.get(3))
                    .equalsIgnoreCase("withscores");
            List<Member> sorted = sorted(decode(
                    storage.get(args.get(0))), reverse);
            long start = CommandUtil.parseLong(args.get(1));
            long end = CommandUtil.parseLong(args.get(2));
            int size = sorted.size();
            long from = start < 0 ? size + start : start;
            long to = end < 0 ? size + end : end;
            from = Math.max(0, from);
            to = Math.min(size - 1L, to);
            List<RespValue> values = new ArrayList<>();
            if (from <= to && from < size) {
                for (long i = from; i <= to; i++) {
                    Member member = sorted.get((int) i);
                    values.add(new RespBulkString(
                            member.member().data()));
                    if (withScores) {
                        values.add(new RespBulkString(
                                CommandUtil.bytes(
                                        TypeSupport.formatScore(
                                                member.score()))));
                    }
                }
            }
            return new RespArray(values);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zrem(List<byte[]> args,
                           StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            int before = decode(storage.get(key)).size();
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        List<Member> members = decode(current);
                        Map<ByteArrayKey, Double> map =
                                toMap(members);
                        for (int i = 1; i < args.size(); i++) {
                            map.remove(new ByteArrayKey(args.get(i)));
                        }
                        return map.isEmpty() ? null
                                : TypedValueCodec.encode(
                                ValueType.ZSET,
                                ZSetCodec.encode(toMembers(map)));
                    });
            List<Member> after = result == null
                    ? List.of() : decode(result);
            return new RespInteger(before - after.size());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zcard(List<byte[]> args,
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

    private RespValue zincrby(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            double delta = TypeSupport.parseDouble(args.get(1));
            if (Double.isNaN(delta)) {
                return new RespError(
                        "ERR value is not a valid float");
            }
            byte[] key = args.get(0);
            ByteArrayKey member = new ByteArrayKey(args.get(2));
            byte[] result = TypeSupport.update(storage, key,
                    current -> {
                        Map<ByteArrayKey, Double> map =
                                toMap(decode(current));
                        double next = map.getOrDefault(member, 0.0)
                                + delta;
                        if (Double.isNaN(next)) {
                            throw new NumberFormatException();
                        }
                        map.put(member, next);
                        return TypedValueCodec.encode(
                                ValueType.ZSET,
                                ZSetCodec.encode(toMembers(map)));
                    });
            double score = toMap(decode(result)).get(member);
            return new RespBulkString(CommandUtil.bytes(
                    TypeSupport.formatScore(score)));
        } catch (NumberFormatException e) {
            return new RespError(
                    "ERR value is not a valid float");
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zrangebyscore(List<byte[]> args,
                                    StorageEngine storage) {
        if (args.size() < 3 || args.size() > 4) {
            return RespError.wrongArity(name);
        }
        try {
            Bound min = parseBound(args.get(1));
            Bound max = parseBound(args.get(2));
            boolean withScores = args.size() == 4
                    && CommandUtil.text(args.get(3))
                    .equalsIgnoreCase("withscores");
            List<Member> sorted = sorted(decode(
                    storage.get(args.get(0))), false);
            List<RespValue> values = new ArrayList<>();
            for (Member member : sorted) {
                if (inRange(member.score(), min, max)) {
                    values.add(new RespBulkString(
                            member.member().data()));
                    if (withScores) {
                        values.add(new RespBulkString(
                                CommandUtil.bytes(
                                        TypeSupport.formatScore(
                                                member.score()))));
                    }
                }
            }
            return new RespArray(values);
        } catch (NumberFormatException e) {
            return new RespError(
                    "ERR min or max is not a float");
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zcount(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            Bound min = parseBound(args.get(1));
            Bound max = parseBound(args.get(2));
            long count = sorted(decode(storage.get(args.get(0))),
                    false).stream()
                    .filter(member -> inRange(member.score(),
                            min, max))
                    .count();
            return new RespInteger(count);
        } catch (NumberFormatException e) {
            return new RespError(
                    "ERR min or max is not a float");
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue zrank(List<byte[]> args,
                            StorageEngine storage,
                            boolean reverse) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            List<Member> sorted = sorted(decode(
                    storage.get(args.get(0))), reverse);
            ByteArrayKey target = new ByteArrayKey(args.get(1));
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).member().equals(target)) {
                    return new RespInteger(i);
                }
            }
            return RespNull.BULK_STRING;
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    static List<Member> decode(byte[] value) {
        if (value == null) {
            return new ArrayList<>();
        }
        if (TypedValueCodec.typeOf(value) != ValueType.ZSET) {
            throw TypeSupport.wrongTypeException();
        }
        return ZSetCodec.decode(TypedValueCodec.payload(value));
    }

    private static Map<ByteArrayKey, Double> toMap(
            List<Member> members) {
        Map<ByteArrayKey, Double> map = new LinkedHashMap<>();
        for (Member member : members) {
            map.put(member.member(), member.score());
        }
        return map;
    }

    private static List<Member> toMembers(
            Map<ByteArrayKey, Double> map) {
        List<Member> members = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, Double> entry
                : map.entrySet()) {
            members.add(new Member(entry.getValue(),
                    entry.getKey()));
        }
        return members;
    }

    private static List<Member> sorted(List<Member> members,
                                       boolean reverse) {
        Comparator<Member> comparator = Comparator
                .comparingDouble(Member::score)
                .thenComparing(member -> member.member().data(),
                        (a, b) -> java.util.Arrays
                                .compareUnsigned(a, b));
        List<Member> result = new ArrayList<>(members);
        result.sort(reverse ? comparator.reversed()
                : comparator);
        return result;
    }

    private static Double scoreOf(byte[] value,
                                  ByteArrayKey member) {
        return toMap(decode(value)).get(member);
    }

    private record Bound(double value, boolean exclusive) {
    }

    private static Bound parseBound(byte[] bytes) {
        String text = CommandUtil.text(bytes);
        boolean exclusive = text.startsWith("(");
        if (exclusive) {
            text = text.substring(1);
        }
        double value = switch (text) {
            case "-inf" -> Double.NEGATIVE_INFINITY;
            case "+inf", "inf" -> Double.POSITIVE_INFINITY;
            default -> Double.parseDouble(text);
        };
        return new Bound(value, exclusive);
    }

    private static boolean inRange(double score, Bound min,
                                   Bound max) {
        boolean aboveMin = min.exclusive()
                ? score > min.value() : score >= min.value();
        boolean belowMax = max.exclusive()
                ? score < max.value() : score <= max.value();
        return aboveMin && belowMax;
    }
}
