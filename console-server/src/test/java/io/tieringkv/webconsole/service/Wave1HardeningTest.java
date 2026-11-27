package io.tieringkv.webconsole.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.config.ProductionConfigGuard;
import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertLevel;
import io.tieringkv.webconsole.domain.AlertRule;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.AlertEventRepository;
import io.tieringkv.webconsole.repository.AlertRuleRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 1（P0）回归验证：
 * B1 Prometheus 冷表/P99 键名修复、B2 告警运算符/手动关闭重置/分层指标字典、
 * B3 键空间分析并发限流/时间预算/JSON.STRLEN 降级、M1 消除 N+1、H5 告警计数缓存、S1 生产配置提醒。
 */
class Wave1HardeningTest {

    @Test
    void collectOnceMapsRealServerInfoFieldsToPrometheusAndAlertDictionary() {
        InstanceService instanceService = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        MetricPointRepository metricRepo = mock(MetricPointRepository.class);
        DbInstanceRepository instanceRepo = mock(DbInstanceRepository.class);
        AlertService alertService = mock(AlertService.class);
        AlertEventRepository alertEventRepo = mock(AlertEventRepository.class);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsoleMetricsBinder binder = new ConsoleMetricsBinder(registry, gateway, alertEventRepo);

        MonitorService monitorService = new MonitorService(
                instanceService, gateway, metricRepo, instanceRepo, alertService);
        monitorService.setMetricsBinder(binder);

        DbInstance inst = new DbInstance();
        inst.setId(42L);
        inst.setName("tiering-prod");
        when(gateway.info(inst)).thenReturn("""
                # Server
                redis_version:6.5.0
                uptime_in_seconds:1200
                # Clients
                connected_clients:12
                active_requests:2
                # Memory
                used_memory:52428800
                maxmemory:104857600
                # Stats
                qps:5000.0
                instantaneous_ops_per_sec:5100.0
                avg_latency_ms:0.420
                latency_p99_ms:4.250
                errors:0
                # Tiering
                tiering_cold_sstable_count:7
                tiering_block_cache_hit_rate:0.8650
                tiering_migration_failed:2
                # Keyspace
                db0:keys=12000,expires=500,avg_ttl=60000
                """);

        monitorService.collectOnce(inst);

        // B1：验证 Prometheus 导出的是真实非零值，而非永久 0
        assertThat(registry.get("tieringkv_instance_cold_tables").gauge().value()).isEqualTo(7.0d);
        assertThat(registry.get("tieringkv_instance_p99_latency_ms").gauge().value()).isEqualTo(4.25d);

        // B2：验证告警字典包含冷热分层与 P99 指标
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Double>> dictCaptor = ArgumentCaptor.forClass(Map.class);
        verify(alertService).evaluate(eq(42L), dictCaptor.capture(), any(LocalDateTime.class));
        assertThat(dictCaptor.getValue())
                .containsEntry("tiering_cold_sstable_count", 7.0d)
                .containsEntry("tiering_block_cache_hit_rate", 0.865d)
                .containsEntry("latency_p99_ms", 4.25d)
                .containsEntry("tiering_migration_failed", 2.0d);
    }

