package io.tieringkv.webconsole.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tieringkv.webconsole.domain.AlertEvalState;
import io.tieringkv.webconsole.repository.AlertEvalStateRepository;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 2A（H2）真实 MySQL 8.0 并发与 Flyway V13 专项集成测试。
 *
 * <p>当通过系统属性 {@code -Dwave2a.mysql.url=jdbc:mysql://...} 提供真实 MySQL 8.0 实例时执行，
 * 端到端验证：
 * <ol>
 *   <li>Flyway V1..V13 真实建表（含 {@code DATETIME(3)} 与索引）；</li>
 *   <li>{@code DATETIME(3)} 精度往返零偏差；</li>
 *   <li>MySQL 8 默认 {@code REPEATABLE READ} 下 12 独立连接并发争抢首报、静默续窗与 RESOLVED 去重；</li>
 *   <li>真实 InnoDB 锁等待超时（MySQL Error 1205）绝不误判为 DB 断连且不触发本地重发；</li>
 *   <li>MySQL 8 {@code LIKE} 清理边界（ruleId=1 绝不误删 ruleId=10/11 或 channelId=1）。</li>
 * </ol>
 */
class Wave2AMysql8ConcurrencyTest {

    @Test
    void verifiesFlywayV13RepeatableReadConcurrencyLockTimeoutAndLikeBoundariesOnRealMysql8() throws Exception {
        String url = System.getProperty("wave2a.mysql.url");
        if (url == null || url.isBlank()) {
            url = System.getenv("WAVE2A_MYSQL_URL");
        }
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Skip live MySQL 8 test when -Dwave2a.mysql.url or WAVE2A_MYSQL_URL is not set");
        String user = System.getProperty("wave2a.mysql.user",
                System.getenv("WAVE2A_MYSQL_USER") != null ? System.getenv("WAVE2A_MYSQL_USER") : "root");
        String pass = System.getProperty("wave2a.mysql.password",
                System.getenv("WAVE2A_MYSQL_PASSWORD") != null ? System.getenv("WAVE2A_MYSQL_PASSWORD") : "tk_root_pwd");

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(url);
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(20);
        hc.setMinimumIdle(4);
        hc.setConnectionTimeout(5000L);
        hc.addDataSourceProperty("useUnicode", "true");
        hc.addDataSourceProperty("characterEncoding", "utf8");
        hc.addDataSourceProperty("serverTimezone", "UTC");
        hc.addDataSourceProperty("allowPublicKeyRetrieval", "true");
        hc.addDataSourceProperty("useSSL", "false");

        try (HikariDataSource ds = new HikariDataSource(hc)) {
            // 1. 真实执行全量 Flyway V1..V13 迁移并核查表定义与隔离级别
            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .load();
            var migrationResult = flyway.migrate();
            assertThat(migrationResult.migrationsExecuted).isGreaterThanOrEqualTo(0);

            String createSql;
            String txIsolation;
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                try (ResultSet rs = st.executeQuery("SHOW CREATE TABLE alert_eval_state")) {
                    assertThat(rs.next()).isTrue();
                    createSql = rs.getString(2);
                }
                try (ResultSet rs = st.executeQuery("SELECT @@transaction_isolation")) {
                    assertThat(rs.next()).isTrue();
                    txIsolation = rs.getString(1);
                }
                st.executeUpdate("DELETE FROM alert_eval_state");
            }
            assertThat(createSql.toLowerCase())
                    .contains("`state_key` varchar(128) not null")
                    .contains("`satisfied_since` datetime(3)")
                    .contains("`last_notified_at` datetime(3)")
                    .contains("`updated_at` datetime(3) not null")
                    .contains("primary key (`state_key`)")
                    .contains("idx_alert_eval_state_updated");
            assertThat(txIsolation).isEqualToIgnoringCase("REPEATABLE-READ");

            // 2. 启动真实 Hibernate JPA + Spring Data Repository + PlatformTransactionManager 连接 MySQL 8
            LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
            emfBean.setDataSource(ds);
            emfBean.setPackagesToScan("io.tieringkv.webconsole.domain");
            emfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties jpaProps = new Properties();
            jpaProps.put("hibernate.hbm2ddl.auto", "none");
            jpaProps.put("hibernate.jdbc.time_zone", "UTC");
            emfBean.setJpaProperties(jpaProps);
            emfBean.afterPropertiesSet();
            EntityManagerFactory emf = emfBean.getObject();
            assertThat(emf).isNotNull();

            try {
                PlatformTransactionManager txManager = new JpaTransactionManager(emf);
                var emProxy = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf);
                JpaRepositoryFactory repoFactory = new JpaRepositoryFactory(emProxy);
                AlertEvalStateRepository repo = repoFactory.getRepository(AlertEvalStateRepository.class);

                AtomicReference<Instant> clockRef = new AtomicReference<>(Instant.parse("2026-11-27T10:00:00.789Z"));
                Clock clock = new Clock() {
                    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
                    @Override public Clock withZone(ZoneId zone) { return this; }
                    @Override public Instant instant() { return clockRef.get(); }
                };

                AlertStateStore store = new AlertStateStore(repo, txManager, clock);

                // 3. DATETIME(3) 毫秒精度往返零偏差验证
                LocalDateTime preciseT0 = LocalDateTime.ofInstant(clockRef.get(), ZoneId.of("UTC"))
                        .truncatedTo(ChronoUnit.MILLIS);
                LocalDateTime recorded = store.recordOrGetSatisfiedSince(100L, 1L, preciseT0);
                assertThat(recorded).isEqualTo(preciseT0);
                LocalDateTime reloaded = store.recordOrGetSatisfiedSince(100L, 1L, preciseT0.plusSeconds(10));
                assertThat(reloaded).isEqualTo(preciseT0);

                // 4. 12 独立连接并发抢占：首报 / 静默内 / 静默期满 CAS 续窗 / RESOLVED 去重
                int threads = 12;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    int firstWinners = race(pool, threads, () ->
                            new AlertStateStore(repo, txManager, clock)
                                    .tryClaimWebhookDelivery(7L, 11L, 1L, 5001L, 300, false));
                    assertThat(firstWinners).isEqualTo(1);

                    // 距离期满还差 1ms（299.999s）：DATETIME(3) 边界严守静默窗，0 个胜出
                    clockRef.set(clockRef.get().plusMillis(299_999L));
                    assertThat(store.tryClaimWebhookDelivery(7L, 11L, 1L, 5002L, 300, false)).isFalse();

                    // 刚好满 300.000s：12 连接并发 CAS 更新，严格仅 1 个胜出
                    clockRef.set(clockRef.get().plusMillis(1L));
                    int renewWinners = race(pool, threads, () ->
                            new AlertStateStore(repo, txManager, clock)
                                    .tryClaimWebhookDelivery(7L, 11L, 1L, 5003L, 300, false));
                    assertThat(renewWinners).isEqualTo(1);

                    // RESOLVED 12 连接并发：严格仅 1 个胜出且清除静默键
                    int resolvedWinners = race(pool, threads, () ->
                            new AlertStateStore(repo, txManager, clock)
                                    .tryClaimWebhookDelivery(7L, 11L, 1L, 5003L, 300, true));
                    assertThat(resolvedWinners).isEqualTo(1);
                    assertThat(repo.existsById(AlertStateStore.notifyKey(7L, 11L, 1L))).isFalse();
                } finally {
                    pool.shutdownNow();
                }

                // 5. 真实 MySQL 8 四类故障异常实测（拒绝按类名猜测，真实捕获 MySQL Error 1062 / 1205 / 1213 / 08S01）
                // 5a. 真实主键冲突（Error 1062 / SQLState 23000）
                Throwable dupErr = null;
                TransactionTemplate tx = new TransactionTemplate(txManager);
                try {
                    tx.executeWithoutResult(s -> {
                        repo.insertRaw("dup:probe:1", null, preciseT0, preciseT0);
                        repo.insertRaw("dup:probe:1", null, preciseT0, preciseT0);
                    });
                } catch (RuntimeException ex) {
                    dupErr = ex;
                }
                assertThat(dupErr).isNotNull();
                SQLException dupSql = findSqlException(dupErr);
                assertThat((Throwable) dupSql).isNotNull();
                assertThat(dupSql.getErrorCode()).isEqualTo(1062);
                assertThat(AlertStateStore.isContentionOrLockFailure(dupErr)).isTrue();
                assertThat(AlertStateStore.isDatabaseUnavailable(dupErr)).isFalse();
                System.out.println("REAL_MYSQL8_ERR_1062: wrapper=" + dupErr.getClass().getSimpleName()
                        + " sqlEx=" + dupSql.getClass().getSimpleName()
                        + " code=" + dupSql.getErrorCode() + " state=" + dupSql.getSQLState());

                // 5b. 真实 MySQL 8 InnoDB 锁等待超时（Error 1205）：持排他锁不提交，对端等待超时必须返回 false（绝不降级重发）
                clockRef.set(clockRef.get().plusSeconds(5));
                assertThat(store.tryClaimWebhookDelivery(8L, 22L, 1L, 6001L, 10, false)).isTrue();
                clockRef.set(clockRef.get().plusSeconds(15)); // 静默期已满，正常会尝试 UPDATE
                try (Connection blocker = ds.getConnection()) {
                    blocker.setAutoCommit(false);
                    try (PreparedStatement ps = blocker.prepareStatement(
                            "SELECT * FROM alert_eval_state WHERE state_key = ? FOR UPDATE")) {
                        ps.setString(1, AlertStateStore.notifyKey(8L, 22L, 1L));
                        try (ResultSet rs = ps.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                        }
                    }
                    // 在单独连接把 session lock wait timeout 设为 1s 验证真实 1205 映射
                    try (Connection victim = ds.getConnection(); Statement st = victim.createStatement()) {
                        st.execute("SET SESSION innodb_lock_wait_timeout = 1");
                        Throwable lockErr = null;
                        try (PreparedStatement ps = victim.prepareStatement(
                                "UPDATE alert_eval_state SET last_notified_at = NOW(3) WHERE state_key = ?")) {
                            ps.setString(1, AlertStateStore.notifyKey(8L, 22L, 1L));
                            ps.executeUpdate();
                        } catch (Exception ex) {
                            lockErr = ex;
                        }
                        assertThat(lockErr).isNotNull();
                        SQLException lockSql = findSqlException(lockErr);
                        assertThat((Throwable) lockSql).isNotNull();
                        assertThat(lockSql.getErrorCode()).isEqualTo(1205);
                        assertThat(AlertStateStore.isContentionOrLockFailure(lockErr))
                                .as("MySQL 8 Error 1205 lock wait timeout must be classified as contention: %s", lockErr)
                                .isTrue();
                        assertThat(AlertStateStore.isDatabaseUnavailable(lockErr)).isFalse();
                        System.out.println("REAL_MYSQL8_ERR_1205: sqlEx=" + lockSql.getClass().getSimpleName()
                                + " code=" + lockSql.getErrorCode() + " state=" + lockSql.getSQLState());
                    }
                    // 同时通过 AlertStateStore 实际连接触发锁超时（将全局 lock_wait_timeout 临时缩至 1s 后还原）
                    try (Connection cfg = ds.getConnection(); Statement st = cfg.createStatement()) {
                        st.execute("SET GLOBAL innodb_lock_wait_timeout = 1");
                    }
                    // 新开短池使会话继承 global innodb_lock_wait_timeout=1，真实走完整 AlertStateStore 路径
                    HikariConfig shortHc = new HikariConfig();
                    shortHc.setJdbcUrl(url);
                    shortHc.setUsername(user);
                    shortHc.setPassword(pass);
                    shortHc.setMaximumPoolSize(2);
                    shortHc.addDataSourceProperty("serverTimezone", "UTC");
                    shortHc.addDataSourceProperty("allowPublicKeyRetrieval", "true");
                    shortHc.addDataSourceProperty("useSSL", "false");
                    try (HikariDataSource shortDs = new HikariDataSource(shortHc)) {
                        LocalContainerEntityManagerFactoryBean shortEmfBean = new LocalContainerEntityManagerFactoryBean();
                        shortEmfBean.setDataSource(shortDs);
                        shortEmfBean.setPackagesToScan("io.tieringkv.webconsole.domain");
                        shortEmfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                        shortEmfBean.setJpaProperties(jpaProps);
                        shortEmfBean.afterPropertiesSet();
                        EntityManagerFactory shortEmf = shortEmfBean.getObject();
                        try {
                            PlatformTransactionManager shortTx = new JpaTransactionManager(shortEmf);
                            var shortEm = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(shortEmf);
                            AlertEvalStateRepository shortRepo = new JpaRepositoryFactory(shortEm)
                                    .getRepository(AlertEvalStateRepository.class);
                            AlertStateStore lockTimeoutStore = new AlertStateStore(shortRepo, shortTx, clock);
                            boolean claimedUnderLockTimeout = lockTimeoutStore.tryClaimWebhookDelivery(
                                    8L, 22L, 1L, 6002L, 10, false);
                            assertThat(claimedUnderLockTimeout)
                                    .as("Under real MySQL 8 Error 1205 lock wait timeout, store must fail closed (false) and never fallback-send")
                                    .isFalse();
                        } finally {
                            shortEmfBean.destroy();
                        }
                    } finally {
                        blocker.rollback();
                        try (Connection cfg = ds.getConnection(); Statement st = cfg.createStatement()) {
                            st.execute("SET GLOBAL innodb_lock_wait_timeout = 50");
                        }
                    }
                }

                // 5c. 真实 MySQL 8 InnoDB 死锁（Error 1213 / SQLState 40001）：双连接交叉加行锁触发真实 InnoDB 死锁检测
                tx.executeWithoutResult(s -> {
                    repo.insertRaw("dl:a", null, preciseT0, preciseT0);
                    repo.insertRaw("dl:b", null, preciseT0, preciseT0);
                });
                AtomicReference<SQLException> realDeadlock = new AtomicReference<>();
                CountDownLatch lockedFirst = new CountDownLatch(2);
                ExecutorService dlPool = Executors.newFixedThreadPool(2);
                try {
                    Future<?> fA = dlPool.submit(() -> runCrossLock(ds, "dl:a", "dl:b", lockedFirst, realDeadlock));
                    Future<?> fB = dlPool.submit(() -> runCrossLock(ds, "dl:b", "dl:a", lockedFirst, realDeadlock));
                    fA.get(10, TimeUnit.SECONDS);
                    fB.get(10, TimeUnit.SECONDS);
                } finally {
                    dlPool.shutdownNow();
                }
                SQLException dlSql = realDeadlock.get();
                assertThat((Throwable) dlSql).as("Real MySQL 8 InnoDB deadlock (1213) must occur").isNotNull();
                assertThat(dlSql.getErrorCode()).isEqualTo(1213);
                assertThat(AlertStateStore.isContentionOrLockFailure(dlSql)).isTrue();
                assertThat(AlertStateStore.isDatabaseUnavailable(dlSql)).isFalse();
                // 使用 Spring 真实异常转换器包装真实 MySQL 1213 异常并验证 AlertStateStore 绝不降级重发
                var translated1213 = new SQLExceptionSubclassTranslator().translate("UPDATE", null, dlSql);
                assertThat(translated1213).isNotNull();
                AlertEvalStateRepository dlMockRepo = org.mockito.Mockito.mock(AlertEvalStateRepository.class, inv -> {
                    throw translated1213;
                });
                AlertStateStore dlStore = new AlertStateStore(dlMockRepo, txManager, clock);
                assertThat(dlStore.tryClaimWebhookDelivery(9L, 33L, 1L, 8001L, 60, false)).isFalse();
                System.out.println("REAL_MYSQL8_ERR_1213: sqlEx=" + dlSql.getClass().getSimpleName()
                        + " springEx=" + translated1213.getClass().getSimpleName()
                        + " code=" + dlSql.getErrorCode() + " state=" + dlSql.getSQLState());

                // 5d. 真实 MySQL JDBC 连接故障（不可达端口 / 08S01）：确认准确归类为 DB 不可用并启用 >=30s 保守本地降级
                int deadPort;
                try (ServerSocket tmp = new ServerSocket(0)) {
                    deadPort = tmp.getLocalPort();
                }
                HikariConfig brokenHc = new HikariConfig();
                brokenHc.setJdbcUrl("jdbc:mysql://127.0.0.1:" + deadPort
                        + "/tiering_console?connectTimeout=300&socketTimeout=300&useSSL=false");
                brokenHc.setUsername(user);
                brokenHc.setPassword(pass);
                brokenHc.setConnectionTimeout(600L);
                brokenHc.setInitializationFailTimeout(-1L); // 允许初始化后在首次请求时暴露真实断连异常
                try (HikariDataSource brokenDs = new HikariDataSource(brokenHc)) {
                    Throwable connErr = null;
                    try (Connection ignored = brokenDs.getConnection()) {
                        // unreachable
                    } catch (Exception ex) {
                        connErr = ex;
                    }
                    assertThat(connErr).isNotNull();
                    assertThat(AlertStateStore.isDatabaseUnavailable(connErr))
                            .as("Real MySQL connection outage must be classified as DB unavailable: %s", connErr)
                            .isTrue();
                    assertThat(AlertStateStore.isContentionOrLockFailure(connErr)).isFalse();
                    System.out.println("REAL_MYSQL8_CONN_OUTAGE: ex=" + connErr.getClass().getSimpleName()
                            + " cause=" + (connErr.getCause() == null ? "none" : connErr.getCause().getClass().getSimpleName()));

                    // 通过真实断开的 JpaTransactionManager 走完整 AlertStateStore 路径：确认启用 >=30s 保守本地降级
                    LocalContainerEntityManagerFactoryBean brokenEmfBean = new LocalContainerEntityManagerFactoryBean();
                    brokenEmfBean.setDataSource(brokenDs);
                    brokenEmfBean.setPackagesToScan("io.tieringkv.webconsole.domain");
                    brokenEmfBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                    Properties brokenProps = new Properties();
                    brokenProps.put("hibernate.hbm2ddl.auto", "none");
                    brokenProps.put("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
                    brokenEmfBean.setJpaProperties(brokenProps);
                    brokenEmfBean.afterPropertiesSet();
                    EntityManagerFactory brokenEmf = brokenEmfBean.getObject();
                    try {
                        PlatformTransactionManager brokenTx = new JpaTransactionManager(brokenEmf);
                        var brokenEm = org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(brokenEmf);
                        AlertEvalStateRepository brokenRepo = new JpaRepositoryFactory(brokenEm)
                                .getRepository(AlertEvalStateRepository.class);
                        AlertStateStore outageStore = new AlertStateStore(brokenRepo, brokenTx, clock);
                        // 规则仅配 5s 静默，DB 断连时自动提升至 >=30s 保守静默防止告警风暴
                        assertThat(outageStore.tryClaimWebhookDelivery(9L, 44L, 1L, 9001L, 5, false)).isTrue();
                        clockRef.set(clockRef.get().plusSeconds(10));
                        assertThat(outageStore.tryClaimWebhookDelivery(9L, 44L, 1L, 9002L, 5, false)).isFalse();
                        clockRef.set(clockRef.get().plusSeconds(25)); // 累计 35s >= 30s
                        assertThat(outageStore.tryClaimWebhookDelivery(9L, 44L, 1L, 9003L, 5, false)).isTrue();
                    } finally {
                        brokenEmfBean.destroy();
                    }
                }

                // 6. MySQL 8 真实 LIKE 边界验证：相邻规则 ID 1 / 10 / 11、渠道 ID 1 / 10、实例 ID 1 / 10 严格隔离
                tx.executeWithoutResult(s -> repo.deleteAll());
                LocalDateTime t = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
                store.recordOrGetSatisfiedSince(1L, 1L, t);
                store.recordOrGetSatisfiedSince(1L, 10L, t);
                store.recordOrGetSatisfiedSince(10L, 1L, t);
                store.recordOrGetSatisfiedSince(11L, 1L, t);
                store.tryClaimWebhookDelivery(1L, 10L, 2L, 7001L, 60, false); // channel=1, rule=10, inst=2
                store.tryClaimWebhookDelivery(10L, 1L, 2L, 7002L, 60, false); // channel=10, rule=1, inst=2
                store.tryClaimWebhookDelivery(10L, 11L, 2L, 7003L, 60, false); // channel=10, rule=11, inst=2
                store.tryClaimWebhookDelivery(2L, 1L, 10L, 7004L, 60, false); // channel=2, rule=1, inst=10
                store.tryClaimWebhookDelivery(2L, 10L, 1L, 7005L, 60, false); // channel=2, rule=10, inst=1

                store.clearForRule(1L);
                assertThat(repo.existsById("eval:1:1")).isFalse();
                assertThat(repo.existsById("eval:1:10")).isFalse();
                assertThat(repo.existsById("notify:10:1:2")).isFalse();
                assertThat(repo.existsById("notify:2:1:10")).isFalse();
                // 绝不误删 ruleId=10 / 11，也绝不误删 channelId=1 + ruleId=10 或 instId=1 + ruleId=10
                assertThat(repo.existsById("eval:10:1")).isTrue();
                assertThat(repo.existsById("eval:11:1")).isTrue();
                assertThat(repo.existsById("notify:1:10:2")).isTrue();
                assertThat(repo.existsById("notify:10:11:2")).isTrue();
                assertThat(repo.existsById("notify:2:10:1")).isTrue();

                // clearForChannel(1L) 绝不误删 channelId=10 或 ruleId/instanceId=1
                store.clearForChannel(1L);
                assertThat(repo.existsById("notify:1:10:2")).isFalse();
                assertThat(repo.existsById("notify:10:11:2")).isTrue();
                assertThat(repo.existsById("notify:2:10:1")).isTrue();

                // clearForInstance(1L) 绝不误删 instanceId=10 或 2
                store.recordOrGetSatisfiedSince(10L, 10L, t);
                store.clearForInstance(1L);
                assertThat(repo.existsById("eval:10:1")).isFalse();
                assertThat(repo.existsById("eval:11:1")).isFalse();
                assertThat(repo.existsById("notify:2:10:1")).isFalse();
                assertThat(repo.existsById("eval:10:10")).isTrue();
                assertThat(repo.existsById("notify:10:11:2")).isTrue();

                System.out.println("REAL_MYSQL8_WAVE2A_VERIFIED: isolation=" + txIsolation
                        + " datetime3_exact=true err_1062=true err_1205=true err_1213=true"
                        + " conn_outage_fallback=true like_boundaries_exact=true");
            } finally {
                emfBean.destroy();
            }
        }
    }

    private static void runCrossLock(HikariDataSource ds, String firstKey, String secondKey,
                                     CountDownLatch lockedFirst, AtomicReference<SQLException> deadlockRef) {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM alert_eval_state WHERE state_key = ? FOR UPDATE")) {
                    ps.setString(1, firstKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                lockedFirst.countDown();
                lockedFirst.await(5, TimeUnit.SECONDS);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE alert_eval_state SET updated_at = NOW(3) WHERE state_key = ?")) {
                    ps.setString(1, secondKey);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                if (ex.getErrorCode() == 1213) {
                    deadlockRef.compareAndSet(null, ex);
                }
                try { conn.rollback(); } catch (SQLException ignored) { }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } catch (SQLException ignored) {
        }
    }

    private static SQLException findSqlException(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SQLException sqlEx) {
                return sqlEx;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static int race(ExecutorService pool, int threads,
                            java.util.concurrent.Callable<Boolean> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(task.call())) {
                    winners.incrementAndGet();
                }
                return null;
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        return winners.get();
    }
}
