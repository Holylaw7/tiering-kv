package io.tieringkv.distributed.harness;

/** 验证历史使用的 KV 存储抽象（ADR-0322 M4 增强）。 */
public interface VerificationStore {

    String get(String key);

    void put(String key, String value);
}
