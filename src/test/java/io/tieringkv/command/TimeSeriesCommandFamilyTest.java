package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespDouble;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 时序命令族（ADR-0337）：RANGE 聚合/下采样、INCRBY、MRANGE、REDUCE。 */
class TimeSeriesCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create(),
                CommandRegistry.createDefaultWithVector(
                        () -> "# Server\r\nno metrics\r\n",
                        Map.of(), new VectorIndexStore(4)));
    }

    private static long integer(RespValue value) {
        return ((RespInteger) value).value();
    }

    private static void addSeries(TestCommandRunner runner,
                                  String key) {
        runner.exec("ts.add", key, "1000", "1");
        runner.exec("ts.add", key, "2000", "2");
        runner.exec("ts.add", key, "3000", "3");
        runner.exec("ts.add", key, "4100", "4");
        runner.exec("ts.add", key, "5000", "5");
    }

    /** 样本数组 → {ts: value}（断言聚合桶）。 */
    private static Map<Long, Double> samples(RespValue value) {
        Map<Long, Double> result = new LinkedHashMap<>();
        for (RespValue item : ((RespArray) value).values()) {
            RespArray sample = (RespArray) item;
            long ts = ((RespInteger) sample.values().get(0)).value();
            double sampleValue =
                    ((RespDouble) sample.values().get(1)).value();
            result.put(ts, sampleValue);
        }
        return result;
    }

    @Test
    void tsRangeReturnsPointsInclusiveRange() {
        TestCommandRunner runner = runner();
        addSeries(runner, "k");
        Map<Long, Double> result = samples(runner.exec(
                "ts.range", "k", "1500", "4500"));
        assertThat(result.keySet())
                .containsExactly(2000L, 3000L, 4100L);
        assertThat(result.get(2000L)).isEqualTo(2.0);
        Map<Long, Double> all = samples(runner.exec(
                "ts.range", "k", "-inf", "+inf"));
        assertThat(all).hasSize(5);
    }

    @Test
    void tsRangeMissingKeyReturnsEmpty() {
        TestCommandRunner runner = runner();
        RespArray result = (RespArray) runner.exec(
                "ts.range", "nope", "0", "+inf");
        assertThat(result.values()).isEmpty();
    }

    @Test
    void tsRangeWrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "plain");
        assertThat(runner.exec("ts.range", "k", "0", "+inf"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void tsRangeSortsUnsortedPoints() {
        TestCommandRunner runner = runner();
        runner.exec("ts.add", "k", "3000", "3");
        runner.exec("ts.add", "k", "1000", "1");
        runner.exec("ts.add", "k", "2000", "2");
        Map<Long, Double> result = samples(runner.exec(
                "ts.range", "k", "0", "+inf"));
        assertThat(result.keySet())
                .containsExactly(1000L, 2000L, 3000L);
    }

    @Test
    void tsRangeAggregationAlignsBuckets() {
        TestCommandRunner runner = runner();
        addSeries(runner, "k");
        Map<Long, Double> avg = samples(runner.exec(
                "ts.range", "k", "0", "+inf",
                "aggregation", "avg", "2000"));
        assertThat(avg.keySet()).containsExactly(0L, 2000L, 4000L);
        assertThat(avg.get(0L)).isEqualTo(1.0);
        assertThat(avg.get(2000L)).isEqualTo(2.5);
        assertThat(avg.get(4000L)).isEqualTo(4.5);
    }

    @Test
    void tsRangeAggregationMatrix() {
        TestCommandRunner runner = runner();
        addSeries(runner, "k");
        assertThat(samples(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "sum", "2000")).get(2000L))
                .isEqualTo(5.0);
        assertThat(samples(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "min", "2000")).get(4000L))
                .isEqualTo(4.0);
        assertThat(samples(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "max", "2000")).get(4000L))
                .isEqualTo(5.0);
        assertThat(samples(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "count", "2000")).get(2000L))
                .isEqualTo(2.0);
        assertThat(samples(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "first", "2000")).get(2000L))
                .isEqualTo(2.0);
        assertThat(samples(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "last", "2000")).get(2000L))
                .isEqualTo(3.0);
    }

    @Test
    void tsRangeCountLimitsOutput() {
        TestCommandRunner runner = runner();
        addSeries(runner, "k");
        Map<Long, Double> result = samples(runner.exec(
                "ts.range", "k", "0", "+inf", "count", "2"));
        assertThat(result.keySet())
                .containsExactly(1000L, 2000L);
    }

    @Test
    void tsRangeInvalidAggregationAndBucketRejected() {
        TestCommandRunner runner = runner();
        addSeries(runner, "k");
        assertThat(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "bogus", "2000"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("ts.range", "k", "0", "+inf",
                "aggregation", "avg", "0"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void tsRangeNegativeTimestampBucketAlignment() {
        TestCommandRunner runner = runner();
        runner.exec("ts.add", "k", "-1000", "1");
        runner.exec("ts.add", "k", "0", "2");
        runner.exec("ts.add", "k", "1000", "3");
        Map<Long, Double> avg = samples(runner.exec(
                "ts.range", "k", "-inf", "+inf",
                "aggregation", "avg", "2000"));
        assertThat(avg.keySet()).containsExactly(-2000L, 0L);
        assertThat(avg.get(-2000L)).isEqualTo(1.0);
        assertThat(avg.get(0L)).isEqualTo(2.5);
    }

    @Test
    void tsIncrByCreatesAndIncrements() {
        TestCommandRunner runner = runner();
        assertThat(integer(runner.exec("ts.incrby", "k", "3",
                "timestamp", "1000"))).isEqualTo(1000);
        Map<Long, Double> first = samples(runner.exec(
                "ts.range", "k", "0", "+inf"));
        assertThat(first.get(1000L)).isEqualTo(3.0);
        assertThat(integer(runner.exec("ts.incrby", "k", "4",
                "timestamp", "1000"))).isEqualTo(1000);
        Map<Long, Double> second = samples(runner.exec(
                "ts.range", "k", "0", "+inf"));
        assertThat(second).hasSize(1);
        assertThat(second.get(1000L)).isEqualTo(7.0);
        assertThat(integer(runner.exec("ts.incrby", "k", "5",
                "timestamp", "2000"))).isEqualTo(2000);
        assertThat(samples(runner.exec("ts.range", "k", "0",
                "+inf"))).hasSize(2);
    }

    @Test
    void tsIncrByWithoutTimestampKeepsTotal() {
        TestCommandRunner runner = runner();
        assertThat(integer(runner.exec("ts.incrby", "k", "2")))
                .isPositive();
        assertThat(integer(runner.exec("ts.incrby", "k", "3")))
                .isPositive();
        Map<Long, Double> points = samples(runner.exec(
                "ts.range", "k", "0", "+inf"));
        assertThat(points.size()).isBetween(1, 2);
        assertThat(points.values().stream()
                .mapToDouble(Double::doubleValue).sum())
                .isEqualTo(5.0);
    }

    @Test
    void tsIncrByInvalidArgumentsRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "str", "plain");
        assertThat(runner.exec("ts.incrby", "k", "abc"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("ts.incrby", "k", "1", "timestamp"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("ts.incrby", "str", "1"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void tsMRangeReturnsAllSeriesSortedByKey() {
        TestCommandRunner runner = runner();
        runner.exec("ts.add", "a", "1000", "1");
        runner.exec("ts.add", "a", "2000", "2");
        runner.exec("ts.add", "b", "1500", "5");
        runner.exec("set", "str", "plain");
        RespArray result = (RespArray) runner.exec(
                "ts.mrange", "0", "3000");
        assertThat(result.values()).hasSize(2);
        RespArray first = (RespArray) result.values().get(0);
        assertThat(new String(((RespBulkString) first.values().get(0))
                .bytes(), StandardCharsets.UTF_8)).isEqualTo("a");
        assertThat(samples(first.values().get(1)).keySet())
                .containsExactly(1000L, 2000L);
        RespArray second = (RespArray) result.values().get(1);
        assertThat(new String(((RespBulkString) second.values().get(0))
                .bytes(), StandardCharsets.UTF_8)).isEqualTo("b");
    }

    @Test
    void tsMRangeWithAggregation() {
        TestCommandRunner runner = runner();
        runner.exec("ts.add", "a", "1000", "1");
        runner.exec("ts.add", "a", "2000", "2");
        runner.exec("ts.add", "a", "3000", "3");
        runner.exec("ts.add", "b", "1000", "10");
        RespArray result = (RespArray) runner.exec(
                "ts.mrange", "0", "+inf", "aggregation", "sum",
                "2000");
        assertThat(result.values()).hasSize(2);
        Map<Long, Double> a = samples(
                ((RespArray) result.values().get(0)).values().get(1));
        assertThat(a.get(0L)).isEqualTo(1.0);
        assertThat(a.get(2000L)).isEqualTo(5.0);
        Map<Long, Double> b = samples(
                ((RespArray) result.values().get(1)).values().get(1));
        assertThat(b.get(0L)).isEqualTo(10.0);
    }

    @Test
    void tsMRangeEmptyResult() {
        TestCommandRunner runner = runner();
        RespArray result = (RespArray) runner.exec(
                "ts.mrange", "0", "+inf");
        assertThat(result.values()).isEmpty();
    }

    @Test
    void tsReduceAggregatesWholeSeries() {
        TestCommandRunner runner = runner();
        addSeries(runner, "k");
        RespArray sum = (RespArray) runner.exec(
                "ts.reduce", "k", "aggregation", "sum");
        assertThat(((RespInteger) sum.values().get(0)).value())
                .isEqualTo(1000);
        assertThat(((RespDouble) sum.values().get(1)).value())
                .isEqualTo(15.0);
        RespArray avg = (RespArray) runner.exec(
                "ts.reduce", "k", "aggregation", "avg");
        assertThat(((RespDouble) avg.values().get(1)).value())
                .isEqualTo(3.0);
        RespArray count = (RespArray) runner.exec(
                "ts.reduce", "k", "aggregation", "count");
        assertThat(((RespDouble) count.values().get(1)).value())
                .isEqualTo(5.0);
        RespArray min = (RespArray) runner.exec(
                "ts.reduce", "k", "aggregation", "min");
        assertThat(((RespDouble) min.values().get(1)).value())
                .isEqualTo(1.0);
        RespArray max = (RespArray) runner.exec(
                "ts.reduce", "k", "aggregation", "max");
        assertThat(((RespDouble) max.values().get(1)).value())
                .isEqualTo(5.0);
    }

    @Test
    void tsReduceEmptyAndWrongType() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("ts.reduce", "nope",
                "aggregation", "sum"))
                .isEqualTo(RespNull.ARRAY);
        runner.exec("set", "k", "plain");
        assertThat(runner.exec("ts.reduce", "k",
                "aggregation", "sum"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void tsWrongArityRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("ts.range", "k", "1"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("ts.mrange", "1"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("ts.incrby", "k"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("ts.reduce", "k", "sum"))
                .isInstanceOf(RespError.class);
    }
}
