package io.tieringkv.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 命令目录（ADR-0348）：默认表 129 项、名称唯一非空。 */
class CommandCatalogTest {

    @Test
    void defaultsContains129Commands() {
        assertThat(CommandCatalog.defaults()).hasSize(129);
    }

    @Test
    void defaultNamesAreUniqueAndNonBlank() {
        List<Command> commands = CommandCatalog.defaults();
        Set<String> names = new HashSet<>();
        for (Command command : commands) {
            assertThat(command.name()).isNotBlank();
            assertThat(names.add(command.name()))
                    .as("duplicate command name: %s",
                            command.name())
                    .isTrue();
        }
    }

    @Test
    void defaultRegistryStillFreezesAt132() {
        assertThat(CommandRegistry.createDefault().size())
                .isEqualTo(132);
    }
}
