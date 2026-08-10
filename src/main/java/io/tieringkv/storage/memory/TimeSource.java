package io.tieringkv.storage.memory;

/** 时钟抽象：生产用 System 时钟，测试注入可控时钟。 */
@FunctionalInterface
public interface TimeSource {

    long nowMillis();
}
