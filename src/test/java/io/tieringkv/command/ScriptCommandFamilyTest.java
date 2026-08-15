package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** SCRIPT 命令族（ADR-0340）：LOAD/EXISTS/FLUSH + EVAL 显式不可用。 */
class ScriptCommandFamilyTest {

    private static final Pattern SHA1_HEX = Pattern.compile(
            "[0-9a-f]{40}");

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void scriptLoadReturnsSha1AndExists() {
        TestCommandRunner runner = runner();
        String sha = text(runner.exec("script", "load",
                "return 1"));
        assertThat(SHA1_HEX.matcher(sha).matches()).isTrue();
        RespArray exists = (RespArray) runner.exec(
                "script", "exists", sha);
        assertThat(((RespInteger) exists.values().get(0)).value())
                .isEqualTo(1);
        RespArray missing = (RespArray) runner.exec(
                "script", "exists", "0".repeat(40));
        assertThat(((RespInteger) missing.values().get(0)).value())
                .isZero();
    }

    @Test
    void scriptFlushClearsRegistry() {
        TestCommandRunner runner = runner();
        String sha = text(runner.exec("script", "load",
                "return 2"));
        assertThat(runner.exec("script", "flush"))
                .isEqualTo(new RespSimpleString("OK"));
        RespArray exists = (RespArray) runner.exec(
                "script", "exists", sha);
        assertThat(((RespInteger) exists.values().get(0)).value())
                .isZero();
    }

    @Test
    void evalExplicitlyUnavailable() {
        TestCommandRunner runner = runner();
        RespValue eval = runner.exec("eval", "return 1", "0");
        assertThat(eval).isInstanceOf(RespError.class);
        assertThat(((RespError) eval).message())
                .contains("scripting engine not available");
        String sha = text(runner.exec("script", "load",
                "return 1"));
        RespValue evalsha = runner.exec("evalsha", sha, "0");
        assertThat(evalsha).isInstanceOf(RespError.class);
        assertThat(((RespError) evalsha).message())
                .contains("scripting engine not available");
    }

    @Test
    void unknownSubcommandAndArityRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("script"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("script", "bogus"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("script", "load"))
                .isInstanceOf(RespError.class);
    }
}
