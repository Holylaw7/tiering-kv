package io.tieringkv.command;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 命令注册表：命令名（小写）→ 实现；启动时构建，运行期只读。 */
public final class CommandRegistry {

    private final Map<String, Command> commands;

    private CommandRegistry(Map<String, Command> commands) {
        this.commands = Map.copyOf(commands);
    }

    public static CommandRegistry createDefault() {
        return createDefault(() -> "# Server\r\nno metrics\r\n");
    }

    public static CommandRegistry createDefault(Supplier<String> infoProvider) {
        return createDefault(infoProvider, Map.of());
    }

    public static CommandRegistry createDefault(
            Supplier<String> infoProvider,
            Map<String, Supplier<String>> sections) {
        Map<String, Command> map = new HashMap<>();
        for (Command command : List.of(
                new PingCommand(),
                new EchoCommand(),
                new SetCommand(),
                new GetCommand(),
                new DelCommand(),
                new ExistsCommand(),
                new InfoCommand(infoProvider, sections),
                new IncrCommand(),
                new DecrCommand(),
                new IncrByCommand(),
                new DecrByCommand(),
                new AppendCommand(),
                new StrlenCommand(),
                new GetSetCommand(),
                new SetNxCommand(),
                new SetExCommand("setex", 1000),
                new SetExCommand("psetex", 1),
                new GetDelCommand(),
                new GetRangeCommand(),
                new SetRangeCommand(),
                new TtlCommand("ttl"),
                new TtlCommand("pttl"),
                new ExpireCommand("expire", false, 1000),
                new ExpireCommand("pexpire", false, 1),
                new ExpireCommand("expireat", true, 1000),
                new ExpireCommand("pexpireat", true, 1),
                new PersistCommand(),
                new MgetCommand(),
                new MsetCommand(),
                new MsetNxCommand(),
                new DbsizeCommand(),
                new FlushCommand("flushdb"),
                new FlushCommand("flushall"),
                new ScanCommand(),
                new TypeCommand(),
                new ConfigCommand(),
                new ClientCommand())) {
            map.put(command.name(), command);
        }
        map.put("command", new CommandCommand(
                new CommandRegistry(map)));
        return new CommandRegistry(map);
    }

    public List<String> names() {
        return List.copyOf(commands.keySet());
    }

    public int size() {
        return commands.size();
    }

    public Command find(String name) {
        return commands.get(name);
    }
}
