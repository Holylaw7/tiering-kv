package io.tieringkv.storage.types;

/**
 * GeoHash 编解码与距离（ADR-0335）：与 Redis geohash.c / geo.c 同款。
 *
 * <p>存储 score：52 位（lat/lon 各 26 bit），lat 偶位、lon 奇位，
 * lat 范围 [-85.05112878, 85.05112878]（EPSG:900913 约束）；
 * GEOHASH 字符串：先按存储范围解码，再用标准 [-90,90] 范围重编码，
 * 输出 10 字符 + 恒 '0'（Redis GEOHASH 兼容口径）；
 * 距离：WGS-84 二次平均半径 6372797.560856m haversine。
 */
public final class GeoHash {

    public static final double LAT_MIN = -85.05112878;
    public static final double LAT_MAX = 85.05112878;
    public static final double LON_MIN = -180.0;
    public static final double LON_MAX = 180.0;
    public static final int STEP = 26;
    public static final double EARTH_RADIUS_METERS = 6372797.560856;

    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final char[] BASE32 =
            "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

    /** 经纬度坐标（longitude, latitude）。 */
    public record Point(double longitude, double latitude) {
    }

    private GeoHash() {
    }

    public static boolean valid(double longitude, double latitude) {
        return longitude >= LON_MIN && longitude <= LON_MAX
                && latitude >= LAT_MIN && latitude <= LAT_MAX;
    }

    /** Redis GEOADD 编码：52 位 score，lat 偶位 / lon 奇位。 */
    public static long encode(double longitude, double latitude) {
        long latBits = toFixed(latitude, LAT_MIN, LAT_MAX);
        long lonBits = toFixed(longitude, LON_MIN, LON_MAX);
        return interleave(latBits, lonBits);
    }

    /** 解码为单元格中心坐标（Redis geohashDecodeAreaToLongLat 同款）。 */
    public static Point decode(long score) {
        long latBits = 0;
        long lonBits = 0;
        for (int i = 0; i < STEP; i++) {
            latBits |= ((score >> (2 * i)) & 1L) << i;
            lonBits |= ((score >> (2 * i + 1)) & 1L) << i;
        }
        double latitude = LAT_MIN
                + (latBits + 0.5) / (1L << STEP) * (LAT_MAX - LAT_MIN);
        double longitude = LON_MIN
                + (lonBits + 0.5) / (1L << STEP) * (LON_MAX - LON_MIN);
        return new Point(longitude, latitude);
    }

    /** Redis GEOHASH 字符串：±85.05 解码 → ±90 重编码 → 10 字符 + '0'。 */
    public static String toHashString(long score) {
        Point point = decode(score);
        long latBits = toFixed(point.latitude(), -90.0, 90.0);
        long lonBits = toFixed(point.longitude(), -180.0, 180.0);
        long bits = interleave(latBits, lonBits);
        char[] result = new char[11];
        for (int i = 0; i < 10; i++) {
            int index = (int) ((bits >> (52 - (i + 1) * 5)) & 0x1f);
            result[i] = BASE32[index];
        }
        result[10] = '0';
        return new String(result);
    }

    /** Redis geohashGetDistance：WGS-84 haversine（lon 相同时走纬度弧）。 */
    public static double distanceMeters(Point a, Point b) {
        double lon1r = a.longitude() * DEG_TO_RAD;
        double lon2r = b.longitude() * DEG_TO_RAD;
        double v = Math.sin((lon2r - lon1r) / 2);
        if (v == 0.0) {
            return EARTH_RADIUS_METERS * Math.abs(
                    a.latitude() * DEG_TO_RAD
                            - b.latitude() * DEG_TO_RAD);
        }
        double lat1r = a.latitude() * DEG_TO_RAD;
        double lat2r = b.latitude() * DEG_TO_RAD;
        double u = Math.sin((lat2r - lat1r) / 2);
        double h = u * u + Math.cos(lat1r) * Math.cos(lat2r) * v * v;
        return 2.0 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }

    /**
     * Redis geohashGetDistanceIfInRectangle：轴对齐矩形判定。
     * 返回中心到点的 haversine 距离；在矩形外返回 -1。
     */
    public static double distanceInRectangle(double widthMeters,
                                             double heightMeters,
                                             Point center,
                                             Point point) {
        double latDistance = EARTH_RADIUS_METERS * Math.abs(
                point.latitude() * DEG_TO_RAD
                        - center.latitude() * DEG_TO_RAD);
        if (latDistance > heightMeters / 2) {
            return -1;
        }
        double lonDistance = distanceMeters(
                new Point(point.longitude(), point.latitude()),
                new Point(center.longitude(), point.latitude()));
        if (lonDistance > widthMeters / 2) {
            return -1;
        }
        return distanceMeters(center, point);
    }

    private static long toFixed(double value, double min, double max) {
        double offset = (value - min) / (max - min) * (1L << STEP);
        long bits = (long) offset;
        return Math.max(0, Math.min(bits, (1L << STEP) - 1));
    }

    private static long interleave(long latBits, long lonBits) {
        long hash = 0;
        for (int i = 0; i < STEP; i++) {
            long pair = (((lonBits >> i) & 1L) << 1)
                    | ((latBits >> i) & 1L);
            hash |= pair << (2 * i);
        }
        return hash;
    }
}
