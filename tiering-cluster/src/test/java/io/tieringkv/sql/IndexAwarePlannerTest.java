package io.tieringkv.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v4 阶段一：索引感知计划器。 */
class IndexAwarePlannerTest {

    @Test
    void indexedColumnPreferred() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.register("users", "email", true, 1000);
        IndexAwarePlanner planner = new IndexAwarePlanner(registry);
        assertThat(planner.preferIndexedScan("users", "email"))
                .isTrue();
    }

    @Test
    void unindexedColumnNotPreferred() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        IndexAwarePlanner planner = new IndexAwarePlanner(registry);
        assertThat(planner.preferIndexedScan("users", "name"))
                .isFalse();
    }

    @Test
    void emptyIndexNotPreferred() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.register("users", "id", false, 0);
        IndexAwarePlanner planner = new IndexAwarePlanner(registry);
        assertThat(planner.preferIndexedScan("users", "id"))
                .isFalse();
    }

    @Test
    void planHintCarriesEntries() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.register("orders", "order_id", false, 500);
        IndexAwarePlanner planner = new IndexAwarePlanner(registry);
        IndexAwarePlanner.PlanHint hint =
                planner.plan("orders", "order_id");
        assertThat(hint.indexed()).isTrue();
        assertThat(hint.entries()).isEqualTo(500);
    }

    @Test
    void nullRegistryRejected() {
        assertThatThrownBy(() -> new IndexAwarePlanner(null));
    }

    @Test
    void blankArgsRejected() {
        IndexAwarePlanner planner = new IndexAwarePlanner(
                new SqlIndexRegistry());
        assertThatThrownBy(() -> planner.plan("", "c"));
        assertThatThrownBy(() -> planner.plan("t", ""));
    }

    private static void assertThatThrownBy(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @ParameterizedTest(name = "table={0} column={1} indexed={2}")
    @MethodSource("matrix")
    void planMatrix(String table, String column, boolean indexed) {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        if (indexed) {
            registry.register(table, column, false, 10);
        }
        IndexAwarePlanner planner = new IndexAwarePlanner(registry);
        IndexAwarePlanner.PlanHint hint =
                planner.plan(table, column);
        assertThat(hint.indexed()).isEqualTo(indexed);
        assertThat(planner.preferIndexedScan(table, column))
                .isEqualTo(indexed);
    }

    @ParameterizedTest(name = "tables {0}")
    @MethodSource("tableCounts")
    void multiTablePlans(int count) {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        for (int i = 0; i < count; i++) {
            registry.register("t" + i, "c" + i, false, i + 1);
        }
        IndexAwarePlanner planner = new IndexAwarePlanner(registry);
        for (int i = 0; i < count; i++) {
            assertThat(planner.preferIndexedScan("t" + i, "c" + i))
                    .isTrue();
        }
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of("users", "email", true),
                Arguments.of("users", "name", false),
                Arguments.of("orders", "id", true),
                Arguments.of("orders", "user_id", false),
                Arguments.of("products", "sku", true),
                Arguments.of("events", "ts", false));
    }

    static Stream<Arguments> tableCounts() {
        return Stream.of(1, 3, 8, 15).map(Arguments::of);
    }
}
