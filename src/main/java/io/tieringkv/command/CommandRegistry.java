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
                new InfoCommand(infoProvider, sections))) {
            map.put(command.name(), command);
        }
        return new CommandRegistry(map);
    }

    public Command find(String name) {
        return commands.get(name);
    }
}
