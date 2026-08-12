package io.tieringkv.compliance;

import io.tieringkv.compliance.KeyRotationManager.KeyStatus;
import io.tieringkv.compliance.KeyRotationManager.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 密钥轮换（ADR-0202）：双密钥切换 + 宽限期 + 回滚。 */
class KeyRotationManagerTest {

    @Test
    void initialActiveKey() {
        KeyRotationManager manager = manager();
        assertThat(manager.active().keyId()).isEqualTo("k1");
        assertThat(manager.active().status())
                .isEqualTo(KeyStatus.ACTIVE);
    }

    @Test
    void prepareNextAndRotate() {
        KeyRotationManager manager = manager();
        manager.prepareNext(key("k2"));
        SigningKey active = manager.rotate(1000);
        assertThat(active.keyId()).isEqualTo("k2");
        assertThat(manager.audit()).hasSize(1);
        assertThat(manager.audit().get(0).oldKeyId())
                .isEqualTo("k1");
    }

    @Test
    void rotateWithoutNextRejected() {
        assertThatThrownBy(() -> manager().rotate(1000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void gracePeriodValidatesOldKey() {
        KeyRotationManager manager = manager();
        manager.prepareNext(key("k2"));
        manager.rotate(1000);
        assertThat(manager.validates(key("k1"))).isTrue();
        assertThat(manager.validates(key("k2"))).isTrue();
        assertThat(manager.validates(key("k3"))).isFalse();
    }

    @Test
    void rollbackRestoresPrevious() {
        KeyRotationManager manager = manager();
        manager.prepareNext(key("k2"));
        manager.rotate(1000);
        manager.rollback();
        assertThat(manager.active().keyId()).isEqualTo("k1");
    }

    @Test
    void rollbackWithoutHistoryRejected() {
        assertThatThrownBy(() -> manager().rollback())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullActiveRejected() {
        assertThatThrownBy(() -> new KeyRotationManager(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyMaterialRejected() {
        assertThatThrownBy(() -> new KeyRotationManager(
                key("k", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullNextRejected() {
        assertThatThrownBy(() -> manager().prepareNext(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retiredKeysTracked() {
        KeyRotationManager manager = manager();
        manager.prepareNext(key("k2"));
        manager.rotate(1000);
        assertThat(manager.retired()).hasSize(1);
        assertThat(manager.retired().get(0).keyId())
                .isEqualTo("k1");
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"k1", "k2", "key-a", "long-key-1"})
    void parameterizedKeyIds(String keyId) {
        KeyRotationManager manager = new KeyRotationManager(
                key(keyId));
        assertThat(manager.active().keyId()).isEqualTo(keyId);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRotationRounds(int rounds) {
        KeyRotationManager manager = manager();
        for (int i = 0; i < rounds; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.audit()).hasSize(rounds);
        assertThat(manager.active().keyId())
                .isEqualTo("k" + (rounds + 1));
    }

    @Test
    void concurrentRotateSafe() throws Exception {
        KeyRotationManager manager = manager();
        manager.prepareNext(key("k2"));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    manager.validates(manager.active());
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        manager.rotate(1);
        assertThat(manager.active().keyId()).isEqualTo("k2");
    }

    @Test
    void materialCloned() {
        KeyRotationManager manager = manager();
        byte[] material = "k1".getBytes(StandardCharsets.UTF_8);
        SigningKey original = new SigningKey("k1", material,
                KeyStatus.ACTIVE);
        manager = new KeyRotationManager(original);
        material[0] = 'X';
        assertThat(manager.active().material()[0])
                .isEqualTo((byte) 'k');
    }

    private static KeyRotationManager manager() {
        return new KeyRotationManager(key("k1"));
    }

    private static SigningKey key(String keyId) {
        return key(keyId, keyId);
    }

    private static SigningKey key(String keyId, String material) {
        return new SigningKey(keyId,
                material.getBytes(StandardCharsets.UTF_8),
                KeyStatus.ACTIVE);
    }
}
