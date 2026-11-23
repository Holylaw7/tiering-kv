package io.tieringkv.compliance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 密钥轮换（ADR-0202）：双密钥原子切换 + 宽限期。 */
public final class KeyRotationManager {

    /** 密钥状态。 */
    public enum KeyStatus {
        ACTIVE,
        NEXT,
        RETIRED
    }

    /** 密钥：ID + 材料 + 状态。 */
    public record SigningKey(String keyId, byte[] material,
                             KeyStatus status) {
    }

    /** 轮换审计记录。 */
    public record Rotation(long timestampMillis, String oldKeyId,
                           String newKeyId) {
    }

    private SigningKey active;
    private SigningKey next;
    private final List<SigningKey> retired =
            new CopyOnWriteArrayList<>();
    private final List<Rotation> audit =
            new CopyOnWriteArrayList<>();

    public KeyRotationManager(SigningKey initialActive) {
        if (initialActive == null || initialActive.material() == null
                || initialActive.material().length == 0) {
            throw new IllegalArgumentException(
                    "active key required");
        }
        this.active = new SigningKey(initialActive.keyId(),
                initialActive.material().clone(), KeyStatus.ACTIVE);
    }

    public synchronized void prepareNext(SigningKey key) {
        if (key == null || key.material() == null
                || key.material().length == 0) {
            throw new IllegalArgumentException(
                    "next key required");
        }
        this.next = new SigningKey(key.keyId(),
                key.material().clone(), KeyStatus.NEXT);
    }

    /** 原子切换：next → active，旧 active 进入宽限期。 */
    public synchronized SigningKey rotate(long timestampMillis) {
        if (next == null) {
            throw new IllegalStateException(
                    "no next key prepared");
        }
        SigningKey old = active;
        retired.add(old);
        active = next;
        next = null;
        audit.add(new Rotation(timestampMillis, old.keyId(),
                active.keyId()));
        return active;
    }

    /** 回滚：active 退回最近退休密钥。 */
    public synchronized SigningKey rollback() {
        if (retired.isEmpty()) {
            throw new IllegalStateException(
                    "nothing to roll back");
        }
        SigningKey previous = retired.remove(
                retired.size() - 1);
        active = new SigningKey(previous.keyId(),
                previous.material().clone(), KeyStatus.ACTIVE);
        return active;
    }

    /** 宽限期验证：active 或最近退休密钥均有效。 */
    public boolean validates(SigningKey key) {
        if (key == null || key.material() == null) {
            return false;
        }
        return active.keyId().equals(key.keyId())
                || (!retired.isEmpty()
                && retired.get(retired.size() - 1).keyId()
                .equals(key.keyId()));
    }

    public SigningKey active() {
        return active;
    }

    public List<SigningKey> retired() {
        return List.copyOf(retired);
    }

    public List<Rotation> audit() {
        return List.copyOf(audit);
    }
}
