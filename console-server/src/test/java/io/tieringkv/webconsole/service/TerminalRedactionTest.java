package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.TerminalHistory;
import io.tieringkv.webconsole.repository.TerminalHistoryRepository;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** N2-B：在线终端历史敏感命令脱敏测试。 */
class TerminalRedactionTest {

    @Test
    void redactsSensitiveArgumentsAcrossAuthHelloAclAndConfigCommands() {
        assertThat(TerminalService.redactCommand("AUTH mySecret123"))
                .isEqualTo("AUTH <redacted>");
        assertThat(TerminalService.redactCommand("auth opsuser \"pass with space\""))
                .isEqualTo("auth <redacted> <redacted>");
        assertThat(TerminalService.redactCommand("HELLO 3 AUTH alice super-secret"))
                .isEqualTo("HELLO 3 AUTH <redacted> <redacted>");
        assertThat(TerminalService.redactCommand("ACL SETUSER bob on >s3cr3t +@all"))
                .isEqualTo("ACL SETUSER <redacted> <redacted> <redacted> <redacted>");
        assertThat(TerminalService.redactCommand("CONFIG SET requirepass \"p@ss w0rd\""))
                .isEqualTo("CONFIG SET requirepass <redacted>");
        assertThat(TerminalService.redactCommand("CONFIG SET maxmemory 1024 requirepass s3cr3t"))
                .isEqualTo("CONFIG SET maxmemory 1024 requirepass <redacted>");

        // 非敏感命令保持原文
        assertThat(TerminalService.redactCommand("SET user:1 \"hello world\""))
                .isEqualTo("SET user:1 \"hello world\"");
        assertThat(TerminalService.redactCommand("CONFIG GET requirepass"))
                .isEqualTo("CONFIG GET requirepass");
        assertThat(TerminalService.redactCommand("ACL LIST"))
                .isEqualTo("ACL LIST");
    }

    @Test
    void persistedTerminalHistoryNeverStoresPlaintextCredentials() throws Exception {
        InstanceService instances = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        TerminalHistoryRepository repo = mock(TerminalHistoryRepository.class);
        AuditService audit = mock(AuditService.class);

        DbInstance inst = new DbInstance();
        inst.setId(1L);
        inst.setName("local");
        when(instances.require(1L)).thenReturn(inst);
        when(gateway.execute(eq(inst), any(String[].class))).thenReturn(new RespReply.Simple("OK"));
        when(repo.save(any(TerminalHistory.class))).thenAnswer(inv -> inv.getArgument(0));

        TerminalService service = new TerminalService(instances, gateway, repo, new ConsoleProperties(), audit);
        service.execute(1L, "CONFIG SET requirepass top-secret-pw");

        ArgumentCaptor<TerminalHistory> saved = ArgumentCaptor.forClass(TerminalHistory.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getCommand()).isEqualTo("CONFIG SET requirepass <redacted>");
        assertThat(saved.getValue().getCommand()).doesNotContain("top-secret-pw");
    }
}
