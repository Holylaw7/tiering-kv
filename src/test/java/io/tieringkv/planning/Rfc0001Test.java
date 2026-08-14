package io.tieringkv.planning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC-0001 与 ADR-0318（v4 规划）。 */
class Rfc0001Test {

    @Test
    void rfcExists() {
        assertThat(Path.of("docs", "planning",
                "rfc-0001-v4-multi-model.md").toFile()).exists();
    }

    @Test
    void adr318Exists() {
        assertThat(Path.of("docs", "adr",
                "ADR-0318-v4-multi-model-rfc-and-feature-plan.md")
                .toFile()).exists();
    }

    @Test
    void rfcHasAllSections() throws Exception {
        String rfc = Files.readString(Path.of("docs", "planning",
                "rfc-0001-v4-multi-model.md"));
        assertThat(rfc).contains("摘要", "动机", "设计", "备选",
                "兼容性", "影响范围", "评审结论");
    }

    @Test
    void roadmapReferencesRfc() throws Exception {
        assertThat(Files.readString(Path.of("docs", "planning",
                "v4-roadmap.md"))).contains("RFC-0001",
                "feature/v4-multi-model");
    }

    @ParameterizedTest(name = "token {0}")
    @MethodSource("tokens")
    void rfcCarriesTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("docs", "planning",
                "rfc-0001-v4-multi-model.md"))).contains(token);
    }

    static Stream<Arguments> tokens() {
        return Stream.of("SQL", "向量", "HNSW", "additive",
                        "feature/v4-multi-model", "Approved")
                .map(Arguments::of);
    }
}
