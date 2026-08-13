package io.tieringkv.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 关键路径操作日志（ADR-0263）：启动/停机/WAL/迁移/Raft/事务/凭据
 * 探测，所有字符串参数先过 Redactor 再输出。
 */
public final class OpsLogger {

    private static final Logger ROOT =
            LoggerFactory.getLogger("tieringkv.ops");
    private static final Logger WAL =
            LoggerFactory.getLogger("tieringkv.ops.wal");
    private static final Logger MIGRATION =
            LoggerFactory.getLogger("tieringkv.ops.migration");
    private static final Logger RAFT =
            LoggerFactory.getLogger("tieringkv.ops.raft");
    private static final Logger TXN =
            LoggerFactory.getLogger("tieringkv.ops.txn");
    private static final Logger CREDENTIAL =
            LoggerFactory.getLogger("tieringkv.ops.credential");

    private OpsLogger() {
    }

    public static void startup(String component, String version) {
        ROOT.info("startup component={} version={}",
                Redactor.mask(component),
                Redactor.mask(version));
    }

    public static void shutdown(String component) {
        ROOT.info("shutdown component={}",
                Redactor.mask(component));
    }

    public static void walFlush(long records, long elapsedMillis) {
        WAL.info("wal flush records={} elapsedMs={}",
                records, elapsedMillis);
    }

    public static void migration(long entries,
                                 double throughputPerSec) {
        MIGRATION.info("migration entries={} throughput={}",
                entries, throughputPerSec);
    }

    public static void raftElection(String nodeId, long term,
                                    String leader) {
        RAFT.info("raft election node={} term={} leader={}",
                Redactor.mask(nodeId), term,
                Redactor.mask(leader));
    }

    public static void txnCommit(String txnId, boolean ok) {
        TXN.info("txn commit txnId={} ok={}",
                Redactor.mask(txnId), ok);
    }

    public static void credentialProbe(String target, boolean ok) {
        CREDENTIAL.info("credential probe target={} ok={}",
                Redactor.mask(target), ok);
    }

    public static void warn(String message, Object... args) {
        ROOT.warn(Redactor.mask(message),
                redactArgs(args));
    }

    public static void error(String message, Throwable error) {
        ROOT.error(Redactor.mask(message), error);
    }

    private static Object[] redactArgs(Object... args) {
        Object[] redacted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            redacted[i] = args[i] instanceof String value
                    ? Redactor.mask(value) : args[i];
        }
        return redacted;
    }
}
