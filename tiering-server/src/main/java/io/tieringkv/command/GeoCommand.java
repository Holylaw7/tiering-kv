package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;
import io.tieringkv.storage.types.GeoHash;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.storage.types.ZSetCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GEO 命令族（ADR-0335）：GEOADD/GEOPOS/GEODIST/GEOHASH/GEOSEARCH/
 * GEORADIUS/GEORADIUSBYMEMBER。
 *
 * <p>存储复用 ZSET：score = 52 位 geohash（ADR-0335），TYPE=zset，
 * ZRANGE/ZSCORE 天然兼容；检索为精确 haversine 过滤（O(N)）。
 */
public final class GeoCommand implements Command {

    private final String name;

    public GeoCommand(String name) {
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
            case "geoadd" -> geoadd(args, storage);
            case "geopos" -> geopos(args, storage);
            case "geodist" -> geodist(args, storage);
            case "geohash" -> geohash(args, storage);
            case "geosearch" -> geosearch(args, storage);
            case "georadius" -> georadius(args, storage, false);
            case "georadiusbymember" -> georadius(args, storage, true);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue geoadd(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 4) {
            return RespError.wrongArity(name);
        }
        byte[] key = args.get(0);
        boolean nx = false;
        boolean xx = false;
        boolean ch = false;
        int idx = 1;
        while (idx < args.size()) {
            String option = CommandUtil.text(args.get(idx))
                    .toLowerCase(Locale.ROOT);
            if (option.equals("nx")) {
                nx = true;
            } else if (option.equals("xx")) {
                xx = true;
            } else if (option.equals("ch")) {
                ch = true;
            } else {
                break;
            }
            idx++;
        }
        if ((args.size() - idx) % 3 != 0 || (nx && xx)) {
            return new RespError("ERR syntax error");
        }
        final int first = idx;
        final boolean addOnlyNew = nx;
        final boolean updateOnlyExisting = xx;
        final boolean reportChanged = ch;
        int[] addedHolder = {0};
        int[] changedHolder = {0};
        try {
            TypeSupport.update(storage, key, current -> {
                if (current != null && TypedValueCodec.typeOf(current)
                        != ValueType.ZSET) {
                    throw TypeSupport.wrongTypeException();
                }
                Map<ByteArrayKey, Double> existing =
                        scoreMap(members(current));
                Map<ByteArrayKey, Double> applied =
                        new LinkedHashMap<>();
                for (int i = first; i < args.size(); i += 3) {
                    double longitude =
                            TypeSupport.parseDouble(args.get(i));
                    double latitude =
                            TypeSupport.parseDouble(args.get(i + 1));
                    if (!GeoHash.valid(longitude, latitude)) {
                        throw new InvalidCoordinateException(
                                longitude, latitude);
                    }
                    ByteArrayKey member =
                            new ByteArrayKey(args.get(i + 2));
                    boolean exists = existing.containsKey(member);
                    if (addOnlyNew && exists) {
                        continue;
                    }
                    if (updateOnlyExisting && !exists) {
                        continue;
                    }
                    double score = GeoHash.encode(longitude, latitude);
                    if (applied.containsKey(member)) {
                        applied.put(member, score); // 命令内重复：最后一次生效
                        continue;
                    }
                    if (!exists) {
                        addedHolder[0]++;
                    } else if (score != existing.get(member)) {
                        changedHolder[0]++;
                    }
                    applied.put(member, score);
                }
                if (applied.isEmpty()) {
                    return current;
                }
                List<ZSetCodec.Member> updated = new ArrayList<>();
                for (Map.Entry<ByteArrayKey, Double> entry
                        : existing.entrySet()) {
                    if (!applied.containsKey(entry.getKey())) {
                        updated.add(new ZSetCodec.Member(
                                entry.getValue(), entry.getKey()));
                    }
                }
                for (Map.Entry<ByteArrayKey, Double> entry
                        : applied.entrySet()) {
                    updated.add(new ZSetCodec.Member(
                            entry.getValue(), entry.getKey()));
                }
                return TypedValueCodec.encode(ValueType.ZSET,
                        ZSetCodec.encode(updated));
            });
        } catch (InvalidCoordinateException e) {
            return new RespError("ERR invalid longitude,latitude "
                    + "pair " + String.format(Locale.ROOT, "%.6f",
                    e.longitude) + "," + String.format(Locale.ROOT,
                    "%.6f", e.latitude));
        } catch (NumberFormatException e) {
            return new RespError("ERR value is not a valid float");
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
        return new RespInteger(reportChanged
                ? addedHolder[0] + changedHolder[0]
                : addedHolder[0]);
    }

    private RespValue geopos(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, Double> map =
                    scoreMap(members(storage.get(args.get(0))));
            List<RespValue> result = new ArrayList<>();
            for (int i = 1; i < args.size(); i++) {
                Double score = map.get(new ByteArrayKey(args.get(i)));
                if (score == null) {
                    result.add(RespNull.ARRAY);
                    continue;
                }
                GeoHash.Point point =
                        GeoHash.decode(score.longValue());
                result.add(new RespArray(List.of(
                        new RespBulkString(CommandUtil.bytes(
                                Double.toString(point.longitude()))),
                        new RespBulkString(CommandUtil.bytes(
                                Double.toString(point.latitude()))))));
            }
            return new RespArray(result);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue geodist(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 3 && args.size() != 4) {
            return RespError.wrongArity(name);
        }
        String unit = "m";
        if (args.size() == 4) {
            unit = CommandUtil.text(args.get(3))
                    .toLowerCase(Locale.ROOT);
            if (toMeters(unit) < 0) {
                return unsupportedUnit();
            }
        }
        try {
            Map<ByteArrayKey, Double> map =
                    scoreMap(members(storage.get(args.get(0))));
            Double score1 = map.get(new ByteArrayKey(args.get(1)));
            Double score2 = map.get(new ByteArrayKey(args.get(2)));
            if (score1 == null || score2 == null) {
                return RespNull.BULK_STRING;
            }
            GeoHash.Point first =
                    GeoHash.decode(score1.longValue());
            GeoHash.Point second =
                    GeoHash.decode(score2.longValue());
            double meters = GeoHash.distanceMeters(first, second);
            return new RespBulkString(CommandUtil.bytes(
                    String.format(Locale.ROOT, "%.4f",
                            meters / toMeters(unit))));
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue geohash(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        try {
            Map<ByteArrayKey, Double> map =
                    scoreMap(members(storage.get(args.get(0))));
            List<RespValue> result = new ArrayList<>();
            for (int i = 1; i < args.size(); i++) {
                Double score = map.get(new ByteArrayKey(args.get(i)));
                result.add(score == null ? RespNull.BULK_STRING
                        : new RespBulkString(CommandUtil.bytes(
                        GeoHash.toHashString(score.longValue()))));
            }
            return new RespArray(result);
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
    }

    private RespValue geosearch(List<byte[]> args,
                                StorageEngine storage) {
        if (args.size() < 5) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            int idx = 1;
            GeoHash.Point center = null;
            ByteArrayKey fromMember = null;
            String from = CommandUtil.text(args.get(idx))
                    .toLowerCase(Locale.ROOT);
            idx++;
            if (from.equals("frommember")) {
                if (idx >= args.size()) {
                    return new RespError("ERR syntax error");
                }
                fromMember = new ByteArrayKey(args.get(idx));
                idx++;
            } else if (from.equals("fromlonlat")) {
                if (idx + 1 >= args.size()) {
                    return new RespError("ERR syntax error");
                }
                double longitude = TypeSupport.parseDouble(
                        args.get(idx));
                double latitude = TypeSupport.parseDouble(
                        args.get(idx + 1));
                if (!GeoHash.valid(longitude, latitude)) {
                    return new RespError("ERR invalid "
                            + "longitude,latitude pair "
                            + String.format(Locale.ROOT, "%.6f",
                            longitude) + "," + String.format(
                            Locale.ROOT, "%.6f", latitude));
                }
                center = new GeoHash.Point(longitude, latitude);
                idx += 2;
            } else {
                return new RespError("ERR syntax error");
            }
            Shape shape;
            try {
                shape = parseShape(args, idx);
            } catch (RadiusException e) {
                return new RespError("ERR radius cannot be negative");
            } catch (BoxException e) {
                return new RespError(
                        "ERR height or width cannot be negative");
            } catch (UnitException e) {
                return unsupportedUnit();
            }
            if (shape == null) {
                return new RespError("ERR syntax error");
            }
            idx = shape.nextIndex;
            Options options;
            try {
                options = parseOptions(args, idx);
            } catch (CountException e) {
                return new RespError("ERR COUNT must be > 0");
            } catch (AnyException e) {
                return new RespError("ERR the ANY argument "
                        + "requires COUNT argument");
            }
            if (options == null) {
                return new RespError("ERR syntax error");
            }
            byte[] value = storage.get(key);
            if (value == null) {
                return new RespArray(List.of());
            }
            if (TypedValueCodec.typeOf(value) != ValueType.ZSET) {
                return TypeSupport.wrongType();
            }
            Map<ByteArrayKey, Double> map = scoreMap(
                    ZSetCodec.decode(TypedValueCodec.payload(value)));
            if (fromMember != null) {
                Double score = map.get(fromMember);
                if (score == null) {
                    return new RespError(
                            "ERR could not decode requested zset member");
                }
                center = GeoHash.decode(score.longValue());
            }
            return searchAndFormat(map, center, shape, options);
        } catch (NumberFormatException e) {
            return new RespError("ERR value is not a valid float");
        }
    }

    private RespValue georadius(List<byte[]> args,
                                StorageEngine storage,
                                boolean byMember) {
        if (args.size() < 5) {
            return RespError.wrongArity(name);
        }
        try {
            byte[] key = args.get(0);
            byte[] value = storage.get(key);
            if (value == null) {
                return new RespArray(List.of());
            }
            if (TypedValueCodec.typeOf(value) != ValueType.ZSET) {
                return TypeSupport.wrongType();
            }
            Map<ByteArrayKey, Double> map = scoreMap(
                    ZSetCodec.decode(TypedValueCodec.payload(value)));
            GeoHash.Point center;
            int idx;
            if (byMember) {
                Double score = map.get(new ByteArrayKey(args.get(1)));
                if (score == null) {
                    return new RespError(
                            "ERR could not decode requested zset member");
                }
                center = GeoHash.decode(score.longValue());
                idx = 2;
            } else {
                double longitude = TypeSupport.parseDouble(args.get(1));
                double latitude = TypeSupport.parseDouble(args.get(2));
                if (!GeoHash.valid(longitude, latitude)) {
                    return new RespError("ERR invalid "
                            + "longitude,latitude pair "
                            + String.format(Locale.ROOT, "%.6f",
                            longitude) + "," + String.format(
                            Locale.ROOT, "%.6f", latitude));
                }
                center = new GeoHash.Point(longitude, latitude);
                idx = 3;
            }
            double radius;
            try {
                radius = TypeSupport.parseDouble(args.get(idx));
            } catch (NumberFormatException e) {
                return new RespError("ERR need numeric radius");
            }
            if (radius < 0) {
                return new RespError("ERR radius cannot be negative");
            }
            String unit = CommandUtil.text(args.get(idx + 1))
                    .toLowerCase(Locale.ROOT);
            if (toMeters(unit) < 0) {
                return unsupportedUnit();
            }
            Shape shape = Shape.radius(radius, unit, idx + 2);
            Options options;
            try {
                options = parseOptions(args, shape.nextIndex);
            } catch (CountException e) {
                return new RespError("ERR COUNT must be > 0");
            } catch (AnyException e) {
                return new RespError("ERR the ANY argument "
                        + "requires COUNT argument");
            }
            if (options == null) {
                return new RespError("ERR syntax error");
            }
            return searchAndFormat(map, center, shape, options);
        } catch (NumberFormatException e) {
            return new RespError("ERR value is not a valid float");
        }
    }

    private static Shape parseShape(List<byte[]> args, int idx) {
        if (idx >= args.size()) {
            return null;
        }
        String by = CommandUtil.text(args.get(idx))
                .toLowerCase(Locale.ROOT);
        idx++;
        if (by.equals("byradius")) {
            if (idx + 1 >= args.size()) {
                return null;
            }
            double radius;
            try {
                radius = TypeSupport.parseDouble(args.get(idx));
            } catch (NumberFormatException e) {
                throw new NumberFormatException();
            }
            if (radius < 0) {
                throw new RadiusException();
            }
            String unit = CommandUtil.text(args.get(idx + 1))
                    .toLowerCase(Locale.ROOT);
            if (toMeters(unit) < 0) {
                throw new UnitException();
            }
            return Shape.radius(radius, unit, idx + 2);
        }
        if (by.equals("bybox")) {
            if (idx + 2 >= args.size()) {
                return null;
            }
            double width;
            double height;
            try {
                width = TypeSupport.parseDouble(args.get(idx));
                height = TypeSupport.parseDouble(args.get(idx + 1));
            } catch (NumberFormatException e) {
                throw new NumberFormatException();
            }
            if (width < 0 || height < 0) {
                throw new BoxException();
            }
            String unit = CommandUtil.text(args.get(idx + 2))
                    .toLowerCase(Locale.ROOT);
            if (toMeters(unit) < 0) {
                throw new UnitException();
            }
            return Shape.box(width, height, unit, idx + 3);
        }
        return null;
    }

    private static Options parseOptions(List<byte[]> args, int idx) {
        Options options = new Options();
        while (idx < args.size()) {
            String option = CommandUtil.text(args.get(idx))
                    .toLowerCase(Locale.ROOT);
            if (option.equals("asc")) {
                options.sort = 1;
                idx++;
            } else if (option.equals("desc")) {
                options.sort = -1;
                idx++;
            } else if (option.equals("withdist")) {
                options.withDist = true;
                idx++;
            } else if (option.equals("withhash")) {
                options.withHash = true;
                idx++;
            } else if (option.equals("withcoord")) {
                options.withCoord = true;
                idx++;
            } else if (option.equals("count")) {
                if (idx + 1 >= args.size()) {
                    return null;
                }
                try {
                    options.count = CommandUtil.parseLong(
                            args.get(idx + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
                if (options.count <= 0) {
                    throw new CountException();
                }
                idx += 2;
            } else if (option.equals("any")) {
                options.any = true;
                idx++;
            } else {
                return null;
            }
        }
        if (options.any && options.count == 0) {
            throw new AnyException();
        }
        if (options.count > 0 && options.sort == 0
                && !options.any) {
            options.sort = 1; // COUNT 未指定排序时 Redis 强制 ASC
        }
        return options;
    }

    private static RespValue searchAndFormat(
            Map<ByteArrayKey, Double> map,
            GeoHash.Point center,
            Shape shape,
            Options options) {
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, Double> entry
                : map.entrySet()) {
            GeoHash.Point point =
                    GeoHash.decode(entry.getValue().longValue());
            double distance;
            if (shape.radiusMeters >= 0) {
                distance = GeoHash.distanceMeters(center, point);
                if (distance > shape.radiusMeters) {
                    continue;
                }
            } else {
                distance = GeoHash.distanceInRectangle(
                        shape.widthMeters, shape.heightMeters,
                        center, point);
                if (distance < 0) {
                    continue;
                }
            }
            hits.add(new Hit(entry.getKey(), entry.getValue(),
                    point, distance));
        }
        if (options.any && options.count > 0
                && hits.size() > options.count) {
            hits = new ArrayList<>(
                    hits.subList(0, (int) options.count));
        }
        if (options.sort != 0) {
            Comparator<Hit> byDistance = Comparator
                    .comparingDouble(Hit::distance);
            if (options.sort < 0) {
                byDistance = byDistance.reversed();
            }
            hits.sort(byDistance.thenComparing(
                    hit -> hit.member.data(),
                    (left, right) -> Arrays.compare(left, right)));
        }
        long limit = options.count > 0 ? options.count
                : Long.MAX_VALUE;
        List<RespValue> results = new ArrayList<>();
        for (Hit hit : hits) {
            if (results.size() >= limit) {
                break;
            }
            boolean withOptions = options.withDist
                    || options.withHash || options.withCoord;
            if (!withOptions) {
                results.add(new RespBulkString(hit.member.data()));
                continue;
            }
            List<RespValue> nested = new ArrayList<>();
            nested.add(new RespBulkString(hit.member.data()));
            if (options.withDist) {
                nested.add(new RespBulkString(CommandUtil.bytes(
                        String.format(Locale.ROOT, "%.4f",
                                hit.distance / shape.unitMeters))));
            }
            if (options.withHash) {
                nested.add(new RespInteger(
                        hit.score.longValue()));
            }
            if (options.withCoord) {
                nested.add(new RespArray(List.of(
                        new RespBulkString(CommandUtil.bytes(
                                Double.toString(
                                        hit.point.longitude()))),
                        new RespBulkString(CommandUtil.bytes(
                                Double.toString(
                                        hit.point.latitude()))))));
            }
            results.add(new RespArray(nested));
        }
        return new RespArray(results);
    }

    private static Map<ByteArrayKey, Double> scoreMap(
            List<ZSetCodec.Member> members) {
        Map<ByteArrayKey, Double> map = new LinkedHashMap<>();
        for (ZSetCodec.Member member : members) {
            map.put(member.member(), member.score());
        }
        return map;
    }

    private static List<ZSetCodec.Member> members(byte[] value) {
        if (value == null) {
            return List.of();
        }
        if (TypedValueCodec.typeOf(value) != ValueType.ZSET) {
            throw TypeSupport.wrongTypeException();
        }
        return ZSetCodec.decode(TypedValueCodec.payload(value));
    }

    private static double toMeters(String unit) {
        return switch (unit) {
            case "m" -> 1.0;
            case "km" -> 1000.0;
            case "mi" -> 1609.34;
            case "ft" -> 0.3048;
            default -> -1.0;
        };
    }

    private static RespError unsupportedUnit() {
        return new RespError("ERR unsupported unit provided. "
                + "please use m, km, ft, mi");
    }

    private record Hit(ByteArrayKey member, Double score,
                       GeoHash.Point point, double distance) {
    }

    private static final class Shape {
        private final double radiusMeters;
        private final double widthMeters;
        private final double heightMeters;
        private final double unitMeters;
        private final int nextIndex;

        private Shape(double radiusMeters, double widthMeters,
                      double heightMeters, double unitMeters,
                      int nextIndex) {
            this.radiusMeters = radiusMeters;
            this.widthMeters = widthMeters;
            this.heightMeters = heightMeters;
            this.unitMeters = unitMeters;
            this.nextIndex = nextIndex;
        }

        private static Shape radius(double radius, String unit,
                                    int nextIndex) {
            return new Shape(radius * toMeters(unit), -1, -1,
                    toMeters(unit), nextIndex);
        }

        private static Shape box(double width, double height,
                                 String unit, int nextIndex) {
            return new Shape(-1, width * toMeters(unit),
                    height * toMeters(unit), toMeters(unit),
                    nextIndex);
        }
    }

    private static final class Options {
        private int sort;
        private boolean withDist;
        private boolean withHash;
        private boolean withCoord;
        private long count;
        private boolean any;
    }

    private static final class InvalidCoordinateException
            extends RuntimeException {
        private final double longitude;
        private final double latitude;

        private InvalidCoordinateException(double longitude,
                                           double latitude) {
            this.longitude = longitude;
            this.latitude = latitude;
        }
    }

    private static final class RadiusException
            extends RuntimeException {
    }

    private static final class BoxException
            extends RuntimeException {
    }

    private static final class UnitException
            extends RuntimeException {
    }

    private static final class CountException
            extends RuntimeException {
    }

    private static final class AnyException
            extends RuntimeException {
    }
}
