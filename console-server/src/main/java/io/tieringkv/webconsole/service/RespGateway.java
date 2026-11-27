package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespClientPool;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 按实例维度的 RESP 访问网关（连接池 + 超时 + 口令 + 跨副本配置指纹自动驱逐）。 */
@Service
public class RespGateway {

    private record PooledEntry(String fingerprint, RespClientPool pool) {
    }

    private final ConsoleProperties properties;
    private final InstanceCredentialCipher cipher;
    private final Map<Long, PooledEntry> pools = new ConcurrentHashMap<>();

    public RespGateway(ConsoleProperties properties) {
        this(properties, new InstanceCredentialCipher((String) null));
    }

    @Autowired
    public RespGateway(ConsoleProperties properties, InstanceCredentialCipher cipher) {
        this.properties = properties;
        this.cipher = cipher;
    }

    @FunctionalInterface
    private interface ClientAction<T> {
        T run(RespClient client) throws IOException;
    }

    public RespReply execute(DbInstance instance, String... args) throws IOException {
        return withClient(instance, client -> client.execute(args));
    }

    public RespReply execute(DbInstance instance, List<byte[]> args) throws IOException {
        return withClient(instance, client -> client.execute(args));
    }

    /** RESP Pipeline 批量执行（O2-A），异常时销毁脏连接确保池隔离。 */
    public List<RespReply> executeBatch(DbInstance instance, List<List<byte[]>> commands) throws IOException {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        return withClient(instance, client -> client.executeBatch(commands));
    }

    private <T> T withClient(DbInstance instance, ClientAction<T> action) throws IOException {
        RespClientPool pool = pool(instance);
        RespClient client = pool.borrow();
        boolean healthy = true;
        try {
            return action.run(client);
        } catch (IOException | RuntimeException ex) {
            // 无论 IO 还是 RESP 解析异常（流可能半读），一律销毁连接避免归还脏连接
            healthy = false;
            throw ex;
        } finally {
            if (healthy && client.isUsable()) {
                pool.release(client);
            } else {
                pool.discard(client);
            }
        }
    }

    /** 探测实例：返回 INFO 文本，失败抛业务异常。 */
    public String info(DbInstance instance) {
        RespReply reply;
        try {
            reply = execute(instance, "INFO");
        } catch (IOException ex) {
            throw BusinessException.upstream("实例不可达：" + instance.getHost() + ":"
                    + instance.getPort() + "（" + ex.getMessage() + "）");
        }
        return asText(reply);
    }

    public String asText(RespReply reply) {
        return switch (reply) {
            case RespReply.Bulk bulk -> bulk.utf8();
            case RespReply.Simple simple -> simple.value();
            case RespReply.Error error -> throw BusinessException.upstream("服务端错误：" + error.message());
            case RespReply.Integer integer -> String.valueOf(integer.value());
            case RespReply.Array array -> "[]";
        };
    }

    public void evict(Long instanceId) {
        PooledEntry entry = pools.remove(instanceId);
        if (entry != null) {
            entry.pool().close();
        }
    }

    /** 返回当前本地已缓存连接池的实例 ID 集合（供 HA 周期对账使用）。 */
    public Set<Long> cachedInstanceIds() {
        return Set.copyOf(pools.keySet());
    }

    /** 若数据库中的实例已删除或连接参数（地址/口令/TLS）已变更，则驱逐本地陈旧连接池（Wave 2A H4）。 */
    public boolean evictIfStale(Long instanceId, DbInstance liveInstance) {
        if (liveInstance == null) {
            PooledEntry removed = pools.remove(instanceId);
            if (removed != null) {
                removed.pool().close();
                return true;
            }
            return false;
        }
        String expectedFp = connectionFingerprint(liveInstance);
        PooledEntry current = pools.get(instanceId);
        if (current != null && !current.fingerprint().equals(expectedFp)) {
            if (pools.remove(instanceId, current)) {
                current.pool().close();
                return true;
            }
        }
        return false;
    }

    public int poolCount() {
        return pools.size();
    }

