package io.tieringkv.webconsole.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 多副本冷启动 Flyway 迁移串行化护栏（v6.2.0）：
 * 当多个控制面副本同时冷启动连接空 MySQL 库时，通过 MySQL 会话级咨询锁
 * {@code GET_LOCK('tiering_console_flyway_migrate', 60)} 串行化建表与迁移，
 * 彻底消除 {@code flyway_schema_history already exists, and is empty} 竞态异常。
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);
    static final String ADVISORY_LOCK_NAME = "tiering_console_flyway_migrate";
    static final int ADVISORY_LOCK_TIMEOUT_SECONDS = 60;

    @Bean
    public FlywayMigrationStrategy serializedFlywayMigrationStrategy() {
        return FlywayConfig::migrateWithAdvisoryLock;
    }

    static void migrateWithAdvisoryLock(Flyway flyway) {
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        if (dataSource == null) {
            flyway.migrate();
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta == null || meta.getDatabaseProductName() == null
                    ? "" : meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            if (!product.contains("mysql") && !product.contains("mariadb")) {
                flyway.migrate();
                return;
            }
            if (!acquireMysqlLock(conn)) {
                throw new IllegalStateException(
                        "等待 MySQL Flyway 迁移咨询锁超时（" + ADVISORY_LOCK_TIMEOUT_SECONDS + "s）");
            }
            try {
                log.info("已获取 Flyway 迁移咨询锁 {}，开始执行数据库迁移", ADVISORY_LOCK_NAME);
                flyway.migrate();
            } finally {
                releaseMysqlLock(conn);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("获取 Flyway 迁移咨询锁失败：" + ex.getMessage(), ex);
        }
    }

    private static boolean acquireMysqlLock(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, ADVISORY_LOCK_NAME);
            ps.setInt(2, ADVISORY_LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        }
    }

    private static void releaseMysqlLock(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, ADVISORY_LOCK_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
        } catch (SQLException ex) {
            log.warn("释放 Flyway 迁移咨询锁 {} 失败", ADVISORY_LOCK_NAME, ex);
        }
    }
}
