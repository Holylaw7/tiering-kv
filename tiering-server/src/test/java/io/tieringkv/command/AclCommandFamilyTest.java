package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ACL 命令族（ADR-0340）：只读子集 WHOAMI/LIST/CAT/GETUSER。 */
class AclCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void whoamiReturnsDefault() {
        TestCommandRunner runner = runner();
        assertThat(text(runner.exec("acl", "whoami")))
                .isEqualTo("default");
    }

    @Test
    void listContainsDefaultUserRule() {
        TestCommandRunner runner = runner();
        RespArray list = (RespArray) runner.exec("acl", "list");
        assertThat(list.values()).hasSize(1);
        assertThat(text(list.values().get(0)))
                .isEqualTo("user default on nopass ~* &* +@all");
    }

    @Test
    void catListsCategories() {
        TestCommandRunner runner = runner();
        RespArray categories = (RespArray) runner.exec("acl", "cat");
        Set<String> names = new HashSet<>();
        for (RespValue category : categories.values()) {
            names.add(text(category));
        }
        assertThat(names).contains(
                "generic", "string", "list", "set", "zset", "hash",
                "stream", "pubsub", "transaction", "scripting",
                "json", "timeseries", "vector");
    }

    @Test
    void catUnknownCategoryRejected() {
        TestCommandRunner runner = runner();
        RespValue result = runner.exec("acl", "cat", "bogus");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("Unknown category");
    }

    @Test
    void getuserDefaultReturnsRules() {
        TestCommandRunner runner = runner();
        RespArray rules = (RespArray) runner.exec(
                "acl", "getuser", "default");
        Set<String> values = new HashSet<>();
        for (RespValue rule : rules.values()) {
            values.add(text(rule));
        }
        assertThat(values).contains(
                "on", "nopass", "~*", "&*", "+@all");
    }

    @Test
    void unknownSubcommandAndArityRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("acl"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("acl", "bogus"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("acl", "getuser"))
                .isInstanceOf(RespError.class);
    }
}
