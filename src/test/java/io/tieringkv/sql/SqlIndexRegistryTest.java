package io.tieringkv.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** v4 阶段一：SQL 索引注册表。 */
class SqlIndexRegistryTest {

    @Test
    void registerAndLookup() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.register("users", "email", true, 1000);
        assertThat(registry.hasIndex("users", "email")).isTrue();
        assertThat(registry.index("users", "email").unique())
                .isTrue();
    }

    @Test
    void missingIndexNotFound() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        assertThat(registry.hasIndex("users", "nope")).isFalse();
    }

    @Test
    void invalidArgsRejected() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        assertThatThrownBy(() -> registry.register("", "c",
                false, 0));
        assertThatThrownBy(() -> registry.register("t", "c",
                false, -1));
    }

    private static void assertThatThrownBy(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @ParameterizedTest(name = "table={0} column={1}")
    @CsvSource({
            "users, email",
            "users, id",
            "orders, order_id",
            "orders, user_id",
            "products, sku",
            "events, ts"
    })
    void registerMatrix(String table, String column) {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.register(table, column, false, 100);
        assertThat(registry.hasIndex(table, column)).isTrue();
        assertThat(registry.index(table, column).entries())
                .isEqualTo(100);
    }

    @ParameterizedTest(name = "tables {0}")
    @MethodSource("tableCounts")
    void multipleIndexes(int count) {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        for (int i = 0; i < count; i++) {
            registry.register("t" + i, "c" + i, false, i);
        }
        assertThat(registry.size()).isEqualTo(count);
    }

    static Stream<Arguments> tableCounts() {
        return Stream.of(1, 5, 10, 25).map(Arguments::of);
    }
}
