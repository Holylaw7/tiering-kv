package io.tieringkv.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AclPolicyTest {

    private static List<byte[]> args(String... values) {
        return java.util.Arrays.stream(values)
                .map(s -> s.getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    @Test
    void passwordlessDefaultAllowsAnonymousExecution() {
        AclPolicy policy = new AclPolicy();
        assertThat(policy.requiresAuthForAnonymous()).isFalse();
        assertThat(policy.authorize(null, "set", args("k1", "v1"))).isNull();
        assertThat(policy.authorize(null, "config", args("get", "*"))).isNull();
    }

    @Test
    void configureRequirePassEnforcesNoAuthUntilAuthenticated() {
        AclPolicy policy = new AclPolicy();
        policy.configureRequirePass("s3cr3t");

        assertThat(policy.requiresAuthForAnonymous()).isTrue();
        assertThat(policy.authorize(null, "get", args("k1"))).startsWith("NOAUTH");
        assertThat(policy.authorize(null, "auth", args("s3cr3t"))).isNull();
        assertThat(policy.authenticate(AclPolicy.DEFAULT_USER, "wrong")).isFalse();
        assertThat(policy.authenticate(AclPolicy.DEFAULT_USER, "s3cr3t")).isTrue();
        assertThat(policy.authorize(AclPolicy.DEFAULT_USER, "set", args("k1", "v1"))).isNull();

        // 描述文本中不包含明文密码
        assertThat(String.join("\n", policy.listDescriptions())).doesNotContain("s3cr3t");

        policy.configureRequirePass("");
        assertThat(policy.requiresAuthForAnonymous()).isFalse();
        assertThat(policy.authorize(null, "get", args("k1"))).isNull();
    }

    @Test
    void multiUserAclEnforcesCommandCategoryAndKeyPatterns() {
        AclPolicy policy = new AclPolicy();
        policy.setUser("reader", List.of("on", ">readpass", "~app:*", "+@read", "-keys"));

        assertThat(policy.authenticate("reader", "readpass")).isTrue();
        assertThat(policy.authorize("reader", "get", args("app:user:1"))).isNull();
        assertThat(policy.authorize("reader", "mget", args("app:a", "app:b"))).isNull();

        assertThat(policy.authorize("reader", "get", args("other:1"))).startsWith("NOPERM");
        assertThat(policy.authorize("reader", "mget", args("app:a", "other:1"))).startsWith("NOPERM");
        assertThat(policy.authorize("reader", "set", args("app:user:1", "x"))).startsWith("NOPERM");
        assertThat(policy.authorize("reader", "keys", args("*"))).startsWith("NOPERM");
        // 只读用户不得通过 ACL/CONFIG/FLUSHALL/EVAL 提权
        assertThat(policy.authorize("reader", "acl", args("setuser", "reader", "+@all"))).startsWith("NOPERM");
        assertThat(policy.authorize("reader", "config", args("set", "requirepass", ""))).startsWith("NOPERM");
        assertThat(policy.authorize("reader", "flushall", args())).startsWith("NOPERM");
        assertThat(policy.authorize("reader", "eval", args("return 1", "0"))).startsWith("NOPERM");

        policy.setUser("reader", List.of("off"));
        assertThat(policy.authenticate("reader", "readpass")).isFalse();
        assertThat(policy.authorize("reader", "get", args("app:user:1"))).startsWith("NOAUTH");

        assertThatThrownBy(() -> policy.deleteUsers(List.of(AclPolicy.DEFAULT_USER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(policy.deleteUsers(List.of("reader", "missing"))).isEqualTo(1);
    }

    @Test
    void linearTimeGlobHandlesMultiWildcardWithoutBacktrackingBlowup() {
        byte[] key = ("a".repeat(64) + "c").getBytes(StandardCharsets.UTF_8);
        assertThat(AclPolicy.globMatches("*a*a*a*a*a*a*a*b", key)).isFalse();
        assertThat(AclPolicy.globMatches("app:?:*", "app:1:hello".getBytes(StandardCharsets.UTF_8))).isTrue();
    }
}
