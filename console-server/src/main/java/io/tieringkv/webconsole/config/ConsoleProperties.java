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
    private final Pool pool = new Pool();
    private final Security security = new Security();
    private final Kubernetes kubernetes = new Kubernetes();

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

    public Pool getPool() {
        return pool;
    }

    public Security getSecurity() {
        return security;
    }

    public Kubernetes getKubernetes() {
        return kubernetes;
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
        /** 存储类型：local（默认本地/共享卷），预留 s3 扩展。 */
        private String storageType = "local";
        /** 是否使用 GZIP 流式压缩导出（.jsonl.gz）；恢复自动识别压缩与明文。 */
        private boolean compress = true;
        /** 单次导出最大键数；0 表示不设硬上限，流式导出全库。 */
        private long maxKeys = 0;
        /** SCAN 每批次键数。 */
        private int scanCount = 500;
        private Retention retention = new Retention();
        private Schedule schedule = new Schedule();
        private S3 s3 = new S3();

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getStorageType() {
            return storageType;
        }

        public void setStorageType(String storageType) {
            this.storageType = storageType;
        }

        public boolean isCompress() {
            return compress;
        }

        public void setCompress(boolean compress) {
            this.compress = compress;
        }

        public long getMaxKeys() {
            return maxKeys;
        }

        public void setMaxKeys(long maxKeys) {
            this.maxKeys = maxKeys;
        }

        public int getScanCount() {
            return scanCount;
        }

        public void setScanCount(int scanCount) {
            this.scanCount = scanCount;
        }

        private Capacity capacity = new Capacity();

        public Capacity getCapacity() {
            return capacity;
        }

        public void setCapacity(Capacity capacity) {
            this.capacity = capacity;
        }

        public Retention getRetention() {
            return retention;
        }

        public void setRetention(Retention retention) {
            this.retention = retention;
        }

        public Schedule getSchedule() {
            return schedule;
        }

        public void setSchedule(Schedule schedule) {
            this.schedule = schedule;
        }

        public S3 getS3() {
            return s3;
        }

        public void setS3(S3 s3) {
            this.s3 = s3;
        }
    }

    /** S3 / MinIO 共享对象存储备份配置（N1-A）。 */
    public static class S3 {
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String prefix = "backups/";
        private String accessKey;
        private String secretKey;
        private boolean pathStyleAccess = true;
        /** 远端对象存储软配额（字节），0 表示不限额，仅校验本地暂存盘空间。 */
        private long quotaBytes = 0;
        private boolean autoCreateBucket = true;
        private String stagingDir = System.getProperty("java.io.tmpdir", "/tmp") + "/tiering-console-staging";
        private int connectTimeoutMs = 5000;
        private int socketTimeoutMs = 30000;
        private int apiCallTimeoutMs = 60000;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
        public long getQuotaBytes() { return quotaBytes; }
        public void setQuotaBytes(long quotaBytes) { this.quotaBytes = quotaBytes; }
        public boolean isAutoCreateBucket() { return autoCreateBucket; }
        public void setAutoCreateBucket(boolean autoCreateBucket) { this.autoCreateBucket = autoCreateBucket; }
        public String getStagingDir() { return stagingDir; }
        public void setStagingDir(String stagingDir) { this.stagingDir = stagingDir; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getSocketTimeoutMs() { return socketTimeoutMs; }
        public void setSocketTimeoutMs(int socketTimeoutMs) { this.socketTimeoutMs = socketTimeoutMs; }
        public int getApiCallTimeoutMs() { return apiCallTimeoutMs; }
        public void setApiCallTimeoutMs(int apiCallTimeoutMs) { this.apiCallTimeoutMs = apiCallTimeoutMs; }

        @Override
        public String toString() {
            return "S3{endpoint=" + endpoint + ", region=" + region
                    + ", bucket=" + bucket + ", prefix=" + prefix
                    + ", pathStyleAccess=" + pathStyleAccess + "}";
        }
    }

    /** 备份容量保护配置（ADR-0369）。 */
    public static class Capacity {
        /** 导出后必须保留的最小可用空间。 */
        private long minFreeBytes = 512L * 1024 * 1024;
        /** 无历史备份时的估算下限。 */
        private long estimateFloorBytes = 16L * 1024 * 1024;
        /** 有历史备份时按最近一次大小乘该系数估算。 */
        private double estimateFactor = 1.25;

        public long getMinFreeBytes() { return minFreeBytes; }
        public void setMinFreeBytes(long minFreeBytes) { this.minFreeBytes = minFreeBytes; }
        public long getEstimateFloorBytes() { return estimateFloorBytes; }
        public void setEstimateFloorBytes(long estimateFloorBytes) { this.estimateFloorBytes = estimateFloorBytes; }
        public double getEstimateFactor() { return estimateFactor; }
        public void setEstimateFactor(double estimateFactor) { this.estimateFactor = estimateFactor; }
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

    /** 定时备份策略轮询配置（v6.1.0 A2）。 */
    public static class Schedule {
        /** 是否开启后台定时备份调度器。 */
        private boolean enabled = true;
        /** 轮询到期策略的间隔（毫秒），默认 30 秒。 */
        private long pollIntervalMs = 30_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
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

    /** RESP 连接池配额配置（M3-B3）。 */
    public static class Pool {
        private int maxIdle = 4;
        private int maxTotal = 16;
        private long borrowTimeoutMs = 5000;
        private long maxIdleTimeMs = 60_000;

        public int getMaxIdle() { return maxIdle; }
        public void setMaxIdle(int maxIdle) { this.maxIdle = maxIdle; }
        public int getMaxTotal() { return maxTotal; }
        public void setMaxTotal(int maxTotal) { this.maxTotal = maxTotal; }
        public long getBorrowTimeoutMs() { return borrowTimeoutMs; }
        public void setBorrowTimeoutMs(long borrowTimeoutMs) { this.borrowTimeoutMs = borrowTimeoutMs; }
        public long getMaxIdleTimeMs() { return maxIdleTimeMs; }
        public void setMaxIdleTimeMs(long maxIdleTimeMs) { this.maxIdleTimeMs = maxIdleTimeMs; }
    }

    /** 敏感凭据加密与认证防护配置（M3-B3 + O3-A）。 */
    public static class Security {
        private String encryptionKey;
        /** 生产环境未配置主密钥时是否直接阻断启动（默认 false，输出 WARN 兼容存量部署）。 */
        private boolean requireEncryptionInProd = false;
        /** 登录防撞库：单源 IP 5 分钟窗口内允许的最大失败次数。 */
        private int ipMaxFailures = 20;
        /** 登录限流计数存储后端：memory（单机默认）| db（多副本共享 MySQL）。 */
        private String loginAttemptStore = "memory";
        /** 允许采信 X-Forwarded-For 头的受信任代理 IP 或网段（默认仅本机回环）。 */
        private List<String> trustedProxies = new ArrayList<>(
                List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"));

        public String getEncryptionKey() { return encryptionKey; }
        public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }
        public boolean isRequireEncryptionInProd() { return requireEncryptionInProd; }
        public void setRequireEncryptionInProd(boolean requireEncryptionInProd) {
            this.requireEncryptionInProd = requireEncryptionInProd;
        }
        public int getIpMaxFailures() { return ipMaxFailures; }
        public void setIpMaxFailures(int ipMaxFailures) { this.ipMaxFailures = ipMaxFailures; }
        public String getLoginAttemptStore() { return loginAttemptStore; }
        public void setLoginAttemptStore(String loginAttemptStore) { this.loginAttemptStore = loginAttemptStore; }
        public List<String> getTrustedProxies() { return trustedProxies; }
        public void setTrustedProxies(List<String> trustedProxies) { this.trustedProxies = trustedProxies; }
    }

    /** Kubernetes Operator 无依赖 REST 桥接配置（v6.2.0 C2，纯 JDK HttpClient）。 */
    public static class Kubernetes {
        private boolean enabled = false;
        private String apiServer = "https://kubernetes.default.svc";
        private String namespace = "tiering-kv";
        private String tokenPath = "/var/run/secrets/kubernetes.io/serviceaccount/token";
        private String caCertPath = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
        private String bearerToken;
        private int connectTimeoutMs = 3000;
        private int requestTimeoutMs = 8000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiServer() { return apiServer; }
        public void setApiServer(String apiServer) { this.apiServer = apiServer; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getTokenPath() { return tokenPath; }
        public void setTokenPath(String tokenPath) { this.tokenPath = tokenPath; }
        public String getCaCertPath() { return caCertPath; }
        public void setCaCertPath(String caCertPath) { this.caCertPath = caCertPath; }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
    }
}