    @Test
    void alertServiceSupportsGteLteIgnoresUnknownOperatorAndResetsDurationOnManualResolve() {
        assertThat(AlertService.matchesThreshold("GT", 5.0, 5.0)).isFalse();
        assertThat(AlertService.matchesThreshold("GTE", 5.0, 5.0)).isTrue();
        assertThat(AlertService.matchesThreshold("LT", 5.0, 5.0)).isFalse();
        assertThat(AlertService.matchesThreshold("LTE", 5.0, 5.0)).isTrue();
        assertThat(AlertService.matchesThreshold("NEQ", 5.0, 5.0)).isNull();
        assertThat(AlertService.matchesThreshold(null, 5.0, 5.0)).isNull();

        AlertRuleRepository ruleRepo = mock(AlertRuleRepository.class);
        AlertEventRepository eventRepo = mock(AlertEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AlertWebhookDispatcher dispatcher = mock(AlertWebhookDispatcher.class);

        AlertRule rule = new AlertRule();
        rule.setId(10L);
        rule.setName("p99-high");
        rule.setMetric("latency_p99_ms");
        rule.setOperator("GTE");
        rule.setThreshold(5.0);
        rule.setDurationSeconds(60);
        rule.setLevel(AlertLevel.WARN);
        rule.setEnabled(true);

        AlertRule invalidRule = new AlertRule();
        invalidRule.setId(11L);
        invalidRule.setName("bad-op");
        invalidRule.setMetric("latency_p99_ms");
        invalidRule.setOperator("BOGUS");
        invalidRule.setThreshold(100.0);
        invalidRule.setDurationSeconds(0);
        invalidRule.setLevel(AlertLevel.WARN);
        invalidRule.setEnabled(true);

        when(ruleRepo.findByEnabledTrue()).thenReturn(List.of(rule, invalidRule));
        when(eventRepo.findByInstanceIdAndStatusOrderByFiredAtDesc(1L, AlertStatus.FIRING))
                .thenReturn(List.of());
        when(eventRepo.save(any(AlertEvent.class))).thenAnswer(inv -> {
            AlertEvent ev = inv.getArgument(0);
            if (ev.getId() == null) {
                ev.setId(501L);
            }
            return ev;
        });

        AlertService service = new AlertService(ruleRepo, eventRepo, auditService, dispatcher);
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 27, 12, 0, 0);

        // t0 首次达标（未满 60s 不触发，非法运算符规则忽略）
        service.evaluate(1L, Map.of("latency_p99_ms", 5.0), t0);
        verify(eventRepo, never()).save(any());

        // t0 + 60s 满足持续时长触发 1 次（且全程仅使用批量查询而非逐规则查询）
        service.evaluate(1L, Map.of("latency_p99_ms", 5.0), t0.plusSeconds(60));
        verify(eventRepo, times(1)).save(any());
        verify(eventRepo, never()).findFirstByRuleIdAndInstanceIdAndStatusOrderByFiredAtDesc(any(), any(), any());

        // 手动关闭事件应清空 satisfiedSince 并推送恢复通知
        AlertEvent firing = new AlertEvent();
        firing.setId(501L);
        firing.setRuleId(10L);
        firing.setInstanceId(1L);
        firing.setRuleName("p99-high");
        firing.setStatus(AlertStatus.FIRING);
        when(eventRepo.findById(501L)).thenReturn(Optional.of(firing));

        service.resolve(501L);
        assertThat(firing.getStatus()).isEqualTo(AlertStatus.RESOLVED);
        verify(dispatcher, times(2)).dispatchAsync(any(AlertEvent.class));

        // 5 秒后指标仍超阈值：因为 satisfiedSince 已重置，未满 60s 不得立即重复触发
        service.evaluate(1L, Map.of("latency_p99_ms", 6.0), t0.plusSeconds(65));
        verify(eventRepo, times(2)).save(any()); // 仍只有之前 1 次触发 + 1 次 resolve
    }

    @Test
    void consoleMetricsBinderCachesFiringAlertCountAndRefreshesOnInvalidation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RespGateway gateway = mock(RespGateway.class);
        AlertEventRepository alertEventRepo = mock(AlertEventRepository.class);
        when(alertEventRepo.countByStatus(AlertStatus.FIRING)).thenReturn(3L, 1L);

        ConsoleMetricsBinder binder = new ConsoleMetricsBinder(registry, gateway, alertEventRepo);

        // 短时间内多次抓取只查库 1 次
        assertThat(registry.get("tieringkv_console_alerts_firing").gauge().value()).isEqualTo(3.0d);
        assertThat(registry.get("tieringkv_console_alerts_firing").gauge().value()).isEqualTo(3.0d);
        assertThat(registry.get("tieringkv_console_alerts_firing").gauge().value()).isEqualTo(3.0d);
        verify(alertEventRepo, times(1)).countByStatus(AlertStatus.FIRING);

