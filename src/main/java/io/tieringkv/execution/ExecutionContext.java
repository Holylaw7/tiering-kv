package io.tieringkv.execution;

/** 执行上下文（ADR-0023）：键 + 提交时间 + 动作，用于等待/延迟观测。 */
public record ExecutionContext(byte[] key, long submittedNanos, Runnable action) {
}
