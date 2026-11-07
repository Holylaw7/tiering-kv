package io.tieringkv.webconsole.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 控制台可配置项（前缀 console.*）。 */
@ConfigurationProperties(prefix = "console")
public class ConsoleProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Metrics metrics = new Metrics();
    private final Backup backup = new Backup();
    private final Terminal terminal = new Terminal();
    private final Bootstrap bootstrap = new Bootstrap();

    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Backup getBackup() {
        return backup;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    /** 启动引导：{@code name@host:port}，为空则沿用数据库中的实例。 */
    public static class Bootstrap {
        private String instance;
        /** 生产环境必须显式提供的初始管理员口令（ADR-0368）；dev/test 为空时用默认值。 */
        private String adminPassword;
        /** 初始管理员用户名，默认 admin。 */
        private String adminUsername = "admin";

        public String getInstance() {
            return instance;
        }

        public void setInstance(String instance) {
            this.instance = instance;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getAdminUsername() {
            return adminUsername;
        }

        public void setAdminUsername(String adminUsername) {
            this.adminUsername = adminUsername;
        }
    }

    public static class Jwt {
        private String secret;
        private long ttlMinutes = 720;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Metrics {
        private long intervalMs = 5000;
        private int retentionHours = 72;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 3000;

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public static class Backup {
        private String dir = "./backups";
        private Retention retention = new Retention();

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public Retention getRetention() {
            return retention;
        }

        public void setRetention(Retention retention) {
            this.retention = retention;
        }
    }

    /**
     * 备份保留配置（ADR-0367）。
     *
     * <p>只约束"保留多少个成功备份"，不保证磁盘占用，磁盘水位由容量保护单独处理。
     */
    public static class Retention {
        /** 每个实例保留的最近成功备份数量；实例可用 backup_retention 覆盖。 */
        private int defaultCount = 10;
        /** FAILED 记录保留天数。 */
        private int failedDays = 7;
        /** 认领后超过该分钟数仍未删完，允许其他实例接手。 */
        private int claimTimeoutMinutes = 30;
        /** 孤儿文件的宽限分钟数（避免误删正在写入的备份）。 */
        private int orphanGraceMinutes = 60;
        /** 定时清理间隔（毫秒），默认 1 小时。 */
        private long intervalMs = 3_600_000L;
        /** 是否启用定时清理。 */
        private boolean enabled = true;

        public int getDefaultCount() { return defaultCount; }
        public void setDefaultCount(int defaultCount) { this.defaultCount = defaultCount; }
        public int getFailedDays() { return failedDays; }
        public void setFailedDays(int failedDays) { this.failedDays = failedDays; }
        public int getClaimTimeoutMinutes() { return claimTimeoutMinutes; }
        public void setClaimTimeoutMinutes(int claimTimeoutMinutes) { this.claimTimeoutMinutes = claimTimeoutMinutes; }
        public int getOrphanGraceMinutes() { return orphanGraceMinutes; }
        public void setOrphanGraceMinutes(int orphanGraceMinutes) { this.orphanGraceMinutes = orphanGraceMinutes; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Terminal {
        private List<String> blockedCommands = new ArrayList<>(
                List.of("FLUSHALL", "FLUSHDB", "SHUTDOWN", "DEBUG"));

        public List<String> getBlockedCommands() {
            return blockedCommands;
        }

        public void setBlockedCommands(List<String> blockedCommands) {
            this.blockedCommands = blockedCommands;
        }
    }
}