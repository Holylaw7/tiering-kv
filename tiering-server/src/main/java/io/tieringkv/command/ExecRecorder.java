package io.tieringkv.command;

/**
 * MULTI/EXEC 事务结果记录器 SPI（C2 模块解耦）：由集群事务模块 {@code ExecJournal} 实现。
 */
@FunctionalInterface
public interface ExecRecorder {

    enum Outcome {
        SUCCESS,
        ROLLED_BACK,
        FAILED_ROLLBACK
    }

    long recordOutcome(int commandCount, Outcome outcome);
}
