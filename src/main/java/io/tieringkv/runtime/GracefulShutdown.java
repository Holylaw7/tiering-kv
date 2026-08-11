package io.tieringkv.runtime;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** 优雅停机（ADR-0096）：stop accept → drain → flush raft → close。 */
public final class GracefulShutdown {

    public static boolean shutdown(Runnable stopAccept,
                                   BooleanSupplier inflightDone,
                                   LongSupplier drainTimeoutMillis,
                                   Runnable flushRaft,
                                   List<AutoCloseable> closers) {
        stopAccept.run();
        long deadline = System.currentTimeMillis() + drainTimeoutMillis
                .getAsLong();
        boolean drained = inflightDone.getAsBoolean();
        while (!drained && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            drained = inflightDone.getAsBoolean();
        }
        flushRaft.run();
        for (AutoCloseable closer : closers) {
            try {
                closer.close();
            } catch (Exception ignored) {
                // 关闭尽力而为
            }
        }
        return drained;
    }

    public static Runnable signalHook(Runnable stopAccept,
                                      BooleanSupplier inflightDone,
                                      LongSupplier drainTimeoutMillis,
                                      Runnable flushRaft,
                                      List<AutoCloseable> closers) {
        return () -> shutdown(stopAccept, inflightDone, drainTimeoutMillis,
                flushRaft, closers);
    }
}
