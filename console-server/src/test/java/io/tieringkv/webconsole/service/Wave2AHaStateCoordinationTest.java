package io.tieringkv.webconsole.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.AlertEvalState;
import io.tieringkv.webconsole.domain.AlertLevel;
import io.tieringkv.webconsole.domain.AlertRule;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.InstanceDeletedEvent;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.AlertEvalStateRepository;
import io.tieringkv.webconsole.repository.AlertEventRepository;
import io.tieringkv.webconsole.repository.AlertRuleRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.web.dto.AlertRuleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wave 2A（P1-HA）：控制面去单机态专项回归验证。
 *
 * <ul>
 *   <li>H2：主备切换后 {@code satisfied_since} 无缝续接、8 线程并发 CAS 原子防重发（FIRING/RESOLVED）、
 *       删除清理与 7 天过期回收、MySQL 不可用保守本地降级；</li>
 *   <li>H3：多实例并行采集不因单慢实例阻塞其余实例，且严守 70% 租约预算防脑裂；</li>
 *   <li>H4/H1：实例连接指纹感知变化自动驱逐旧池，跨副本周期对账清理已删实例池/指标/锁/状态。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class Wave2AHaStateCoordinationTest {

    @Autowired
    private AlertEvalStateRepository stateRepository;

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEventRepository eventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditService auditService;

    private AtomicReference<Instant> clockRef;
    private Clock mutableClock;

    @BeforeEach
    void setUp() {
        stateRepository.deleteAll();
        eventRepository.deleteAll();
        ruleRepository.deleteAll();
        clockRef = new AtomicReference<>(Instant.parse("2026-11-27T10:00:00Z"));
        mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clockRef.get();
            }
        };
    }

    @Test
    void h2AlertDurationSurvivesLeaderFailoverAndClearsOnRecovery() {
        AlertStateStore storeA = new AlertStateStore(stateRepository, transactionManager, mutableClock);
        AlertStateStore storeB = new AlertStateStore(stateRepository, transactionManager, mutableClock);

        AlertService replicaA = new AlertService(ruleRepository, eventRepository, auditService, null, storeA);
        AlertService replicaB = new AlertService(ruleRepository, eventRepository, auditService, null, storeB);

        AlertRule rule = replicaA.create(new AlertRuleRequest(
                "ha-duration-rule", null, "avg_latency_ms", "GTE", 10.0, 60, AlertLevel.CRIT, true));

        LocalDateTime t0 = LocalDateTime.of(2026, 11, 27, 10, 0, 0);
        // 副本 A 在 t0 首次观测超阈值，计时落库但不触发
        replicaA.evaluate(101L, Map.of("avg_latency_ms", 12.5), t0);
        assertThat(eventRepository.count()).isZero();
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(rule.getId(), 101L))).isTrue();

        // 模拟副本 A 宕机，副本 B 在 t0 + 60s 接管求值：无缝承接 MySQL 中的 satisfied_since 立即触发
        replicaB.evaluate(101L, Map.of("avg_latency_ms", 13.0), t0.plusSeconds(60));
        assertThat(eventRepository.countByStatus(AlertStatus.FIRING)).isEqualTo(1L);

        // 恢复后自动清理持久化持续时长记录
        replicaB.evaluate(101L, Map.of("avg_latency_ms", 1.2), t0.plusSeconds(70));
        assertThat(eventRepository.countByStatus(AlertStatus.FIRING)).isZero();
        assertThat(eventRepository.countByStatus(AlertStatus.RESOLVED)).isEqualTo(1L);
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(rule.getId(), 101L))).isFalse();
    }

    @Test
    void h2ConcurrentMultiReplicaWebhookClaimsAllowExactlyOneWinner() throws Exception {
        int replicas = 8;
        ExecutorService pool = Executors.newFixedThreadPool(replicas);
        try {
            // 1. 首次 FIRING 8 副本并发争抢：严格只允许 1 个副本发送
            int firstWinners = raceClaims(pool, replicas, () -> {
                AlertStateStore replicaStore = new AlertStateStore(stateRepository, transactionManager, mutableClock);
                return replicaStore.tryClaimWebhookDelivery(7L, 11L, 1L, 9001L, 300, false);
            });
            assertThat(firstWinners).isEqualTo(1);

            // 2. 静默窗口内（+100s）对端副本尝试发送：0 个胜出
            clockRef.set(clockRef.get().plusSeconds(100));
            AlertStateStore peerStore = new AlertStateStore(stateRepository, transactionManager, mutableClock);
            assertThat(peerStore.tryClaimWebhookDelivery(7L, 11L, 1L, 9002L, 300, false)).isFalse();

            // 3. 静默期满（+301s）8 副本再次并发 CAS 续窗：仍严格只允许 1 个副本胜出
            clockRef.set(clockRef.get().plusSeconds(201));
            int renewalWinners = raceClaims(pool, replicas, () -> {
                AlertStateStore replicaStore = new AlertStateStore(stateRepository, transactionManager, mutableClock);
                return replicaStore.tryClaimWebhookDelivery(7L, 11L, 1L, 9003L, 300, false);
            });
            assertThat(renewalWinners).isEqualTo(1);

            // 4. RESOLVED 恢复事件 8 副本并发推送同一 eventId：严格仅 1 个副本发送，且清空静默窗口
            int resolvedWinners = raceClaims(pool, replicas, () -> {
                AlertStateStore replicaStore = new AlertStateStore(stateRepository, transactionManager, mutableClock);
                return replicaStore.tryClaimWebhookDelivery(7L, 11L, 1L, 9003L, 300, true);
            });
            assertThat(resolvedWinners).isEqualTo(1);
            assertThat(stateRepository.existsById(AlertStateStore.notifyKey(7L, 11L, 1L))).isFalse();

            // 5. 静默窗口已被恢复清除，新故障立刻可发送
            assertThat(peerStore.tryClaimWebhookDelivery(7L, 11L, 1L, 9004L, 300, false)).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void h2CleanupAndStalePurgeRemoveOnlyMatchingRows() {
        AlertStateStore store = new AlertStateStore(stateRepository, transactionManager, mutableClock);
        LocalDateTime now = LocalDateTime.now(mutableClock);

        store.recordOrGetSatisfiedSince(10L, 1L, now);
        store.recordOrGetSatisfiedSince(10L, 2L, now);
        store.recordOrGetSatisfiedSince(20L, 1L, now);
        store.tryClaimWebhookDelivery(5L, 10L, 1L, 100L, 60, false);
        store.tryClaimWebhookDelivery(6L, 20L, 2L, 101L, 60, false);

        // 按规则 10 清理不得影响规则 20
        store.clearForRule(10L);
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(10L, 1L))).isFalse();
        assertThat(stateRepository.existsById(AlertStateStore.notifyKey(5L, 10L, 1L))).isFalse();
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(20L, 1L))).isTrue();
        assertThat(stateRepository.existsById(AlertStateStore.notifyKey(6L, 20L, 2L))).isTrue();

        // 按实例 2 清理不得影响实例 1
        store.clearForInstance(2L);
        assertThat(stateRepository.existsById(AlertStateStore.notifyKey(6L, 20L, 2L))).isFalse();
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(20L, 1L))).isTrue();

        // 插入一条 10 天前的过期行，验证 purgeStaleBefore 仅清过期行
        AlertEvalState oldRow = new AlertEvalState();
        oldRow.setStateKey("resolved:9:888");
        oldRow.setLastNotifiedAt(now.minusDays(10));
        oldRow.setUpdatedAt(now.minusDays(10));
        stateRepository.saveAndFlush(oldRow);

        int purged = store.purgeStaleBefore(now.minusDays(7));
        assertThat(purged).isEqualTo(1);
        assertThat(stateRepository.existsById("resolved:9:888")).isFalse();
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(20L, 1L))).isTrue();
    }

    @Test
    void h2DegradesSafelyToConservativeLocalSilenceWhenDatabaseUnavailable() {
        // 模拟数据库彻底断连：所有仓储调用均抛出 DataAccessResourceFailureException
        AlertEvalStateRepository brokenRepo = mock(AlertEvalStateRepository.class, inv -> {
            throw new DataAccessResourceFailureException("MySQL unavailable");
        });

        AlertStateStore degradedStore = new AlertStateStore(brokenRepo, transactionManager, mutableClock);
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 27, 10, 0, 0);

        // 持续时长记录不抛异常且降级到本地缓存保持首次时间戳
        assertThat(degradedStore.recordOrGetSatisfiedSince(1L, 1L, t0)).isEqualTo(t0);
        assertThat(degradedStore.recordOrGetSatisfiedSince(1L, 1L, t0.plusSeconds(15))).isEqualTo(t0);

        // DB 故障时即使规则静默为 0，也启用保守 30s 本地静默防告警风暴
        assertThat(degradedStore.tryClaimWebhookDelivery(1L, 1L, 1L, 10L, 0, false)).isTrue();
        assertThat(degradedStore.tryClaimWebhookDelivery(1L, 1L, 1L, 11L, 0, false)).isFalse();
        clockRef.set(clockRef.get().plusSeconds(31));
        assertThat(degradedStore.tryClaimWebhookDelivery(1L, 1L, 1L, 12L, 0, false)).isTrue();

        // 本地兜底按规则 ID=1 清理不得误删 channelId=1, ruleId=10 的条目
        assertThat(degradedStore.tryClaimWebhookDelivery(1L, 10L, 2L, 13L, 60, false)).isTrue();
        degradedStore.clearForRule(1L);
        assertThat(degradedStore.tryClaimWebhookDelivery(1L, 10L, 2L, 14L, 60, false)).isFalse();
    }

    @Test
    void h2DeadlockLockTimeoutAndUnknownDbErrorsNeverFallbackToDuplicateLocalSend() {
        // 分别验证：死锁 (1213)、无法获取锁、锁等待超时 (QueryTimeoutException / MySQL 1205)、未知异常均严格 fail-closed
        List<RuntimeException> nonOutageErrors = List.of(
                new DeadlockLoserDataAccessException("deadlock", new SQLException("Deadlock found", "40001", 1213)),
                new CannotAcquireLockException("lock busy", new SQLException("Lock wait timeout", "41000", 1205)),
                new QueryTimeoutException("lock wait timeout", new SQLException("Lock wait timeout", "HY000", 1205)),
                new InvalidDataAccessApiUsageException("unexpected sql error")
        );
        for (RuntimeException err : nonOutageErrors) {
            AlertEvalStateRepository repo = mock(AlertEvalStateRepository.class, inv -> {
                throw err;
            });
            AlertStateStore store = new AlertStateStore(repo, transactionManager, mutableClock);
            assertThat(store.tryClaimWebhookDelivery(7L, 11L, 1L, 100L, 60, false))
                    .as("Exception %s must never fallback to local duplicate send", err.getClass().getSimpleName())
                    .isFalse();
        }
    }

    @Test
    void h3MultiRoundSlowSocketStressBoundsThreadsQueueAndReleasesConnections() throws Exception {
        // 慢实例：accept TCP 后故意永不回包，且 readTimeoutMs(3400ms) > budgetMs(2800ms)，
        // 真实触发 CompletableFuture.allOf.get(budgetMs) TimeoutException，验证：
        // 1) cancel(true) 不会强杀阻塞 Socket 读取，线程依赖 readTimeoutMs 有界退出；
        // 2) inFlightInstances 去重保证单慢实例跨轮最多占 1 线程，线程池/队列永不堆积；
        // 3) 正常实例不被慢实例拖累且每轮落库；
        // 4) 超时后底层 Socket/信号量连接全量归还，无泄漏；
        // 5) 队列满时 AbortPolicy 不阻塞调度线程且不泄漏 inFlightInstances。
        try (ServerSocket slowServer = new ServerSocket(0);
             ServerSocket fastServer = new ServerSocket(0)) {
            List<Socket> acceptedSlow = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread slowAccept = Thread.startVirtualThread(() -> {
                while (!slowServer.isClosed()) {
                    try {
                        acceptedSlow.add(slowServer.accept());
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });
            Thread fastAccept = Thread.startVirtualThread(() -> {
                while (!fastServer.isClosed()) {
                    try {
                        Socket s = fastServer.accept();
                        Thread.startVirtualThread(() -> {
                            try (s; OutputStream out = s.getOutputStream()) {
                                byte[] buf = new byte[1024];
                                while (s.getInputStream().read(buf) > 0) {
                                    String info = "# Server\r\nredis_version:6.5.0\r\n# Stats\r\nqps:123\r\n";
                                    String resp = "$" + info.length() + "\r\n" + info + "\r\n";
                                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                }
                            } catch (IOException ignored) {
                            }
                        });
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });

            DbInstance slowInst = new DbInstance();
            slowInst.setId(601L);
            slowInst.setName("slow-hang-node");
            slowInst.setHost("127.0.0.1");
            slowInst.setPort(slowServer.getLocalPort());
            slowInst.setEnabled(true);

            DbInstance fastInst = new DbInstance();
            fastInst.setId(602L);
            fastInst.setName("fast-healthy-node");
            fastInst.setHost("127.0.0.1");
            fastInst.setPort(fastServer.getLocalPort());
            fastInst.setEnabled(true);

            ConsoleProperties props = new ConsoleProperties();
            props.getMetrics().setIntervalMs(2000L);     // leaseTtl=4000ms -> budgetMs=2800ms
            props.getMetrics().setConnectTimeoutMs(200);
            props.getMetrics().setReadTimeoutMs(3400);   // 刻意大于 budgetMs(2800ms)，触发真实 TimeoutException

            RespGateway realGateway = new RespGateway(props);
            InstanceService instanceService = mock(InstanceService.class);
            when(instanceService.enabled()).thenReturn(List.of(slowInst, fastInst));
            MetricPointRepository metricRepo = mock(MetricPointRepository.class);
            AtomicInteger fastSaved = new AtomicInteger();
            when(metricRepo.save(any(MetricPoint.class))).thenAnswer(inv -> {
                fastSaved.incrementAndGet();
                return inv.getArgument(0);
            });
            DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
            AlertService alertService = mock(AlertService.class);

            ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
            pool.setCorePoolSize(4);
            pool.setMaxPoolSize(8);
            pool.setQueueCapacity(128);
            pool.setThreadNamePrefix("test-monitor-");
            pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
            pool.initialize();

            MonitorService monitorService = new MonitorService(
                    instanceService, realGateway, metricRepo, instRepo, alertService, null, props);
            monitorService.setCollectExecutor(pool);

            int maxActive = 0;
            int maxQueue = 0;
            int inFlightAfterTimeoutRound1 = 0;
            long fastRound2ElapsedMs;
            long drainMs;
            try {
                // 第 1 轮：慢实例超时触发 budgetMs(2800ms) TimeoutException，但底层 Socket 仍在阻塞等待 readTimeoutMs(3400ms)
                monitorService.scheduledCollect();
                inFlightAfterTimeoutRound1 = monitorService.inFlightCount();
                maxActive = Math.max(maxActive, pool.getActiveCount());
                maxQueue = Math.max(maxQueue, pool.getThreadPoolExecutor().getQueue().size());

                // 第 2 轮（立即触发）：慢实例在途被跳过，正常实例快速完成（不被慢实例拖累）
                long r2Start = System.currentTimeMillis();
                monitorService.scheduledCollect();
                fastRound2ElapsedMs = System.currentTimeMillis() - r2Start;
                maxActive = Math.max(maxActive, pool.getActiveCount());
                maxQueue = Math.max(maxQueue, pool.getThreadPoolExecutor().getQueue().size());

                // 等待第 1 轮慢实例触达 readTimeoutMs 自然退出后再连跑 2 轮，验证多轮不累积
                Thread.sleep(900L);
                monitorService.scheduledCollect();
                maxActive = Math.max(maxActive, pool.getActiveCount());
                maxQueue = Math.max(maxQueue, pool.getThreadPoolExecutor().getQueue().size());

                monitorService.scheduledCollect();
                maxActive = Math.max(maxActive, pool.getActiveCount());
                maxQueue = Math.max(maxQueue, pool.getThreadPoolExecutor().getQueue().size());

                // 等待最后一轮在途慢实例触发 readTimeoutMs(3400ms) 有界退出
                long waitStart = System.currentTimeMillis();
                while ((monitorService.inFlightCount() > 0 || pool.getActiveCount() > 0)
                        && System.currentTimeMillis() - waitStart < 5000L) {
                    Thread.sleep(50L);
                }
                drainMs = System.currentTimeMillis() - waitStart;

                System.out.println("H3_SLOW_SOCKET_STRESS_METRICS: rounds=4 inFlightAtTimeout=" + inFlightAfterTimeoutRound1
                        + " fastRound2ElapsedMs=" + fastRound2ElapsedMs
                        + " maxActive=" + maxActive + " maxQueue=" + maxQueue
                        + " drainMs=" + drainMs + " fastSaved=" + fastSaved.get()
                        + " slowAccepted=" + acceptedSlow.size()
                        + " activeConnsAfter=" + realGateway.totalActiveConnections());

                // 诚实验证：allOf 超时当下慢线程仍在阻塞读取 Socket（cancel(true) 不能打断同步 SocketInputStream.read）
                assertThat(inFlightAfterTimeoutRound1).isEqualTo(1);
                // 第 2 轮跳过在途慢实例，正常实例秒级完成
                assertThat(fastRound2ElapsedMs).isLessThan(1000L);
                // 活跃线程<=2、队列无累积、慢实例总共仅建立<=2次慢连接而非4次堆积
                assertThat(maxActive).isLessThanOrEqualTo(2);
                assertThat(maxQueue).isZero();
                assertThat(acceptedSlow.size()).isLessThanOrEqualTo(2);
                // 最终全部任务依 readTimeoutMs 有界退出、正常实例 4/4 全成功、连接池活跃连接归零无泄漏
                assertThat(monitorService.inFlightCount()).isZero();
                assertThat(pool.getActiveCount()).isZero();
                assertThat(fastSaved.get()).isEqualTo(4);
                assertThat(realGateway.totalActiveConnections()).isZero();

                // 队列饱和验证：1 线程 + 1 队列 + AbortPolicy 下提交 5 个实例，绝不阻塞调度线程且拒绝项不泄漏 inFlight
                ThreadPoolTaskExecutor tinyPool = new ThreadPoolTaskExecutor();
                tinyPool.setCorePoolSize(1);
                tinyPool.setMaxPoolSize(1);
                tinyPool.setQueueCapacity(1);
                tinyPool.setThreadNamePrefix("test-tiny-");
                tinyPool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
                tinyPool.initialize();
                try {
                    List<DbInstance> burst = new ArrayList<>();
                    for (long id = 701; id <= 705; id++) {
                        DbInstance s = new DbInstance();
                        s.setId(id);
                        s.setName("slow-" + id);
                        s.setHost("127.0.0.1");
                        s.setPort(slowServer.getLocalPort());
                        s.setEnabled(true);
                        burst.add(s);
                    }
                    ConsoleProperties tinyProps = new ConsoleProperties();
                    tinyProps.getMetrics().setIntervalMs(2000L);
                    tinyProps.getMetrics().setConnectTimeoutMs(150);
                    tinyProps.getMetrics().setReadTimeoutMs(400);
                    RespGateway tinyGw = new RespGateway(tinyProps);
                    try {
                        InstanceService burstInstSvc = mock(InstanceService.class);
                        when(burstInstSvc.enabled()).thenReturn(burst);
                        MonitorService burstMon = new MonitorService(
                                burstInstSvc, tinyGw, metricRepo, instRepo, alertService, null, tinyProps);
                        burstMon.setCollectExecutor(tinyPool);

                        long burstStart = System.currentTimeMillis();
                        burstMon.scheduledCollect();
                        long burstElapsed = System.currentTimeMillis() - burstStart;
                        // 被 AbortPolicy 拒绝的 3 个任务不阻塞调度线程，整轮快速返回且 inFlight 最终清零
                        assertThat(burstElapsed).isLessThan(3500L);
                        long waitTiny = System.currentTimeMillis();
                        while ((burstMon.inFlightCount() > 0 || tinyPool.getActiveCount() > 0)
                                && System.currentTimeMillis() - waitTiny < 3000L) {
                            Thread.sleep(50L);
                        }
                        assertThat(burstMon.inFlightCount()).isZero();
                        assertThat(tinyGw.totalActiveConnections()).isZero();
                    } finally {
                        tinyGw.shutdown();
                    }
                } finally {
                    tinyPool.shutdown();
                }
            } finally {
                pool.shutdown();
                realGateway.shutdown();
                for (Socket s : acceptedSlow) {
                    try { s.close(); } catch (IOException ignored) { }
                }
                slowServer.close();
                fastServer.close();
                slowAccept.join(1000);
                fastAccept.join(1000);
            }
        }
    }

    @Test
    void h4ReconcilerPreservesResourcesOnDbOutageRecoversAndClearsOnlyOnValidEmptyList() throws Exception {
        try (ServerSocket liveResp = new ServerSocket(0)) {
            Thread accept = Thread.startVirtualThread(() -> {
                while (!liveResp.isClosed()) {
                    try {
                        Socket s = liveResp.accept();
                        Thread.startVirtualThread(() -> {
                            try (s; OutputStream out = s.getOutputStream()) {
                                byte[] buf = new byte[512];
                                while (s.getInputStream().read(buf) > 0) {
                                    String info = "# Server\r\nredis_version:6.5.0\r\n";
                                    String resp = "$" + info.length() + "\r\n" + info + "\r\n";
                                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                }
                            } catch (IOException ignored) {
                            }
                        });
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });

            DbInstance live = new DbInstance();
            live.setId(1L);
            live.setName("alive-inst");
            live.setHost("127.0.0.1");
            live.setPort(liveResp.getLocalPort());

            ConsoleProperties props = new ConsoleProperties();
            RespGateway gateway = new RespGateway(props);
            try {
                // 真实建立连接池，确保 gateway.cachedInstanceIds() 包含实例 1
                assertThat(gateway.info(live)).contains("redis_version:6.5.0");
                assertThat(gateway.cachedInstanceIds()).containsExactly(1L);

                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                ConsoleMetricsBinder binder = new ConsoleMetricsBinder(registry, gateway, eventRepository);
                MetricPoint pt = new MetricPoint();
                pt.setUsedMemory(1024L);
                pt.setMaxMemory(4096L);
                binder.recordInstanceUp(live, pt, 1L, 0.5d);

                DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
                AlertStateStore store = new AlertStateStore(stateRepository, transactionManager, mutableClock);
                InstanceReconcileService reconciler = new InstanceReconcileService(
                        instRepo, gateway, binder, null, null, store);

                // 1. 正常列表：保留活跃实例的连接池与指标
                doReturn(List.of(live)).when(instRepo).findAll();
                assertThat(reconciler.reconcileAll()).isZero();
                assertThat(gateway.cachedInstanceIds()).containsExactly(1L);
                assertThat(binder.registeredInstanceIds()).containsExactly(1L);

                // 2. 数据库瞬断（连续失败 12 次）：不抛未捕获异常、不把异常当作空列表、保留现有连接池与指标
                when(instRepo.findAll()).thenThrow(new DataAccessResourceFailureException("MySQL connection refused"));
                for (int i = 1; i <= 12; i++) {
                    assertThat(reconciler.reconcileAll()).isEqualTo(-1);
                    assertThat(reconciler.consecutiveFailureCount()).isEqualTo(i);
                    assertThat(gateway.cachedInstanceIds()).containsExactly(1L);
                    assertThat(binder.registeredInstanceIds()).containsExactly(1L);
                }

                // 3. 数据库恢复且实例仍存在：失败计数归零，连接池与指标均完好
                doReturn(List.of(live)).when(instRepo).findAll();
                assertThat(reconciler.reconcileAll()).isZero();
                assertThat(reconciler.consecutiveFailureCount()).isZero();
                assertThat(gateway.cachedInstanceIds()).containsExactly(1L);
                assertThat(binder.registeredInstanceIds()).containsExactly(1L);

                // 4. 数据库正常返回空列表（全量删除语义）：允许全量清理本地连接池与指标
                doReturn(List.of()).when(instRepo).findAll();
                assertThat(reconciler.reconcileAll()).isGreaterThanOrEqualTo(2);
                assertThat(gateway.cachedInstanceIds()).isEmpty();
                assertThat(binder.registeredInstanceIds()).isEmpty();
            } finally {
                gateway.shutdown();
                liveResp.close();
                accept.join(1000);
            }
        }
    }

    @Test
    void h3ParallelMonitorCollectDoesNotBlockFastInstanceAndRespectsLeaseBudget() {
        DbInstance slowInst = new DbInstance();
        slowInst.setId(501L);
        slowInst.setName("slow-node");
        DbInstance fastInst = new DbInstance();
        fastInst.setId(502L);
        fastInst.setName("fast-node");

        InstanceService instanceService = mock(InstanceService.class);
        when(instanceService.enabled()).thenReturn(List.of(slowInst, fastInst));
        RespGateway gateway = mock(RespGateway.class);
        when(gateway.info(slowInst)).thenAnswer(inv -> {
            Thread.sleep(6_000L);
            return "# Server\r\nredis_version:6.5.0\r\n";
        });
        when(gateway.info(fastInst)).thenReturn("# Server\r\nredis_version:6.5.0\r\n# Stats\r\nqps:42\r\n");

        MetricPointRepository metricRepo = mock(MetricPointRepository.class);
        DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
        AlertService alertService = mock(AlertService.class);
        ConsoleProperties props = new ConsoleProperties();
        props.getMetrics().setIntervalMs(2000L); // leaseTtl = 4000ms -> budget = 2800ms

        MonitorService monitorService = new MonitorService(
                instanceService, gateway, metricRepo, instRepo, alertService, null, props);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        monitorService.setCollectExecutor(exec);
        try {
            long startMs = System.currentTimeMillis();
            monitorService.scheduledCollect();
            long elapsedMs = System.currentTimeMillis() - startMs;

            // 快实例不被慢实例阻塞且已落库，且整轮耗时在 70% 预算附近截断，远低于 6s 慢实例耗时
            verify(metricRepo, times(1)).save(any(MetricPoint.class));
            assertThat(elapsedMs).isLessThan(4_500L);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void h4CrossReplicaReconciliationCleansDeletedInstancesAndDetectsConfigFingerprintDrift() {
        DbInstance live = new DbInstance();
        live.setId(1L);
        live.setName("alive-inst");
        live.setHost("127.0.0.1");
        live.setPort(6379);
        live.setTlsEnabled(false);

        DbInstance updated = new DbInstance();
        updated.setId(1L);
        updated.setName("alive-inst");
        updated.setHost("127.0.0.1");
        updated.setPort(6380); // 端口变化应触发连接指纹变化
        updated.setTlsEnabled(true);
        assertThat(RespGateway.connectionFingerprint(live))
                .isNotEqualTo(RespGateway.connectionFingerprint(updated));

        DbInstance deleted = new DbInstance();
        deleted.setId(888L);
        deleted.setName("deleted-on-peer");
        deleted.setHost("127.0.0.1");
        deleted.setPort(6399);

        ConsoleProperties props = new ConsoleProperties();
        RespGateway gateway = new RespGateway(props);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ConsoleMetricsBinder binder = new ConsoleMetricsBinder(registry, gateway, eventRepository);
        MetricPoint pt = new MetricPoint();
        pt.setUsedMemory(1024L);
        pt.setMaxMemory(4096L);
        binder.recordInstanceUp(live, pt, 1L, 0.5d);
        binder.recordInstanceUp(deleted, pt, 2L, 1.0d);
        assertThat(binder.registeredInstanceIds()).containsExactlyInAnyOrder(1L, 888L);

        DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
        when(instRepo.findAll()).thenReturn(List.of(live)); // 数据库中已无 888

        AlertStateStore store = new AlertStateStore(stateRepository, transactionManager, mutableClock);
        store.recordOrGetSatisfiedSince(99L, 888L, LocalDateTime.now(mutableClock));
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(99L, 888L))).isTrue();

        InstanceReconcileService reconciler = new InstanceReconcileService(
                instRepo, gateway, binder, null, null, store);
        int cleaned = reconciler.reconcileAll();
        assertThat(cleaned).isGreaterThanOrEqualTo(1);
        assertThat(binder.registeredInstanceIds()).containsExactly(1L);

        // 本地删除事件立即清理该实例对应的持久化告警状态
        reconciler.onInstanceDeleted(new InstanceDeletedEvent(888L));
        assertThat(stateRepository.existsById(AlertStateStore.evalKey(99L, 888L))).isFalse();
        assertThat(gateway.evictIfStale(888L, null)).isFalse();
        assertThat(Set.copyOf(binder.registeredInstanceIds())).doesNotContain(888L);
    }

    private static int raceClaims(ExecutorService pool, int threads,
                                  java.util.concurrent.Callable<Boolean> claimTask) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(3, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(claimTask.call())) {
                    winners.incrementAndGet();
                }
                return null;
            }));
        }
        assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        return winners.get();
    }
}
