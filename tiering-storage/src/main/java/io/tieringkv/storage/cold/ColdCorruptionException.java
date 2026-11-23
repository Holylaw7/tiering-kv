package io.tieringkv.storage.cold;

/** 冷存储损坏：SSTable 块/索引/footer 的 magic/version/checksum 校验失败。 */
public final class ColdCorruptionException extends RuntimeException {

    public ColdCorruptionException(String message) {
        super(message);
    }
}
