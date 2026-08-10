package io.tieringkv.mvcc;

/** 版本标识（ADR-0071）：startTS / commitTS。 */
public record MvccVersion(long startTS, long commitTS) {
}
