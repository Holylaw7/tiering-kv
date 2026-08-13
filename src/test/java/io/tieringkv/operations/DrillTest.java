package io.tieringkv.operations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 升级/备份演练（ADR-0299）。 */
class DrillTest {

    @Test
    void upgradeScriptExists() {
        assertThat(Path.of("scripts", "upgrade-drill.sh")
                .toFile()).exists();
    }

    @Test
    void restoreScriptExists() {
        assertThat(Path.of("scripts", "restore-drill.sh")
                .toFile()).exists();
    }

    @Test
    void upgradeScriptIsExecutable() throws Exception {
        String script = Files.readString(Path.of("scripts",
                "upgrade-drill.sh"));
        assertThat(script).startsWith("#!/usr/bin/env bash");
        assertThat(script).contains("set -euo pipefail");
    }

    @Test
    void restoreScriptIsExecutable() throws Exception {
        String script = Files.readString(Path.of("scripts",
                "restore-drill.sh"));
        assertThat(script).startsWith("#!/usr/bin/env bash");
        assertThat(script).contains("set -euo pipefail");
    }

    @ParameterizedTest(name = "upgrade token {0}")
    @MethodSource("upgradeTokens")
    void upgradeScriptCarriesTokens(String token)
            throws Exception {
        assertThat(Files.readString(Path.of("scripts",
                "upgrade-drill.sh"))).contains(token);
    }

    @ParameterizedTest(name = "restore token {0}")
    @MethodSource("restoreTokens")
    void restoreScriptCarriesTokens(String token)
            throws Exception {
        assertThat(Files.readString(Path.of("scripts",
                "restore-drill.sh"))).contains(token);
    }

    @ParameterizedTest(name = "doc token {0}")
    @MethodSource("docTokens")
    void drillDocCarriesTokens(String token) throws Exception {
        assertThat(Files.readString(Path.of("docs", "operations",
                "upgrade-backup-drills.md"))).contains(token);
    }

    static Stream<Arguments> upgradeTokens() {
        return Stream.of("upgrade drill", "NODES", "parity",
                        "sha256sum", "while IFS", "OLD", "NEW")
                .map(Arguments::of);
    }

    static Stream<Arguments> restoreTokens() {
        return Stream.of("restore drill", "BACKUP", "snapshot",
                        "WAL", "MVCC", "exit 1", "ls -la")
                .map(Arguments::of);
    }

    static Stream<Arguments> docTokens() {
        return Stream.of("upgrade-drill.sh", "restore-drill.sh",
                        "追平等待", "奇偶校验", "快照", "MVCC 索引",
                        "数据校验")
                .map(Arguments::of);
    }
}
