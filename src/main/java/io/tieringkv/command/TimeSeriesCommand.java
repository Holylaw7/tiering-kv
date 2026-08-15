package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespDouble;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * 时序命令族（ADR-0337）：TS.RANGE（AGGREGATION 桶聚合 + COUNT）、
 * TS.INCRBY（同刻累加/新刻追加，原子 + TTL 保留）、TS.MRANGE
 * （全部 TIME_SERIES 键）、TS.REDUCE（全序列聚合，项目扩展）。
 *
 * <p>复用 TIME_SERIES 冻结编码；桶按 floorDiv(ts, bucket)*bucket
 * 对齐；聚合子集 AVG/SUM/MIN/MAX/COUNT/FIRST/LAST。
 */
public final class TimeSeriesCommand implements Command {

    private final String name;

    public TimeSeriesCommand(String name) {
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
            case "ts.range" -> tsRange(args, storage);
            case "ts.mrange" -> tsMRange(args, storage);
            case "ts.incrby" -> tsIncrBy(args, storage);
            case "ts.reduce" -> tsReduce(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue tsRange(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() < 3) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            List<MultiModelCodec.TimePoint> points =
                    readPoints(storage, key);
            if (points == null) {
                return new RespArray(List.of());
            }
            long from = parseTimestamp(text(args.get(1)));
            long to = parseTimestamp(text(args.get(2)));
            Aggregation aggregation = null;
            long bucket = 0;
            long count = -1;
            int idx = 3;
            while (idx < args.size()) {
                String option = text(args.get(idx))
                        .toLowerCase(Locale.ROOT);
                if (option.equals("aggregation")
                        && idx + 2 < args.size()) {
                    aggregation = parseAggregation(args.get(idx + 1));
                    bucket = parseLong(args.get(idx + 2));
                    if (bucket <= 0) {
                        return new RespError(
                                "ERR bucket size must be positive");
                    }
                    idx += 3;
                } else if (option.equals("count")
                        && idx + 1 < args.size()) {
                    count = parseLong(args.get(idx + 1));
                    if (count <= 0) {
                        return new RespError(
                                "ERR COUNT must be > 0");
                    }
                    idx += 2;
                } else {
                    return new RespError("ERR syntax error");
                }
            }
            return new RespArray(buildSamples(points, from, to,
                    aggregation, bucket, count));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        } catch (UnknownAggregationException e) {
            return new RespError("ERR unknown aggregation type");
        }
    }

