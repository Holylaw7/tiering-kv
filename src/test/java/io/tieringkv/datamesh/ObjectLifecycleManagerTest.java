package io.tieringkv.datamesh;

import io.tieringkv.datamesh.ObjectLifecycleManager.LifecycleRule;
import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 对象生命周期（ADR-0203）：TTL 规则 + 恢复保护。 */
class ObjectLifecycleManagerTest {

    @Test
    void addRuleAndApply() {
        ObjectLifecycleManager manager = manager();
        ArchivedObject object = object("obj-v1", 1000);
        assertThat(manager.apply(object, 2000)).isTrue();
        assertThat(manager.applied()).hasSize(1);
    }

    @Test
    void noMatchingRuleSkips() {
        ObjectLifecycleManager manager = new ObjectLifecycleManager();
        assertThat(manager.apply(object("obj-x", 1000), 2000))
                .isFalse();
    }

    @Test
    void expiredAfterTtl() {
        ObjectLifecycleManager manager = manager(7);
        ArchivedObject object = object("obj-v1", 0);
        long day = 24 * 60 * 60 * 1000;
        assertThat(manager.expired(object, day * 10)).isTrue();
        assertThat(manager.expired(object, day * 5)).isFalse();
    }

    @Test
    void protectionPreventsDeletion() {
        ObjectLifecycleManager manager = manager();
        manager.protect("obj-v1");
        assertThat(manager.isProtected("obj-v1")).isTrue();
        assertThat(manager.isProtected("obj-v2")).isFalse();
    }

    @Test
    void invalidRuleRejected() {
        assertThatThrownBy(() -> new LifecycleRule("", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LifecycleRule("obj-", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullObjectRejected() {
        ObjectLifecycleManager manager = manager();
        assertThatThrownBy(() -> manager.apply(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.expired(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "days {0}")
    @ValueSource(longs = {0, 1, 30, 90, 365})
    void parameterizedExpirationDays(long days) {
        ObjectLifecycleManager manager = manager(days);
        ArchivedObject object = object("obj-v1", 0);
        long day = 24 * 60 * 60 * 1000;
        assertThat(manager.expired(object, day * (days + 1)))
                .isTrue();
        assertThat(manager.expired(object, day * days)).isFalse();
    }

    @ParameterizedTest(name = "prefix {0}")
    @ValueSource(strings = {"obj-", "cold-", "archive-"})
    void parameterizedPrefixes(String prefix) {
        ObjectLifecycleManager manager = new ObjectLifecycleManager();
        manager.addRule(new LifecycleRule(prefix, 30));
        ArchivedObject object = object(prefix + "v1", 1);
        assertThat(manager.apply(object, 2)).isTrue();
    }

    @Test
    void concurrentApplyStable() throws Exception {
        ObjectLifecycleManager manager = manager();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    manager.apply(object("obj-v" + i, 1), 2);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(manager.applied()).hasSize(200);
    }

    @Test
    void ruleAppliedCarriesRule() {
        ObjectLifecycleManager manager = manager();
        manager.apply(object("obj-v1", 1000), 2000);
        var record = manager.applied().get(0);
        assertThat(record.rule().expirationDays()).isEqualTo(30);
        assertThat(record.objectKey()).isEqualTo("obj-v1");
    }

    private static ObjectLifecycleManager manager() {
        return manager(30);
    }

    private static ObjectLifecycleManager manager(long days) {
        ObjectLifecycleManager manager =
                new ObjectLifecycleManager();
        manager.addRule(new LifecycleRule("obj-", days));
        return manager;
    }

    private static ArchivedObject object(String key, long archived) {
        return new ArchivedObject(key, "aws-us",
                new RemoteSnapshot(key, "gcp-us", 1, 1, false,
                        archived), archived);
    }
}
