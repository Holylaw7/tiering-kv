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

        public String getInstance() {
            return instance;
        }

        public void setInstance(String instance) {
            this.instance = instance;
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

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
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