        // 告警状态变化失效缓存后立即反映最新值
        binder.invalidateFiringAlertCache();
        assertThat(registry.get("tieringkv_console_alerts_firing").gauge().value()).isEqualTo(1.0d);
        verify(alertEventRepo, times(2)).countByStatus(AlertStatus.FIRING);
    }

    @Test
    void latestByInstanceUsesSingleBatchQueryInsteadOfNPlusOne() {
        InstanceService instanceService = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        MetricPointRepository metricRepo = mock(MetricPointRepository.class);
        DbInstanceRepository instanceRepo = mock(DbInstanceRepository.class);
        AlertService alertService = mock(AlertService.class);

        DbInstance i1 = new DbInstance();
        i1.setId(1L);
        DbInstance i2 = new DbInstance();
        i2.setId(2L);
        when(instanceService.enabled()).thenReturn(List.of(i1, i2));

        MetricPoint p1 = new MetricPoint();
        p1.setInstanceId(1L);
        p1.setQps(100.0);
        MetricPoint p2 = new MetricPoint();
        p2.setInstanceId(2L);
        p2.setQps(200.0);
        when(metricRepo.findLatestByInstanceIds(List.of(1L, 2L))).thenReturn(List.of(p2, p1));

        MonitorService monitorService = new MonitorService(
                instanceService, gateway, metricRepo, instanceRepo, alertService);
        Map<Long, MetricPoint> latest = monitorService.latestByInstance();

        assertThat(latest.keySet()).containsExactly(1L, 2L);
        assertThat(latest.get(1L).getQps()).isEqualTo(100.0);
        assertThat(latest.get(2L).getQps()).isEqualTo(200.0);
        verify(metricRepo, times(1)).findLatestByInstanceIds(List.of(1L, 2L));
        verify(metricRepo, never()).findFirstByInstanceIdOrderByCollectedAtDesc(any());
    }

    @Test
    void analyzerPrefersJsonStrlenFallsBackSafelyAndEnforcesConcurrencyAndTimeBudget() throws Exception {
        InstanceService instanceService = mock(InstanceService.class);
        KeyspaceService keyspaceService = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);

        DbInstance inst = new DbInstance();
        inst.setId(5L);
        inst.setName("json-inst");
        when(instanceService.require(5L)).thenReturn(inst);
        when(gateway.execute(inst, "DBSIZE")).thenReturn(new RespReply.Integer(2L));
        when(gateway.execute(eq(inst), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("doc:ok".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("doc:legacy".getBytes(StandardCharsets.UTF_8)))))));
        when(keyspaceService.inspectBatch(eq(inst), anyList())).thenReturn(List.of(
                new KeyEntry("doc:ok", "json", -1L),
                new KeyEntry("doc:legacy", "json", 120L)));
        // 首次批次 JSON.STRLEN：第一条成功返回数组长度，第二条模拟旧版内核返回 Error 触发小批次 JSON.GET 降级
        when(gateway.executeBatch(eq(inst), anyList()))
                .thenReturn(List.of(
                        new RespReply.Array(List.of(new RespReply.Integer(256L))),
                        new RespReply.Error("ERR unknown command JSON.STRLEN")))
                .thenReturn(List.of(
                        new RespReply.Bulk("{\"k\":\"value\"}".getBytes(StandardCharsets.UTF_8))));

        KeyspaceAnalyzerService analyzer = new KeyspaceAnalyzerService(
                instanceService, keyspaceService, gateway, 2, 15_000L);
        KeyspaceAnalyzerService.KeyspaceAnalysisView view = analyzer.analyze(
                5L, new KeyspaceAnalyzerService.AnalyzeRequest("*", 100, ":", 10));

        assertThat(view.bigKeys()).hasSize(2);
        assertThat(view.bigKeys().get(0).key()).isEqualTo("doc:ok");
        assertThat(view.bigKeys().get(0).sizeUnits()).isEqualTo(256L);
        assertThat(view.bigKeys().get(1).key()).isEqualTo("doc:legacy");
        assertThat(view.bigKeys().get(1).sizeUnits()).isEqualTo("{\"k\":\"value\"}".getBytes(StandardCharsets.UTF_8).length);

        // 并发限流验证（maxConcurrent = 1）
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RespGateway slowGateway = mock(RespGateway.class);
        when(slowGateway.execute(inst, "DBSIZE")).thenAnswer(inv -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new RespReply.Integer(0L);
        });
        when(slowGateway.execute(eq(inst), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of()))));

        KeyspaceAnalyzerService singleSlotAnalyzer = new KeyspaceAnalyzerService(
                instanceService, keyspaceService, slowGateway, 1, 15_000L);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<KeyspaceAnalyzerService.KeyspaceAnalysisView> running = pool.submit(
                    () -> singleSlotAnalyzer.analyze(5L, new KeyspaceAnalyzerService.AnalyzeRequest("*", 100, ":", 10)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> singleSlotAnalyzer.analyze(
                    5L, new KeyspaceAnalyzerService.AnalyzeRequest("*", 100, ":", 10)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getStatus())
                    .isEqualTo(429);
            release.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).scannedKeys()).isZero();
        } finally {
            pool.shutdownNow();
        }
        // 验证时间预算耗尽时自动截断并附带提示（覆盖 SCAN 与 JSON 降级路径）
        RespGateway delayedGateway = mock(RespGateway.class);
        when(delayedGateway.execute(inst, "DBSIZE")).thenReturn(new RespReply.Integer(100L));
        when(delayedGateway.execute(eq(inst), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(30);
                    return new RespReply.Array(List.of(
                            new RespReply.Bulk("99".getBytes(StandardCharsets.UTF_8)),
                            new RespReply.Array(List.of(new RespReply.Bulk("k:1".getBytes(StandardCharsets.UTF_8))))));
                });
        KeyspaceAnalyzerService tightBudgetAnalyzer = new KeyspaceAnalyzerService(
                instanceService, keyspaceService, delayedGateway, 2, 10L);
        KeyspaceAnalyzerService.KeyspaceAnalysisView truncatedView = tightBudgetAnalyzer.analyze(
                5L, new KeyspaceAnalyzerService.AnalyzeRequest("*", 100, ":", 10));
        assertThat(truncatedView.truncated()).isTrue();
        assertThat(truncatedView.recommendations()).anySatisfy(tip -> assertThat(tip).contains("时间预算"));
    }

    @Test
    void productionGuardWarnsWhenWebhookSsrfProtectionDisabled() {
        ConsoleProperties props = new ConsoleProperties();
        props.getJwt().setSecret("Zk8Qv2LmN7pR4tXyA9bC3dE6fG1hJ5kM0nP8sU2w");
        props.getBootstrap().setAdminPassword("AdminPass!2345");
        props.getSecurity().setBlockPrivateWebhookUrls(false);

        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "S3cure-db-pass")
                .withProperty("spring.datasource.username", "console_app")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/tiering_console");

        ProductionConfigGuard guard = new ProductionConfigGuard(props, env);
        assertThat(guard.check()).anyMatch(f ->
                "WARN".equals(f.level()) && "console.security.block-private-webhook-urls".equals(f.key()));

        // 过宽白名单网段（如 10.0.0.0/8）触发 WARN，精确网关 /32 不触发
        props.getSecurity().setBlockPrivateWebhookUrls(true);
        props.getSecurity().setWebhookAllowedCidrs(List.of("10.0.0.0/8"));
        assertThat(new ProductionConfigGuard(props, env).check()).anyMatch(f ->
                "WARN".equals(f.level()) && "console.security.webhook-allowed-cidrs".equals(f.key()));

        props.getSecurity().setWebhookAllowedCidrs(List.of("10.20.8.15/32"));
        assertThat(new ProductionConfigGuard(props, env).check()).noneMatch(f ->
                "console.security.webhook-allowed-cidrs".equals(f.key()));
    }
}
