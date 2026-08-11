package io.tieringkv.cli;

import java.util.Arrays;
import java.util.List;

/**
 * tierctl 生产 CLI（Phase 26 Goal 7）：集群状态 / Region / 事务 / 备份 /
 * 恢复 / 混沌 / 升级。复用运行时数据源，不引入第二套状态视图。
 */
public final class TierCtl {

    public enum Command {
        CLUSTER_STATUS("cluster", "status"),
        REGION_LIST("region", "list"),
        TXN_INSPECT("txn", "inspect"),
        BACKUP_CREATE("backup", "create"),
        RESTORE("restore", null),
        CHAOS_RUN("chaos", "run"),
        UPGRADE("upgrade", null);

        private final String verb;
        private final String sub;

        Command(String verb, String sub) {
            this.verb = verb;
            this.sub = sub;
        }
    }

    public record CliCommand(Command command, List<String> args) {
    }

    /** 执行上下文：生产实现接入运行时/RPC，测试用内存实现。 */
    public interface CliContext {
        String clusterStatus();

        String regionList();

        String txnInspect(String txnId);

        String backupCreate(String name);

        String restore(String name);

        String chaosRun(String scenario);

        String upgrade(String target);
    }

    private TierCtl() {
    }

    public static CliCommand parse(String[] args) {
        if (args == null || args.length < 2) {
            throw new IllegalArgumentException(
                    "usage: tierctl <command> <subcommand> [args...]");
        }
        String verb = args[0].toLowerCase();
        String sub = args[1].toLowerCase();
        for (Command command : Command.values()) {
            if (command.verb.equals(verb)
                    && command.sub != null && command.sub.equals(sub)) {
                return new CliCommand(command,
                        List.of(Arrays.copyOfRange(args, 2, args.length)));
            }
            if (command.verb.equals(verb) && command.sub == null) {
                // 单参数命令：restore <name> / upgrade <target>
                return new CliCommand(command,
                        List.of(Arrays.copyOfRange(args, 1, args.length)));
            }
        }
        throw new IllegalArgumentException("unknown command: "
                + verb + " " + sub);
    }

    public static String execute(CliContext context, CliCommand command) {
        return switch (command.command()) {
            case CLUSTER_STATUS -> context.clusterStatus();
            case REGION_LIST -> context.regionList();
            case TXN_INSPECT -> context.txnInspect(requireArg(command, 0,
                    "txn id"));
            case BACKUP_CREATE -> context.backupCreate(requireArg(command,
                    0, "backup name"));
            case RESTORE -> context.restore(requireArg(command, 0,
                    "backup name"));
            case CHAOS_RUN -> context.chaosRun(requireArg(command, 0,
                    "scenario"));
            case UPGRADE -> context.upgrade(requireArg(command, 0,
                    "target version"));
        };
    }

    public static String run(CliContext context, String[] args) {
        return execute(context, parse(args));
    }

    private static String requireArg(CliCommand command, int index,
                                     String label) {
        if (command.args().size() <= index) {
            throw new IllegalArgumentException(
                    "missing " + label + " for "
                            + command.command().name().toLowerCase());
        }
        return command.args().get(index);
    }
}
