package io.tieringkv.webconsole.service;

import com.sun.net.httpserver.HttpServer;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.AlertChannel;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceDeletedEvent;
import io.tieringkv.webconsole.domain.InstanceStatus;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.AlertChannelRepository;
import io.tieringkv.webconsole.repository.AlertEvalStateRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.LoginAttemptRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.security.UserPrincipal;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import io.tieringkv.webconsole.security.OutboundUrlGuard;
import io.tieringkv.webconsole.web.dto.AlertChannelRequest;
import io.tieringkv.webconsole.web.dto.AlertChannelView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sBackupTriggerResult;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterListView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterSummary;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sScaleRequest;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sUpgradeRequest;
import io.tieringkv.webconsole.web.dto.InstanceRequest;
import io.tieringkv.webconsole.web.dto.KeyDetail;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import io.tieringkv.webconsole.web.dto.KeyScanPage;
import io.tieringkv.webconsole.web.dto.TrendPoint;
import io.tieringkv.webconsole.web.dto.UserCreateRequest;
import io.tieringkv.webconsole.web.dto.UserUpdateRequest;
import io.tieringkv.webconsole.web.dto.UserView;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 2B（T2）：核心服务边界、异常与分支覆盖强化测试。
 *
 * <p>纯单测直击低分支覆盖类：InstanceService、AlertChannelService、RetentionService、
 * MetricService、SlowLogService、UserService、RespGateway、K8sOperatorBridgeService、
 * KeyspaceService 与 AlertStateStore 边界路径。
 */
class CoreServicesBranchCoverageTest {

    @AfterEach
    void cleanupContexts() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== 1. InstanceService ====================