    private RespValue tsMRange(List<byte[]> args,
                               StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            long from = parseTimestamp(text(args.get(0)));
            long to = parseTimestamp(text(args.get(1)));
            Aggregation aggregation = null;
            long bucket = 0;
            long count = -1;
            int idx = 2;
            while (idx < args.size()) {
                String option = text(args.get(idx))
                        .toLowerCase(Locale.ROOT);
                if (option.equals("aggregation")
                        && idx + 2 < args.size()) {
                    aggregation = parseAggregation(args.get(idx + 1));
                    bucket = parseLong(args.get(idx + 2));
                    if (bucket <= 0) {
                        return new RespError(
                                "ERR bucket size must be positive");
                    }
                    idx += 3;
                } else if (option.equals("count")
                        && idx + 1 < args.size()) {
                    count = parseLong(args.get(idx + 1));
                    if (count <= 0) {
                        return new RespError(
                                "ERR COUNT must be > 0");
                    }
                    idx += 2;
                } else {
                    return new RespError("ERR syntax error");
                }
            }
            List<byte[]> seriesKeys = new ArrayList<>();
            try (StorageIterator iterator = storage.iterator()) {
                while (iterator.hasNext()) {
                    KeyValueEntry entry = iterator.next();
                    byte[] value = entry.value();
                    if (value != null && TypedValueCodec.typeOf(value)
                            == ValueType.TIME_SERIES) {
                        seriesKeys.add(entry.key());
                    }
                }
            }
            seriesKeys.sort(Comparator.comparing(
                    key -> new String(key, StandardCharsets.UTF_8)));
            List<RespValue> series = new ArrayList<>();
            for (byte[] key : seriesKeys) {
                List<RespValue> samples = buildSamples(
                        MultiModelCodec.decodeTimeSeries(
                                storage.get(key)),
                        from, to, aggregation, bucket, count);
                if (samples.isEmpty()) {
                    continue;
                }
                series.add(new RespArray(List.of(
                        new RespBulkString(key),
                        new RespArray(samples))));
            }
            return new RespArray(series);
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        } catch (UnknownAggregationException e) {
            return new RespError("ERR unknown aggregation type");
        }
    }

    private RespValue tsIncrBy(List<byte[]> args,
                               StorageEngine storage) {
        if (args.size() != 2 && args.size() != 4) {
            return RespError.wrongArity(name);
        }
        double increment;
        try {
            increment = Double.parseDouble(text(args.get(1)));
        } catch (NumberFormatException e) {
            return new RespError("ERR invalid value");
        }
        long timestamp = System.currentTimeMillis();
        if (args.size() == 4) {
            if (!text(args.get(2)).equalsIgnoreCase("timestamp")) {
                return new RespError("ERR syntax error");
            }
            try {
                timestamp = parseLong(args.get(3));
            } catch (NumberFormatException e) {
                return new RespError(CommandUtil.NOT_INTEGER);
            }
        }
        final long targetTs = timestamp;
        try {
            TypeSupport.update(storage, args.get(0), current -> {
                if (current != null && TypedValueCodec.typeOf(current)
                        != ValueType.TIME_SERIES) {
                    throw TypeSupport.wrongTypeException();
                }
                List<MultiModelCodec.TimePoint> points =
                        current == null ? new ArrayList<>()
                                : new ArrayList<>(
                                MultiModelCodec.decodeTimeSeries(
                                        current));
                boolean updated = false;
                for (int i = 0; i < points.size(); i++) {
                    MultiModelCodec.TimePoint point = points.get(i);
                    if (point.timestampMillis() == targetTs) {
                        points.set(i, new MultiModelCodec.TimePoint(
                                targetTs, point.value() + increment));
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    points.add(new MultiModelCodec.TimePoint(
                            targetTs, increment));
                }
                return MultiModelCodec.encodeTimeSeries(points);
            });
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
        return new RespInteger(timestamp);
    }

    private RespValue tsReduce(List<byte[]> args,
                               StorageEngine storage) {
        if (args.size() != 1 && args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            List<MultiModelCodec.TimePoint> points =
                    readPoints(storage, args.get(0));
            if (points == null || points.isEmpty()) {
                return RespNull.ARRAY;
            }
            Aggregation aggregation = Aggregation.SUM;
            if (args.size() == 3) {
                if (!text(args.get(1)).equalsIgnoreCase(
                        "aggregation")) {
                    return new RespError("ERR syntax error");
                }
                aggregation = parseAggregation(args.get(2));
            }
            List<MultiModelCodec.TimePoint> sorted =
                    new ArrayList<>(points);
            sorted.sort(Comparator.comparingLong(
                    MultiModelCodec.TimePoint::timestampMillis));
            double value = aggregate(sorted, aggregation);
            return new RespArray(List.of(
                    new RespInteger(sorted.get(0).timestampMillis()),
                    new RespDouble(value)));
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        } catch (UnknownAggregationException e) {
            return new RespError("ERR unknown aggregation type");
        }
    }

    private static List<RespValue> buildSamples(
            List<MultiModelCodec.TimePoint> points,
            long from, long to,
            Aggregation aggregation, long bucket, long count) {
        List<MultiModelCodec.TimePoint> sorted =
                new ArrayList<>(points);
        sorted.sort(Comparator.comparingLong(
                MultiModelCodec.TimePoint::timestampMillis));
        List<RespValue> samples = new ArrayList<>();
        if (aggregation == null) {
            for (MultiModelCodec.TimePoint point : sorted) {
                if (count >= 0 && samples.size() >= count) {
                    break;
                }
                if (point.timestampMillis() >= from
                        && point.timestampMillis() <= to) {
                    samples.add(sample(point.timestampMillis(),
                            point.value()));
                }
            }
            return samples;
        }
        TreeMap<Long, List<MultiModelCodec.TimePoint>> buckets =
                new TreeMap<>();
        for (MultiModelCodec.TimePoint point : sorted) {
            if (point.timestampMillis() < from
                    || point.timestampMillis() > to) {
                continue;
            }
            long start = Math.floorDiv(point.timestampMillis(),
                    bucket) * bucket;
            buckets.computeIfAbsent(start,
                    ignored -> new ArrayList<>()).add(point);
        }
        for (var entry : buckets.entrySet()) {
            if (count >= 0 && samples.size() >= count) {
                break;
            }
            samples.add(sample(entry.getKey(),
                    aggregate(entry.getValue(), aggregation)));
        }
        return samples;
    }

    private static double aggregate(
            List<MultiModelCodec.TimePoint> points,
            Aggregation aggregation) {
        List<MultiModelCodec.TimePoint> sorted =
                new ArrayList<>(points);
        sorted.sort(Comparator.comparingLong(
                MultiModelCodec.TimePoint::timestampMillis));
        return switch (aggregation) {
            case SUM -> sorted.stream()
                    .mapToDouble(MultiModelCodec.TimePoint::value)
                    .sum();
            case AVG -> sorted.stream()
                    .mapToDouble(MultiModelCodec.TimePoint::value)
                    .average().orElse(0);
            case MIN -> sorted.stream()
                    .mapToDouble(MultiModelCodec.TimePoint::value)
                    .min().orElse(0);
            case MAX -> sorted.stream()
                    .mapToDouble(MultiModelCodec.TimePoint::value)
                    .max().orElse(0);
            case COUNT -> sorted.size();
            case FIRST -> sorted.get(0).value();
            case LAST -> sorted.get(sorted.size() - 1).value();
        };
    }

    private static RespArray sample(long timestamp, double value) {
        return new RespArray(List.of(
                new RespInteger(timestamp),
                new RespDouble(value)));
    }

    private enum Aggregation {
        AVG,
        SUM,
        MIN,
        MAX,
        COUNT,
        FIRST,
        LAST
    }

    private static Aggregation parseAggregation(byte[] value) {
        String name = text(value).toUpperCase(Locale.ROOT);
        for (Aggregation aggregation : Aggregation.values()) {
            if (aggregation.name().equals(name)) {
                return aggregation;
            }
        }
        throw new UnknownAggregationException();
    }

    private static List<MultiModelCodec.TimePoint> readPoints(
            StorageEngine storage, byte[] key) {
        byte[] value = storage.get(key);
        if (value == null) {
            return null;
        }
        if (TypedValueCodec.typeOf(value) != ValueType.TIME_SERIES) {
            throw TypeSupport.wrongTypeException();
        }
        return MultiModelCodec.decodeTimeSeries(value);
    }

    private static long parseTimestamp(String value) {
        if (value.equals("-inf")) {
            return Long.MIN_VALUE;
        }
        if (value.equals("+inf")) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(value.trim());
    }

    private static final class UnknownAggregationException
            extends RuntimeException {
    }

    private static long parseLong(byte[] value) {
        return CommandUtil.parseLong(value);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
