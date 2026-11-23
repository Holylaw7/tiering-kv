package io.tieringkv.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 文档产品化检查清单（ADR-0302）。 */
class DocumentationChecklistTest {

    @Test
    void quickstartExists() {
        assertThat(Path.of("docs", "operations",
                "quickstart.md").toFile()).exists();
    }

    @Test
    void runbookExists() {
        assertThat(Path.of("docs", "operations",
                "operations-runbook.md").toFile()).exists();
    }

    @Test
    void whitepaperExists() {
        assertThat(Path.of("docs", "benchmark",
                "final-performance-whitepaper.md").toFile())
                .exists();
    }

    @Test
    void readmeHasQuickstart() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("快速开始")
                .contains("redis-cli")
                .contains("能力矩阵")
                .contains("目录与文档");
    }

    @Test
    void readmeHasNoPlaceholders() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).doesNotContain("TODO")
                .doesNotContain("占位")
                .doesNotContain("待补充");
    }

    @Test
    void quickstartHasNoPlaceholders() throws Exception {
        String doc = Files.readString(Path.of("docs", "operations",
                "quickstart.md"));
        assertThat(doc).doesNotContain("TODO")
                .doesNotContain("占位");
    }

    @Test
    void runbookHasNoPlaceholders() throws Exception {
        String doc = Files.readString(Path.of("docs", "operations",
                "operations-runbook.md"));
        assertThat(doc).doesNotContain("TODO")
                .doesNotContain("占位");
    }

    @ParameterizedTest(name = "quickstart {0}")
    @MethodSource("quickstartTokens")
    void quickstartCarriesTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("docs", "operations",
                "quickstart.md"))).contains(token);
    }

    @ParameterizedTest(name = "runbook {0}")
    @MethodSource("runbookTokens")
    void runbookCarriesTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("docs", "operations",
                "operations-runbook.md"))).contains(token);
    }

    @ParameterizedTest(name = "whitepaper {0}")
    @MethodSource("whitepaperTokens")
    void whitepaperCarriesTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("docs", "benchmark",
                "final-performance-whitepaper.md"))).contains(token);
    }

    static Stream<Arguments> quickstartTokens() {
        return Stream.of("mvn -q package", "java -jar", "redis-cli",
                        "SET", "GET", "PING", "5 分钟", "配置")
                .map(Arguments::of);
    }

    static Stream<Arguments> runbookTokens() {
        return Stream.of("启动", "停机", "监控", "备份", "恢复",
                        "升级", "故障排查", "SLO")
                .map(Arguments::of);
    }

    static Stream<Arguments> whitepaperTokens() {
        return Stream.of("LOCAL", "ops/s", "WAL append",
                        "MULTI/EXEC", "内存",
                        "容量模型", "口径")
                .map(Arguments::of);
    }
}