    @Test
    void instanceServiceCoversCrudValidationTlsProbeAndAfterCommitDelete() {
        DbInstanceRepository repo = mock(DbInstanceRepository.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        InstanceService service = new InstanceService(repo, gateway, audit);
        service.setMetricsBinder(binder);
        service.setEventPublisher(publisher);

        DbInstance existing = sampleInstance(1L, "inst-1", "10.0.0.1", 6379);
        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(existing));
        when(repo.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of(existing));
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.list()).hasSize(1);
        assertThat(service.enabled()).hasSize(1);
        assertThat(service.require(1L).getName()).isEqualTo("inst-1");
        assertThatThrownBy(() -> service.require(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实例不存在");

        // create: 地址冲突 / 名称冲突 / 非法 CA 证书 / 正常创建（含空白 CA 与密码加密）
        when(repo.existsByHostAndPort("10.0.0.1", 6379)).thenReturn(true);
        assertThatThrownBy(() -> service.create(new InstanceRequest("n", "10.0.0.1", 6379, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已纳管");

        when(repo.existsByHostAndPort("10.0.0.2", 6379)).thenReturn(false);
        when(repo.findByName("inst-1")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.create(new InstanceRequest("inst-1", "10.0.0.2", 6379, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");

        when(repo.findByName("bad-tls")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new InstanceRequest(
                "bad-tls", "10.0.0.2", 6379, "pw", "r", true, true, true, "NOT-A-PEM")))
                .isInstanceOf(BusinessException.class);

        when(repo.findByName("ok-inst")).thenReturn(Optional.empty());
        when(repo.save(any(DbInstance.class))).thenAnswer(inv -> {
            DbInstance d = inv.getArgument(0);
            d.setId(2L);
            return d;
        });
        var created = service.create(new InstanceRequest(
                "ok-inst", "10.0.0.2", 6379, "secret", "rem", null, null, null, "   "));
        assertThat(created.name()).isEqualTo("ok-inst");
        assertThat(created.enabled()).isTrue();
        assertThat(created.tlsEnabled()).isFalse();

        // update: 重名冲突 / 新地址冲突 / 正常更新（空口令不覆盖、变更端口驱逐池、禁用 TLS）
        DbInstance other = sampleInstance(2L, "other", "10.0.0.9", 6379);
        when(repo.findByName("other")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.update(1L, new InstanceRequest("other", "10.0.0.1", 6379, null, null, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");

        when(repo.findByName("inst-1")).thenReturn(Optional.of(existing));
        when(repo.existsByHostAndPort("10.0.0.9", 6380)).thenReturn(true);
        assertThatThrownBy(() -> service.update(1L, new InstanceRequest("inst-1", "10.0.0.9", 6380, null, null, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已纳管");

        when(repo.existsByHostAndPort("10.0.0.8", 6380)).thenReturn(false);
        service.update(1L, new InstanceRequest("inst-1", "10.0.0.8", 6380, "newpw", "r2", false, false, false, ""));
        assertThat(existing.getPort()).isEqualTo(6380);
        assertThat(existing.isEnabled()).isFalse();
        verify(gateway).evict(1L);

        // probe: redis_version / tieringkv_version fallback / 通信失败 DOWN
        when(gateway.info(existing)).thenReturn("# Server\r\nredis_version:7.0.0\r\n");
        assertThat(service.probe(1L).version()).isEqualTo("7.0.0");

        when(gateway.info(existing)).thenReturn("# Server\r\ntieringkv_version:6.4.0\r\n");
        assertThat(service.probe(existing).getVersion()).isEqualTo("6.4.0");
        assertThat(existing.getStatus()).isEqualTo(InstanceStatus.UP);

        when(gateway.info(existing)).thenThrow(BusinessException.upstream("down"));
        service.probe(existing);
        assertThat(existing.getStatus()).isEqualTo(InstanceStatus.DOWN);
        assertThat(existing.getVersion()).isNull();

        // delete: 无事务同步立发事件 + 有事务同步在 afterCommit 发事件
        service.delete(1L);
        verify(binder, times(1)).unregisterInstance(1L);
        verify(publisher, times(1)).publishEvent(new InstanceDeletedEvent(1L));

        TransactionSynchronizationManager.initSynchronization();
        service.delete(1L);
        verify(publisher, times(1)).publishEvent(new InstanceDeletedEvent(1L)); // 尚未提交
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(publisher, times(2)).publishEvent(new InstanceDeletedEvent(1L));
    }

    // ==================== 2. AlertChannelService ====================

    @Test
    void alertChannelServiceCoversValidationConflictsTypesAndAfterCommitCleanup() {
        AlertChannelRepository repo = mock(AlertChannelRepository.class);
        InstanceCredentialCipher cipher = new InstanceCredentialCipher((String) null);
        AlertWebhookDispatcher dispatcher = mock(AlertWebhookDispatcher.class);
        AuditService audit = mock(AuditService.class);
        ConsoleProperties guardProps = new ConsoleProperties();
        guardProps.getSecurity().setBlockPrivateWebhookUrls(false);
        OutboundUrlGuard allowAllGuard = new OutboundUrlGuard(guardProps);
        AlertStateStore stateStore = mock(AlertStateStore.class);

        AlertChannelService service = new AlertChannelService(repo, cipher, dispatcher, audit, allowAllGuard);
        service.setStateStore(stateStore);

        AlertChannel existing = new AlertChannel();
        existing.setId(1L);
        existing.setName("ops-hook");
        existing.setType("WEBHOOK");
        existing.setUrl("https://hooks.example.com/a");
        existing.setSecret("enc-sec");
        existing.setSilenceSeconds(300);
        existing.setEnabled(true);

        when(repo.findAllByOrderByIdAsc()).thenReturn(List.of(existing));
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThat(service.list()).hasSize(1);
        assertThat(service.list().get(0).hasSecret()).isTrue();

        // create: 名称冲突 / 非法类型 / 默认值与有密钥
        when(repo.findByName("ops-hook")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.create(
                new AlertChannelRequest("ops-hook", "WEBHOOK", "https://a.example.com", null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");

        when(repo.findByName("mail-ch")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(
                new AlertChannelRequest("mail-ch", "EMAIL", "https://a.example.com", null, 60, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持 WEBHOOK");

        when(repo.findByName("new-ch")).thenReturn(Optional.empty());
        when(repo.save(any(AlertChannel.class))).thenAnswer(inv -> inv.getArgument(0));
        AlertChannelView created = service.create(
                new AlertChannelRequest("new-ch", " ", "https://a.example.com/hook", "my-secret", null, null));
        assertThat(created.type()).isEqualTo("WEBHOOK");
        assertThat(created.silenceSeconds()).isEqualTo(300);
        assertThat(created.enabled()).isTrue();
        assertThat(created.hasSecret()).isTrue();

        // update: 不存在 / 重名冲突 / 正常更新（含全字段）
        assertThatThrownBy(() -> service.update(99L,
                new AlertChannelRequest("x", null, "https://a.example.com", null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");

        AlertChannel other = new AlertChannel();
        other.setId(2L);
        other.setName("taken");
        when(repo.findByName("taken")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.update(1L,
                new AlertChannelRequest("taken", "webhook", "https://a.example.com", null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");

        AlertChannelView updated = service.update(1L,
                new AlertChannelRequest("ops-hook", "webhook", "https://b.example.com", "new-sec", 120, false));
        assertThat(updated.silenceSeconds()).isEqualTo(120);
        assertThat(updated.enabled()).isFalse();

        // test: 不存在 / 成功与失败
        assertThatThrownBy(() -> service.test(99L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        when(dispatcher.sendTest(existing)).thenReturn(new WebhookTestResult(true, 200, "OK", 15L));
        assertThat(service.test(1L).delivered()).isTrue();
        when(dispatcher.sendTest(existing)).thenReturn(new WebhookTestResult(false, 502, "ERR", 30L));
        assertThat(service.test(1L).delivered()).isFalse();

        // delete: 不存在 / 无事务立即清 / 有事务 afterCommit 清
        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
        service.delete(1L);
        verify(stateStore, times(1)).clearForChannel(1L);

        TransactionSynchronizationManager.initSynchronization();
        service.delete(1L);
        verify(stateStore, times(1)).clearForChannel(1L);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
        verify(stateStore, times(2)).clearForChannel(1L);
    }

    // ==================== 3. RetentionService & MetricService ====================

    @Test
    void retentionAndMetricServicesCoverLeaderLeaseNullDepsAndBoundaryClamping() {
        MetricPointRepository metricRepo = mock(MetricPointRepository.class);
        OpLogRepository opLogRepo = mock(OpLogRepository.class);
        LoginAttemptRepository loginRepo = mock(LoginAttemptRepository.class);
        DbLeaseLockService leaseSvc = mock(DbLeaseLockService.class);
        AlertStateStore stateStore = mock(AlertStateStore.class);
        ConsoleProperties props = new ConsoleProperties();
        props.getMetrics().setRetentionHours(0); // clamp to >= 1

        // 未抢到主锁：直接返回不删除任何表
        RetentionService retention = new RetentionService(metricRepo, opLogRepo, props, loginRepo, leaseSvc);
        retention.setAlertStateStore(stateStore);
        when(leaseSvc.tryAcquire(eq(RetentionService.RETENTION_LEASE_KEY), any(Duration.class))).thenReturn(false);
        retention.purge();
        verify(metricRepo, never()).deleteOlderThan(any());

        // 抢到主锁且全部删除 0 行
        when(leaseSvc.tryAcquire(eq(RetentionService.RETENTION_LEASE_KEY), any(Duration.class))).thenReturn(true);
        retention.purge();
        verify(metricRepo, times(1)).deleteOlderThan(any());
        verify(stateStore, times(1)).purgeStaleBefore(any());

        // 抢到主锁且有删除行数
        when(metricRepo.deleteOlderThan(any())).thenReturn(5);
        when(opLogRepo.deleteOlderThan(any())).thenReturn(3);
        when(loginRepo.deleteExpired(any())).thenReturn(2);
        when(stateStore.purgeStaleBefore(any())).thenReturn(4);
        retention.purge();
        verify(stateStore, times(2)).purgeStaleBefore(any());

        // 轻量构造器（无选主与可选依赖）
        RetentionService simpleRetention = new RetentionService(metricRepo, opLogRepo, props);
        simpleRetention.purge();
        verify(metricRepo, times(3)).deleteOlderThan(any());

        // MetricService: save / collect / latest / trends（含全 null 字段与非空字段、minutes 边界截断）
        MetricService metricService = new MetricService(metricRepo);
        LocalDateTime now = LocalDateTime.now();
        when(metricRepo.save(any(MetricPoint.class))).thenAnswer(inv -> inv.getArgument(0));
        MetricPoint saved = metricService.save(1L, "# Server\r\nredis_version:6.4.0\r\n# Stats\r\nqps:88.5\r\n", now);
        assertThat(saved.getQps()).isEqualTo(88.5d);

        when(metricRepo.findFirstByInstanceIdOrderByCollectedAtDesc(1L)).thenReturn(Optional.of(saved));
        assertThat(metricService.latest(1L)).isPresent();

        MetricPoint nullFields = new MetricPoint();
        nullFields.setCollectedAt(now.minusMinutes(1));
        MetricPoint fullFields = new MetricPoint();
        fullFields.setCollectedAt(now);
        fullFields.setQps(12.5d);
        fullFields.setAvgLatencyMs(1.2d);
        fullFields.setConnections(7);
        fullFields.setUsedMemory(2048L);
        fullFields.setTotalKeys(50L);
        fullFields.setErrors(1L);
        fullFields.setMaxMemory(4096L);
        when(metricRepo.findByInstanceIdAndCollectedAtBetweenOrderByCollectedAtAsc(eq(1L), any(), any()))
                .thenReturn(List.of(nullFields, fullFields));

        List<TrendPoint> clampedLow = metricService.trends(1L, -10);
        assertThat(clampedLow).hasSize(2);
        assertThat(clampedLow.get(0).qps()).isZero();
        assertThat(clampedLow.get(0).connections()).isZero();
        assertThat(clampedLow.get(0).usedMemory()).isZero();
        assertThat(clampedLow.get(1).qps()).isEqualTo(12.5d);
        assertThat(clampedLow.get(1).maxMemory()).isEqualTo(4096L);
        assertThat(metricService.trends(1L, 999_999)).hasSize(2);
    }

    // ==================== 4. SlowLogService ====================

    @Test
    void slowLogServiceCoversValidationErrorsMalformedRepliesAndResetPaths() throws Exception {
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        SlowLogService service = new SlowLogService(instSvc, gateway, audit);
        DbInstance inst = sampleInstance(1L, "sl-inst", "127.0.0.1", 6379);
        when(instSvc.require(1L)).thenReturn(inst);

        assertThatThrownBy(() -> service.list(1L, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(1L, 257)).isInstanceOf(BusinessException.class);

        // LEN: unknown subcommand / generic error / IOException
        when(gateway.execute(inst, "SLOWLOG", "LEN"))
                .thenReturn(new RespReply.Error("ERR unknown subcommand 'LEN'"));
        assertThat(service.list(1L, 10).supported()).isFalse();

        when(gateway.execute(inst, "SLOWLOG", "LEN"))
                .thenReturn(new RespReply.Error("ERR permission denied"));
        assertThatThrownBy(() -> service.list(1L, 10))
                .isInstanceOf(BusinessException.class).hasMessageContaining("SLOWLOG LEN");

        doThrow(new IOException("broken pipe")).when(gateway).execute(inst, "SLOWLOG", "LEN");
        assertThatThrownBy(() -> service.list(1L, 10))
                .isInstanceOf(BusinessException.class).hasMessageContaining("读取 SLOWLOG 失败");

        // GET: not supported / generic error / nil array / 畸形行 + 多类型参数解码
        doReturn(new RespReply.Simple("OK")).when(gateway).execute(inst, "SLOWLOG", "LEN"); // 非 Integer -> 0
        when(gateway.execute(inst, "SLOWLOG", "GET", "5"))
                .thenReturn(new RespReply.Error("command not supported"));
        assertThat(service.list(1L, 5).supported()).isFalse();

        when(gateway.execute(inst, "SLOWLOG", "GET", "5"))
                .thenReturn(new RespReply.Error("ERR busy"));
        assertThatThrownBy(() -> service.list(1L, 5))
                .isInstanceOf(BusinessException.class).hasMessageContaining("SLOWLOG GET");

        when(gateway.execute(inst, "SLOWLOG", "GET", "5")).thenReturn(new RespReply.Array(null));
        assertThat(service.list(1L, 5).entries()).isEmpty();

        RespReply mixedRows = new RespReply.Array(List.of(
                new RespReply.Simple("bad-row"),
                new RespReply.Array(List.of(new RespReply.Integer(1))), // size < 4
                new RespReply.Array(List.of(
                        new RespReply.Integer(9),
                        new RespReply.Simple("non-int-ts"),
                        new RespReply.Integer(4200),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("get".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk(null),
                                new RespReply.Simple("s"),
                                new RespReply.Integer(7),
                                new RespReply.Error("e")
                        ))
                )),
                new RespReply.Array(List.of(
                        new RespReply.Integer(10),
                        new RespReply.Integer(100),
                        new RespReply.Integer(50),
                        new RespReply.Array(null) // nil args -> command=""
                ))
        ));
        when(gateway.execute(inst, "SLOWLOG", "LEN")).thenReturn(new RespReply.Integer(2));
        when(gateway.execute(inst, "SLOWLOG", "GET", "5")).thenReturn(mixedRows);
        var view = service.list(1L, 5);
        assertThat(view.supported()).isTrue();
        assertThat(view.totalLen()).isEqualTo(2L);
        assertThat(view.entries()).hasSize(2);
        assertThat(view.entries().get(0).command()).isEqualTo("GET");
        assertThat(view.entries().get(0).args()).containsExactly("get", "", "s", "7", "");
        assertThat(view.entries().get(1).command()).isEmpty();

        // reset: unsupported -> 400 / generic long error -> 502 / IOException -> 502 / OK
        when(gateway.execute(inst, "SLOWLOG", "RESET"))
                .thenReturn(new RespReply.Error("ERR unknown command"));
        assertThatThrownBy(() -> service.reset(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 SLOWLOG");

        String longErr = "E".repeat(300);
        when(gateway.execute(inst, "SLOWLOG", "RESET")).thenReturn(new RespReply.Error(longErr));
        assertThatThrownBy(() -> service.reset(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("清空 SLOWLOG 失败");

        doThrow(new IOException((String) null)).when(gateway).execute(inst, "SLOWLOG", "RESET");
        assertThatThrownBy(() -> service.reset(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("清空 SLOWLOG 失败");

        doReturn(new RespReply.Simple("OK")).when(gateway).execute(inst, "SLOWLOG", "RESET");
        service.reset(1L);
        verify(audit, times(1)).record("SLOWLOG_RESET", "sl-inst", "OK", true);
    }

    // ==================== 5. UserService ====================

    @Test
    void userServiceCoversPaginationLastAdminSelfGuardsAndTokenRevocation() {
        UserRepository repo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        when(encoder.encode(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));

        UserService service = new UserService(repo, encoder, audit);

        User admin1 = sampleUser(1L, "admin1", Role.ADMIN, 1);
        User admin2 = sampleUser(2L, "admin2", Role.ADMIN, 1);
        User ops = sampleUser(3L, "ops1", Role.OPS, 1);
        authenticateAs(admin1);

        when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(admin1, ops)));
        when(repo.findByUsernameContainingOrderByIdAsc(eq("ops"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ops)));
        PageResult<UserView> p1 = service.page("  ", 0, 500);
        assertThat(p1.items()).hasSize(2);
        PageResult<UserView> p2 = service.page(" ops ", 1, 10);
        assertThat(p2.items()).hasSize(1);

        // create: duplicate vs valid
        when(repo.existsByUsername("admin1")).thenReturn(true);
        assertThatThrownBy(() -> service.create(new UserCreateRequest("admin1", "pass1234", "a", "a@b.c", Role.OPS)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");
        when(repo.existsByUsername("newops")).thenReturn(false);
        assertThat(service.create(new UserCreateRequest("newops", "pass1234", "n", "n@b.c", Role.OPS)).username())
                .isEqualTo("newops");

        // update: 404 / 非法 status / 唯一管理员保护 / 禁自己 / 角色变更与禁用吊销 tokenVersion
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, new UserUpdateRequest(null, null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");

        when(repo.findById(1L)).thenReturn(Optional.of(admin1));
        when(repo.findById(2L)).thenReturn(Optional.of(admin2));
        when(repo.findById(3L)).thenReturn(Optional.of(ops));
        when(repo.countByRoleAndStatus(Role.ADMIN, 1)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(3L, new UserUpdateRequest(null, null, null, 2)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("0 或 1");
        assertThatThrownBy(() -> service.update(1L, new UserUpdateRequest(null, null, Role.OPS, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少一个启用的管理员");
        assertThatThrownBy(() -> service.update(1L, new UserUpdateRequest(null, null, null, 0)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少一个启用的管理员");

        // 有 2 名管理员时，不能禁用自己但可降级另一名管理员并触发 tokenVersion 递增
        when(repo.countByRoleAndStatus(Role.ADMIN, 1)).thenReturn(2L);
        assertThatThrownBy(() -> service.update(1L, new UserUpdateRequest(null, null, null, 0)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能禁用当前登录账号");

        long prevVer = admin2.getTokenVersion();
        service.update(2L, new UserUpdateRequest("Nick2", "n2@x.com", Role.OPS, 0));
        assertThat(admin2.getRole()).isEqualTo(Role.OPS);
        assertThat(admin2.getStatus()).isEqualTo(0);
        assertThat(admin2.getTokenVersion()).isEqualTo(prevVer + 1L);

        // 对已禁用的账号再次传 status=0 + 相同角色不重复吊销
        long verAfter = admin2.getTokenVersion();
        service.update(2L, new UserUpdateRequest(null, null, Role.OPS, 0));
        assertThat(admin2.getTokenVersion()).isEqualTo(verAfter);

        // resetPassword + delete 保护
        service.resetPassword(3L, "newPass123");
        assertThat(ops.getTokenVersion()).isEqualTo(1);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能删除当前登录账号");
        when(repo.countByRoleAndStatus(Role.ADMIN, 1)).thenReturn(1L);
        admin2.setRole(Role.ADMIN);
        assertThatThrownBy(() -> service.delete(2L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("至少一个启用的管理员");
        service.delete(3L);
        verify(repo).delete(ops);
    }

    // ==================== 6. RespGateway ====================

    @Test
    void respGatewayCoversAsTextBatchAuthErrorsAndPoolStats() throws Exception {
        ConsoleProperties props = new ConsoleProperties();
        props.getMetrics().setConnectTimeoutMs(400);
        props.getMetrics().setReadTimeoutMs(400);
        RespGateway gateway = new RespGateway(props);

        // asText 全分支
        assertThat(gateway.asText(new RespReply.Bulk("hi".getBytes(StandardCharsets.UTF_8)))).isEqualTo("hi");
        assertThat(gateway.asText(new RespReply.Simple("OK"))).isEqualTo("OK");
        assertThat(gateway.asText(new RespReply.Integer(42))).isEqualTo("42");
        assertThat(gateway.asText(new RespReply.Array(List.of()))).isEqualTo("[]");
        assertThatThrownBy(() -> gateway.asText(new RespReply.Error("boom")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("boom");

        assertThat(gateway.totalMaxConnections()).isEqualTo(props.getPool().getMaxTotal());
        assertThat(gateway.executeBatch(sampleInstance(1L, "x", "127.0.0.1", 1), List.of())).isEmpty();
        assertThat(gateway.executeBatch(sampleInstance(1L, "x", "127.0.0.1", 1), null)).isEmpty();
        assertThat(gateway.evictIfStale(999L, null)).isFalse();

        AtomicReference<String> authMode = new AtomicReference<>("OK");
        try (MiniRespServer stub = new MiniRespServer(args -> {
            String cmd = args.get(0).toUpperCase(Locale.ROOT);
            if ("AUTH".equals(cmd)) {
                return switch (authMode.get()) {
                    case "OK" -> "+OK\r\n";
                    case "UNKNOWN" -> "-ERR unknown command 'AUTH'\r\n";
                    default -> "-WRONGPASS invalid password\r\n";
                };
            }
            if ("PING".equals(cmd)) {
                return "+PONG\r\n";
            }
            return "$4\r\nINFO\r\n";
        })) {
            DbInstance inst = sampleInstance(10L, "gw-test", "127.0.0.1", stub.port());
            inst.setPassword("pass123");

            // AUTH OK + executeBatch + 统计指标
            List<RespReply> batch = gateway.executeBatch(inst, List.of(
                    io.tieringkv.webconsole.resp.RespClient.commandBytes("PING"),
                    io.tieringkv.webconsole.resp.RespClient.commandBytes("INFO")));
            assertThat(batch).hasSize(2);
            assertThat(gateway.poolCount()).isEqualTo(1);
            assertThat(gateway.totalIdleConnections()).isGreaterThanOrEqualTo(1L);
            assertThat(gateway.totalMaxConnections()).isEqualTo(props.getPool().getMaxTotal());
            assertThat(gateway.totalBorrowTimeouts()).isZero();
            assertThat(gateway.evictIfStale(10L, inst)).isFalse();

            // 配置指纹变化 -> 自动驱逐旧池
            DbInstance mutated = sampleInstance(10L, "gw-test", "127.0.0.1", stub.port());
            mutated.setPassword("other-pass");
            assertThat(gateway.evictIfStale(10L, mutated)).isTrue();
            assertThat(gateway.poolCount()).isZero();

            // AUTH unknown command -> 502
            authMode.set("UNKNOWN");
            assertThatThrownBy(() -> gateway.info(inst))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 AUTH");

            // AUTH wrong password -> 502
            authMode.set("WRONG");
            assertThatThrownBy(() -> gateway.info(inst))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("AUTH 失败");

            // 不可达端口 -> 502
            int closedPort;
            try (ServerSocket tmp = new ServerSocket(0)) {
                closedPort = tmp.getLocalPort();
            }
            DbInstance dead = sampleInstance(11L, "dead", "127.0.0.1", closedPort);
            assertThatThrownBy(() -> gateway.info(dead))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("无法连接实例");
        } finally {
            gateway.shutdown();
        }
    }

    // ==================== 7. K8sOperatorBridgeService ====================

    @Test
    void k8sOperatorBridgeServiceCoversDisabledTokenFileHttpErrorsAndPatchOps(@TempDir Path tempDir) throws Exception {
        AuditService audit = mock(AuditService.class);
        ObjectMapper mapper = new ObjectMapper();
        ConsoleProperties props = new ConsoleProperties();
        K8sOperatorBridgeService svc = new K8sOperatorBridgeService(props, mapper, audit);

        // 1. disabled 模式
        K8sClusterListView disabled = svc.listClusters();
        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> svc.scaleCluster("demo", new K8sScaleRequest(2, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未启用");

        // 2. enabled 但缺少 token（含不存在的 tokenPath）
        props.getKubernetes().setEnabled(true);
        props.getKubernetes().setTokenPath(tempDir.resolve("missing.token").toString());
        assertThat(svc.listClusters().enabled()).isFalse();
        assertThatThrownBy(() -> svc.triggerBackup("demo"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Bearer 令牌");

        // 3. 从文件读取 token + 启动本地 mock K8s API server
        Path tokenFile = tempDir.resolve("sa.token");
        Files.writeString(tokenFile, "file-sa-token\n", StandardCharsets.UTF_8);
        props.getKubernetes().setTokenPath(tokenFile.toString());

        AtomicInteger statusCode = new AtomicInteger(200);
        AtomicReference<String> responseBody = new AtomicReference<>("{}");
        HttpServer k8s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        k8s.createContext("/", exchange -> {
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode.get(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        k8s.start();
        try {
            props.getKubernetes().setApiServer("http://127.0.0.1:" + k8s.getAddress().getPort() + "/");
            props.getKubernetes().setNamespace("tiering-kv");

            // listClusters 200（含完整字段与缺省字段两项）
            responseBody.set("""
                    {"items":[
                      {"metadata":{"name":"c1","namespace":"ns1","generation":3,"creationTimestamp":"2026-11-27T00:00:00Z"},
                       "spec":{"image":"tk:6.4.0","metadataReplicas":3,"storageReplicas":4,"gatewayReplicas":2,"regionIds":["r1","r2"],"backupScheduleCron":"0 2 * * *"},
                       "status":{"phase":"READY","readyMetadata":3,"readyStorage":4,"readyGateway":2,"observedGeneration":3,"lastAction":"scale","lastBackupJob":"j1","lastBackupStatus":"OK","lastBackupTime":"t1","message":"healthy"}},
                      {"metadata":{"name":"c2"},"spec":{},"status":{"phase":""}}
                    ]}""");
            K8sClusterListView list = svc.listClusters();
            assertThat(list.enabled()).isTrue();
            assertThat(list.clusters()).hasSize(2);
            assertThat(list.clusters().get(0).regionIds()).containsExactly("r1", "r2");
            assertThat(list.clusters().get(1).namespace()).isEqualTo("tiering-kv");
            assertThat(list.clusters().get(1).phase()).isEqualTo("PENDING");

            // scale / upgrade 参数校验
            assertThatThrownBy(() -> svc.scaleCluster("INVALID_NAME", new K8sScaleRequest(2, null, null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("非法");
            assertThatThrownBy(() -> svc.scaleCluster("c1", new K8sScaleRequest(null, null, null)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("至少指定一项");
            assertThatThrownBy(() -> svc.upgradeCluster("c1", new K8sUpgradeRequest("tk:6.4\nbad")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("非法空白");

            // 正常 PATCH scale / upgrade / backup
            responseBody.set("{\"metadata\":{\"name\":\"c1\"},\"spec\":{\"storageReplicas\":5,\"gatewayReplicas\":2,\"metadataReplicas\":3,\"image\":\"tk:6.5.0\"},\"status\":{\"phase\":\"UPDATING\"}}");
            K8sClusterSummary scaled = svc.scaleCluster("c1", new K8sScaleRequest(5, 2, 3));
            assertThat(scaled.storageReplicas()).isEqualTo(5);
            K8sClusterSummary upgraded = svc.upgradeCluster("c1", new K8sUpgradeRequest("tk:6.5.0"));
            assertThat(upgraded.image()).isEqualTo("tk:6.5.0");
            K8sBackupTriggerResult backed = svc.triggerBackup("c1");
            assertThat(backed.clusterName()).isEqualTo("c1");

            // HTTP 404 / 401 / 403 / 500（超长响应截断）
            statusCode.set(404);
            assertThatThrownBy(() -> svc.listClusters())
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
            statusCode.set(401);
            assertThatThrownBy(() -> svc.listClusters())
                    .isInstanceOf(BusinessException.class).hasMessageContaining("鉴权失败");
            statusCode.set(403);
            assertThatThrownBy(() -> svc.listClusters())
                    .isInstanceOf(BusinessException.class).hasMessageContaining("鉴权失败");
            statusCode.set(500);
            responseBody.set("X".repeat(400));
            assertThatThrownBy(() -> svc.listClusters())
                    .isInstanceOf(BusinessException.class).hasMessageContaining("HTTP 500");

            // 无效 CA 文件在 https:// 路径触发证书加载异常
            Path badCa = tempDir.resolve("bad-ca.crt");
            Files.writeString(badCa, "not-a-cert", StandardCharsets.UTF_8);
            props.getKubernetes().setApiServer("https://127.0.0.1:" + k8s.getAddress().getPort());
            props.getKubernetes().setCaCertPath(badCa.toString());
            assertThatThrownBy(svc::listClusters)
                    .isInstanceOf(BusinessException.class).hasMessageContaining("CA 证书");
        } finally {
            k8s.stop(0);
        }
    }

    // ==================== 8. KeyspaceService ====================

    @Test
    void keyspaceServiceCoversScanPipelineAllTypesMutationsAndErrorBranches() throws Exception {
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        KeyspaceService service = new KeyspaceService(instSvc, gateway, audit);
        DbInstance inst = sampleInstance(1L, "ks-inst", "127.0.0.1", 6379);
        when(instSvc.require(1L)).thenReturn(inst);

        // scan: 错误 / 结构异常 / IOException / 正常 nil 键数组与多键
        when(gateway.execute(inst, "SCAN", "0", "MATCH", "*", "COUNT", "1"))
                .thenReturn(new RespReply.Error("ERR scan"));
        assertThatThrownBy(() -> service.scan(1L, " ", "", 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("SCAN 失败");

        when(gateway.execute(inst, "SCAN", "0", "MATCH", "*", "COUNT", "200"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Simple("0")))); // size < 2
        assertThatThrownBy(() -> service.scan(1L, null, null, 999))
                .isInstanceOf(BusinessException.class).hasMessageContaining("返回结构异常");

        when(gateway.execute(inst, "SCAN", "5", "MATCH", "u:*", "COUNT", "10"))
                .thenThrow(new IOException("timeout"));
        assertThatThrownBy(() -> service.scan(1L, "5", "u:*", 10))
                .isInstanceOf(BusinessException.class).hasMessageContaining("通信失败");

        when(gateway.execute(inst, "SCAN", "0", "MATCH", "*", "COUNT", "10"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(null))));
        KeyScanPage emptyPage = service.scan(1L, "0", "*", 10);
        assertThat(emptyPage.finished()).isTrue();
        assertThat(emptyPage.keys()).isEmpty();

        // inspectBatch: 空 / 返回数不匹配 / IOException / 非 Integer TTL
        assertThat(service.inspectBatch(inst, List.of())).isEmpty();
        assertThat(service.inspectBatch(inst, null)).isEmpty();
        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of(new RespReply.Simple("string")));
        assertThatThrownBy(() -> service.inspectBatch(inst, List.of("k1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("条目数不匹配");

        doThrow(new IOException("eof")).when(gateway).executeBatch(eq(inst), any());
        assertThatThrownBy(() -> service.inspectBatch(inst, List.of("k1")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("通信失败");

        doReturn(List.of(new RespReply.Simple("string"), new RespReply.Simple("not-int")))
                .when(gateway).executeBatch(eq(inst), any());
        assertThat(service.inspectBatch(inst, List.of("k1")).get(0).ttl()).isEqualTo(-2L);

        // readValuesBatch: 覆盖全数据类型 + stream/ts safeDump 错误/IO + batch 条目不匹配/Error
        assertThat(service.readValuesBatch(inst, List.of())).isEmpty();
        assertThat(service.readValuesBatch(inst, null)).isEmpty();

        List<KeyEntry> mixed = List.of(
                new KeyEntry("s", "string", -1),
                new KeyEntry("h", "hash", -1),
                new KeyEntry("l", "list", -1),
                new KeyEntry("st", "set", -1),
                new KeyEntry("z", "zset", -1),
                new KeyEntry("j", "json", -1),
                new KeyEntry("stm", "stream", -1),
                new KeyEntry("ts", "timeseries", -1),
                new KeyEntry("unk", "unknown", -1),
                new KeyEntry("niltype", null, -1)
        );
        when(gateway.execute(inst, "XRANGE", "stm", "-", "+", "COUNT", "50"))
                .thenReturn(new RespReply.Error("unsupported stream"));
        when(gateway.execute(inst, "TS.RANGE", "ts", "-", "+"))
                .thenThrow(new IOException("ts io"));
        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of(
                new RespReply.Bulk("v".getBytes(StandardCharsets.UTF_8)),
                new RespReply.Array(List.of(new RespReply.Simple("f"), new RespReply.Integer(9))),
                new RespReply.Array(List.of(new RespReply.Simple("a"))),
                new RespReply.Array(List.of(new RespReply.Simple("b"))),
                new RespReply.Array(List.of(new RespReply.Simple("m"), new RespReply.Simple("1.5"))),
                new RespReply.Bulk("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
        ));
        List<Object> vals = service.readValuesBatch(inst, mixed);
        assertThat(vals).hasSize(10);
        assertThat(vals.get(0)).isEqualTo("v");
        assertThat(vals.get(1)).isEqualTo(Map.of("f", "9"));
        assertThat(vals.get(6)).isEqualTo(Map.of("unsupported", "unsupported stream"));
        assertThat(vals.get(7)).isEqualTo(Map.of("unsupported", "读取失败：ts io"));
        assertThat(vals.get(8)).isNull();
        assertThat(vals.get(9)).isNull();

        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of(new RespReply.Error("WRONGTYPE")));
        assertThatThrownBy(() -> service.readValuesBatch(inst, List.of(new KeyEntry("s", "string", -1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("WRONGTYPE");

        // detail: none -> 404 / string / stream / timeseries / unknown
        when(gateway.execute(inst, "TYPE", "gone")).thenReturn(new RespReply.Simple("none"));
        when(gateway.execute(inst, "TTL", "gone")).thenReturn(new RespReply.Integer(-2));
        assertThatThrownBy(() -> service.detail(1L, "gone"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("键不存在");

        when(gateway.execute(inst, "TYPE", "s1")).thenReturn(new RespReply.Simple("string"));
        when(gateway.execute(inst, "TTL", "s1")).thenReturn(new RespReply.Integer(60));
        when(gateway.execute(inst, "GET", "s1")).thenReturn(new RespReply.Bulk("ok".getBytes(StandardCharsets.UTF_8)));
        KeyDetail detail = service.detail(1L, "s1");
        assertThat(detail.value()).isEqualTo("ok");

        when(gateway.execute(inst, "TYPE", "x")).thenReturn(new RespReply.Simple("weird"));
        when(gateway.execute(inst, "TTL", "x")).thenReturn(new RespReply.Simple("bad"));
        assertThat(service.detail(1L, "x").value()).isNull();

        // delete / expire / setString / setHashField / deleteHashField 分支
        when(gateway.execute(inst, "DEL", "s1")).thenReturn(new RespReply.Simple("OK")); // non-int -> 0
        service.delete(1L, "s1");
        when(gateway.execute(inst, "PERSIST", "s1")).thenReturn(new RespReply.Integer(1));
        service.expire(1L, "s1", 0);
        when(gateway.execute(inst, "EXPIRE", "s1", "30")).thenReturn(new RespReply.Integer(1));
        service.expire(1L, "s1", 30);

        when(gateway.execute(inst, "TYPE", "h1")).thenReturn(new RespReply.Simple("hash"));
        assertThatThrownBy(() -> service.setString(1L, "h1", "v"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能用 SET 覆盖");
        when(gateway.execute(inst, "SET", "s1", "v2")).thenReturn(new RespReply.Simple("OK"));
        service.setString(1L, "s1", "v2");

        assertThatThrownBy(() -> service.setHashField(1L, "s1", "f", "v"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能写入 hash");
        when(gateway.execute(inst, "HSET", "h1", "f1", "v1")).thenReturn(new RespReply.Integer(1));
        service.setHashField(1L, "h1", "f1", "v1");

        assertThatThrownBy(() -> service.deleteHashField(1L, "s1", "f1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不是 hash");
        when(gateway.execute(inst, "HDEL", "h1", "missing")).thenReturn(new RespReply.Integer(0));
        assertThatThrownBy(() -> service.deleteHashField(1L, "h1", "missing"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("字段不存在");
        when(gateway.execute(inst, "HDEL", "h1", "f1")).thenReturn(new RespReply.Integer(1));
        service.deleteHashField(1L, "h1", "f1");
    }

    // ==================== 9. AlertStateStore 纯内存与异常链全分支 ====================

    @Test
    void alertStateStoreCoversInMemoryModeNullGuardsDbExceptionsAndClassifierMatrix() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-11-27T12:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };

        // 1. 纯内存模式全路径
        AlertStateStore mem = AlertStateStore.inMemoryOnly(clock);
        LocalDateTime t0 = mem.recordOrGetSatisfiedSince(1L, 2L, null);
        assertThat(mem.recordOrGetSatisfiedSince(1L, 2L, t0.plusSeconds(20))).isEqualTo(t0);
        mem.clearSatisfiedSince(null, 2L);
        mem.clearSatisfiedSince(1L, null);
        mem.clearSatisfiedSince(1L, 2L);
        assertThat(mem.recordOrGetSatisfiedSince(1L, 2L, t0.plusSeconds(30))).isEqualTo(t0.plusSeconds(30));

        // resolved / silence=0 / eventId=null / silence>0 边界
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, null, 60, true)).isTrue();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 50L, 60, true)).isTrue();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 50L, 60, true)).isFalse();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, null, 0, false)).isTrue();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 51L, 0, false)).isTrue();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 51L, 0, false)).isFalse();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 52L, 60, false)).isTrue();
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 53L, 60, false)).isFalse();
        nowRef.set(nowRef.get().plusSeconds(61));
        assertThat(mem.tryClaimWebhookDelivery(1L, 2L, 3L, 54L, 60, false)).isTrue();

        // 清理 null 守卫与正常内存清理
        mem.clearForRule(null);
        mem.clearForRule(2L);
        mem.clearForChannel(null);
        mem.clearForChannel(1L);
        mem.clearForInstance(null);
        mem.clearForInstance(3L);
        assertThat(mem.purgeStaleBefore(LocalDateTime.now(clock).plusDays(1))).isZero();

        // 2. DB 模式下异常吞并分支 + resolved/silence=0 + 异常分类器全分支
        AlertEvalStateRepository brokenRepo = mock(AlertEvalStateRepository.class, inv -> {
            throw new DataAccessResourceFailureException("db down");
        });
        PlatformTransactionManager noopTx = mock(PlatformTransactionManager.class);
        when(noopTx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        AlertStateStore dbBroken = new AlertStateStore(brokenRepo, noopTx, clock);
        dbBroken.clearSatisfiedSince(1L, 1L);
        dbBroken.clearForRule(1L);
        dbBroken.clearForChannel(1L);
        dbBroken.clearForInstance(1L);
        assertThat(dbBroken.purgeStaleBefore(LocalDateTime.now(clock))).isZero();

        // resolved + eventId=null / silence=0 + eventId=null 在 DB 正常下返回 true
        AlertEvalStateRepository okRepo = mock(AlertEvalStateRepository.class);
        AlertStateStore dbOk = new AlertStateStore(okRepo, noopTx, clock);
        when(okRepo.existsById(anyString())).thenReturn(true);
        assertThat(dbOk.tryClaimWebhookDelivery(1L, 2L, 3L, null, 60, true)).isTrue();
        assertThat(dbOk.tryClaimWebhookDelivery(1L, 2L, 3L, null, 0, false)).isTrue();

        // 分类器边界：各类异常与 SQLState/ErrorCode
        assertThat(AlertStateStore.isContentionOrLockFailure(new SQLException("dup", "23505", 23505))).isTrue();
        assertThat(AlertStateStore.isContentionOrLockFailure(new SQLException("lock", "HY000", 50200))).isTrue();
        assertThat(AlertStateStore.isContentionOrLockFailure(new jakarta.persistence.OptimisticLockException())).isTrue();
        assertThat(AlertStateStore.isContentionOrLockFailure(new jakarta.persistence.PessimisticLockException())).isTrue();
        assertThat(AlertStateStore.isContentionOrLockFailure(new RuntimeException("plain"))).isFalse();

        assertThat(AlertStateStore.isDatabaseUnavailable(new SQLTransientConnectionException("conn"))).isTrue();
        assertThat(AlertStateStore.isDatabaseUnavailable(new SQLNonTransientConnectionException("conn"))).isTrue();
        assertThat(AlertStateStore.isDatabaseUnavailable(new RuntimeException(new ConnectException("refused")))).isTrue();
        assertThat(AlertStateStore.isDatabaseUnavailable(new RuntimeException(new SocketTimeoutException("read")))).isTrue();
        assertThat(AlertStateStore.isDatabaseUnavailable(new SQLException("link", "08001", 0))).isTrue();
        assertThat(AlertStateStore.isDatabaseUnavailable(new RuntimeException("plain"))).isFalse();
    }

    // ==================== 辅助工具 ====================

    private static DbInstance sampleInstance(Long id, String name, String host, int port) {
        DbInstance inst = new DbInstance();
        inst.setId(id);
        inst.setName(name);
        inst.setHost(host);
        inst.setPort(port);
        inst.setEnabled(true);
        return inst;
    }

    private static User sampleUser(Long id, String username, Role role, int status) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    private static void authenticateAs(User user) {
        UserPrincipal principal = new UserPrincipal(user.getId(), user.getUsername(), user.getRole());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @FunctionalInterface
    interface RespHandler {
        String handle(List<String> args);
    }

    static final class MiniRespServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;

        MiniRespServer(RespHandler handler) throws IOException {
            this.server = new ServerSocket(0);
            this.worker = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        Thread.startVirtualThread(() -> serve(client, handler));
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        private static void serve(Socket socket, RespHandler handler) {
            try (socket;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = socket.getOutputStream()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("*")) {
                        continue;
                    }
                    int count = Integer.parseInt(line.substring(1).trim());
                    List<String> args = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String lenLine = reader.readLine();
                        if (lenLine == null || !lenLine.startsWith("$")) {
                            return;
                        }
                        int len = Integer.parseInt(lenLine.substring(1).trim());
                        char[] buf = new char[len];
                        int read = 0;
                        while (read < len) {
                            int n = reader.read(buf, read, len - read);
                            if (n < 0) {
                                return;
                            }
                            read += n;
                        }
                        reader.readLine();
                        args.add(new String(buf));
                    }
                    out.write(handler.handle(args).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            worker.interrupt();
        }
    }
}