    public int totalActiveConnections() {
        int sum = 0;
        for (PooledEntry entry : pools.values()) {
            sum += entry.pool().activeCount();
        }
        return sum;
    }

    public long totalIdleConnections() {
        long sum = 0L;
        for (PooledEntry entry : pools.values()) {
            sum += entry.pool().idleCount();
        }
        return sum;
    }

    public int totalMaxConnections() {
        if (pools.isEmpty()) {
            return properties.getPool().getMaxTotal();
        }
        int sum = 0;
        for (PooledEntry entry : pools.values()) {
            sum += entry.pool().maxTotal();
        }
        return sum;
    }

    public long totalBorrowTimeouts() {
        long sum = 0L;
        for (PooledEntry entry : pools.values()) {
            sum += entry.pool().borrowTimeoutCount();
        }
        return sum;
    }

    private RespClientPool pool(DbInstance instance) {
        String fp = connectionFingerprint(instance);
        PooledEntry existing = pools.get(instance.getId());
        if (existing != null && existing.fingerprint().equals(fp)) {
            return existing.pool();
        }
        ConsoleProperties.Pool cfg = properties.getPool();
        RespClientPool[] staleToClose = {null};
        PooledEntry active = pools.compute(instance.getId(), (key, current) -> {
            if (current != null && current.fingerprint().equals(fp)) {
                return current;
            }
            if (current != null) {
                staleToClose[0] = current.pool();
            }
            RespClientPool fresh = new RespClientPool(
                    () -> open(instance),
                    cfg.getMaxIdle(),
                    cfg.getMaxTotal(),
                    cfg.getBorrowTimeoutMs(),
                    cfg.getMaxIdleTimeMs());
            return new PooledEntry(fp, fresh);
        });
        if (staleToClose[0] != null) {
            staleToClose[0].close();
        }
        return active.pool();
    }

    /** 基于连接参数计算 SHA-256 指纹：任意副本更新实例 host/port/password/TLS 后，对端副本借用连接即刻感知重建。 */
    static String connectionFingerprint(DbInstance instance) {
        String raw = (instance.getHost() == null ? "" : instance.getHost()) + "\0"
                + instance.getPort() + "\0"
                + instance.isTlsEnabled() + "\0"
                + instance.isTlsVerifyHostname() + "\0"
                + (instance.getPassword() == null ? "" : instance.getPassword()) + "\0"
                + (instance.getTlsCaCert() == null ? "" : instance.getTlsCaCert());
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private RespClient open(DbInstance instance) {
        RespClient client = null;
        try {
            RespClient.TlsOptions tls = new RespClient.TlsOptions(
                    instance.isTlsEnabled(),
                    instance.isTlsVerifyHostname(),
                    instance.getTlsCaCert());
            client = RespClient.open(instance.getHost(), instance.getPort(),
                    properties.getMetrics().getConnectTimeoutMs(),
                    properties.getMetrics().getReadTimeoutMs(),
                    tls);
            String password = cipher.decrypt(instance.getPassword());
            if (password != null && !password.isBlank()) {
                RespReply reply = client.execute("AUTH", password);
                if (reply instanceof RespReply.Error error) {
                    String msg = error.message() == null ? "" : error.message().toLowerCase();
                    if (msg.contains("unknown command")) {
                        throw new IOException("目标内核暂不支持 AUTH 命令，请确认实例未启用控制台密码或配置匹配："
                                + error.message());
                    }
                    throw new IOException("AUTH 失败：" + error.message());
                }
            }
            return client;
        } catch (IOException ex) {
            if (client != null) {
                client.close();
            }
            throw new BusinessException(502, 50201,
                    "无法连接实例 " + instance.getHost() + ":" + instance.getPort()
                            + "（" + ex.getMessage() + "）");
        } catch (RuntimeException ex) {
            if (client != null) {
                client.close();
            }
            throw new BusinessException(502, 50201,
                    "连接实例协议异常 " + instance.getHost() + ":" + instance.getPort()
                            + "（" + ex.getMessage() + "）");
        }
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(entry -> entry.pool().close());
        pools.clear();
    }
}
