package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespClientPool;
import io.tieringkv.webconsole.resp.RespReply;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按实例维度的 RESP 访问网关（连接池 + 超时 + 口令）。 */
@Service
public class RespGateway {

    private final ConsoleProperties properties;
    private final Map<Long, RespClientPool> pools = new ConcurrentHashMap<>();

    public RespGateway(ConsoleProperties properties) {
        this.properties = properties;
    }

    @FunctionalInterface
    private interface ClientAction {
        RespReply run(RespClient client) throws IOException;
    }

    public RespReply execute(DbInstance instance, String... args) throws IOException {
        return withClient(instance, client -> client.execute(args));
    }

    public RespReply execute(DbInstance instance, List<byte[]> args) throws IOException {
        return withClient(instance, client -> client.execute(args));
    }

    private RespReply withClient(DbInstance instance, ClientAction action) throws IOException {
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
                client.close();
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
        RespClientPool pool = pools.remove(instanceId);
        if (pool != null) {
            pool.close();
        }
    }

    private RespClientPool pool(DbInstance instance) {
        return pools.computeIfAbsent(instance.getId(), key -> new RespClientPool(
                () -> open(instance), 4));
    }

    private RespClient open(DbInstance instance) {
        RespClient client = null;
        try {
            client = RespClient.open(instance.getHost(), instance.getPort(),
                    properties.getMetrics().getConnectTimeoutMs(),
                    properties.getMetrics().getReadTimeoutMs());
            String password = instance.getPassword();
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
        pools.values().forEach(RespClientPool::close);
        pools.clear();
    }
}
