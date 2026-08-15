package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** GEO 命令族（ADR-0335）：geohash ZSET 存储 + 半径/矩形检索。 */
class GeoCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    private static long integer(RespValue value) {
        return ((RespInteger) value).value();
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }

    private static double doubleOf(RespValue value) {
        return Double.parseDouble(text(value));
    }

    private static void addSicily(TestCommandRunner runner) {
        assertThat(integer(runner.exec("geoadd", "sicily",
                "13.361389", "38.115556", "Palermo",
                "15.087269", "37.502669", "Catania")))
                .isEqualTo(2);
    }

    @Test
    void geoaddCountsAndOptions() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        assertThat(integer(runner.exec("geoadd", "sicily",
                "13.361389", "38.115556", "Palermo")))
                .isZero();
        assertThat(integer(runner.exec("geoadd", "sicily",
                "ch", "13.5", "38.1", "Palermo"))).isEqualTo(1);
        assertThat(integer(runner.exec("geoadd", "sicily",
                "ch", "13.5", "38.1", "Palermo"))).isZero();
        assertThat(integer(runner.exec("geoadd", "sicily",
                "nx", "13.5", "38.1", "Palermo",
                "14.0", "37.0", "New"))).isEqualTo(1);
        assertThat(integer(runner.exec("geoadd", "sicily",
                "xx", "14.0", "37.0", "NotAdded"))).isZero();
    }

    @Test
    void geoposReturnsCoordinatesWithinTolerance() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        RespArray result = (RespArray) runner.exec(
                "geopos", "sicily", "Palermo", "Catania",
                "nope");
        assertThat(result.values()).hasSize(3);
        RespArray palermo = (RespArray) result.values().get(0);
        assertThat(doubleOf(palermo.values().get(0)))
                .isCloseTo(13.361389, within(0.001));
        assertThat(doubleOf(palermo.values().get(1)))
                .isCloseTo(38.115556, within(0.001));
        RespArray catania = (RespArray) result.values().get(1);
        assertThat(doubleOf(catania.values().get(0)))
                .isCloseTo(15.087269, within(0.001));
        assertThat(result.values().get(2))
                .isEqualTo(RespNull.ARRAY);
    }

    @Test
    void geodistMatchesRedisDocBaseline() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        assertThat(doubleOf(runner.exec("geodist", "sicily",
                "Palermo", "Catania"))).isCloseTo(166274.1516,
                within(0.5));
        assertThat(doubleOf(runner.exec("geodist", "sicily",
                "Palermo", "Catania", "km"))).isCloseTo(166.2742,
                within(0.001));
        assertThat(doubleOf(runner.exec("geodist", "sicily",
                "Palermo", "Catania", "mi"))).isCloseTo(103.3182,
                within(0.05));
        assertThat(runner.exec("geodist", "sicily",
                "Palermo", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void geohashMatchesRedisDocBaseline() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        RespArray result = (RespArray) runner.exec(
                "geohash", "sicily", "Palermo", "Catania",
                "nope");
        assertThat(result.values()).hasSize(3);
        assertThat(text(result.values().get(0)))
                .isEqualTo("sqc8b49rny0");
        assertThat(text(result.values().get(1)))
                .isEqualTo("sqdtr74hyu0");
        assertThat(result.values().get(2))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void geosearchByRadiusSortsByDistance() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        RespArray byLonLat = (RespArray) runner.exec(
                "geosearch", "sicily", "fromlonlat", "15", "37",
                "byradius", "200", "km", "asc");
        assertThat(text(byLonLat.values().get(0)))
                .isEqualTo("Catania");
        assertThat(text(byLonLat.values().get(1)))
                .isEqualTo("Palermo");

        RespArray fromMember = (RespArray) runner.exec(
                "geosearch", "sicily", "frommember", "Palermo",
                "byradius", "200", "km", "asc");
        assertThat(text(fromMember.values().get(0)))
                .isEqualTo("Palermo");
        assertThat(fromMember.values()).hasSize(2);
    }

    @Test
    void geosearchByBoxAndCount() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        RespArray box = (RespArray) runner.exec(
                "geosearch", "sicily", "frommember", "Palermo",
                "bybox", "400", "400", "km");
        assertThat(box.values()).hasSize(2);
        RespArray count = (RespArray) runner.exec(
                "geosearch", "sicily", "frommember", "Palermo",
                "byradius", "200", "km", "count", "1");
        assertThat(count.values()).hasSize(1);
        assertThat(text(count.values().get(0)))
                .isEqualTo("Palermo");
    }

    @Test
    void geosearchWithOptions() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        RespArray result = (RespArray) runner.exec(
                "geosearch", "sicily", "frommember", "Palermo",
                "byradius", "200", "km", "asc", "withdist",
                "withhash", "withcoord");
        RespArray first = (RespArray) result.values().get(0);
        assertThat(first.values()).hasSize(4);
        assertThat(text(first.values().get(0)))
                .isEqualTo("Palermo");
        assertThat(doubleOf(first.values().get(1))).isZero();
        assertThat(((RespInteger) first.values().get(2)).value())
                .isPositive();
        RespArray coord = (RespArray) first.values().get(3);
        assertThat(coord.values()).hasSize(2);
    }

    @Test
    void georadiusLegacyCompatibility() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        RespArray radius = (RespArray) runner.exec(
                "georadius", "sicily", "15", "37", "200", "km",
                "asc");
        assertThat(text(radius.values().get(0)))
                .isEqualTo("Catania");
        RespArray byMember = (RespArray) runner.exec(
                "georadiusbymember", "sicily", "Palermo", "200",
                "km", "asc");
        assertThat(text(byMember.values().get(0)))
                .isEqualTo("Palermo");
        assertThat(byMember.values()).hasSize(2);
    }

    @Test
    void invalidCoordinatesRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("geoadd", "g", "181", "0", "m"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("geoadd", "g", "0", "86", "m"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("geoadd", "g", "0", "-86", "m"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("geoadd", "g", "notnum", "0", "m"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void missingKeyAndUnknownMember() {
        TestCommandRunner runner = runner();
        RespArray empty = (RespArray) runner.exec("geosearch",
                "nope", "frommember", "m", "byradius", "10", "km");
        assertThat(empty.values()).isEmpty();
        RespArray geoPos = (RespArray) runner.exec("geopos",
                "nope", "m");
        assertThat(geoPos.values().get(0))
                .isEqualTo(RespNull.ARRAY);
    }

    @Test
    void typeIsZsetAndZsetCommandsCompatible() {
        TestCommandRunner runner = runner();
        addSicily(runner);
        assertThat(runner.exec("type", "sicily"))
                .isEqualTo(new RespSimpleString("zset"));
        RespArray range = (RespArray) runner.exec("zrange",
                "sicily", "0", "-1");
        assertThat(range.values()).hasSize(2);
        assertThat(((RespBulkString) range.values().get(0))
                .bytes()).isEqualTo("Palermo".getBytes(
                StandardCharsets.UTF_8));
        RespValue score = runner.exec("zscore", "sicily",
                "Palermo");
        assertThat(score).isInstanceOf(RespBulkString.class);
        assertThat(Long.parseLong(text(score))).isPositive();
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("geoadd", "k", "13", "38", "m"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("geopos", "k", "m"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void syntaxErrorsRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("geosearch", "k", "frommember",
                "m", "byradius", "10"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("geodist", "k", "a"))
                .isInstanceOf(RespError.class);
    }

}
