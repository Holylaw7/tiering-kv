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

    public RespReply execute(DbInstance instance, String... args) throws IOException {
        RespClientPool pool = pool(instance);
        RespClient client = pool.borrow();
        boolean healthy = true;
        try {
            return client.execute(args);
        } catch (IOException ex) {
            healthy = false;
            throw ex;
        } finally {
            if (healthy) {
                pool.release(client);
            } else {
                client.close();
            }
        }
    }

    public RespReply execute(DbInstance instance, List<byte[]> args) throws IOException {
        RespClientPool pool = pool(instance);
        RespClient client = pool.borrow();
        boolean healthy = true;
        try {
            return client.execute(args);
        } catch (IOException ex) {
            healthy = false;
            throw ex;
        } finally {
            if (healthy) {
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
        try {
            RespClient client = RespClient.open(instance.getHost(), instance.getPort(),
                    properties.getMetrics().getConnectTimeoutMs(),
                    properties.getMetrics().getReadTimeoutMs());
            String password = instance.getPassword();
            if (password != null && !password.isBlank()) {
                RespReply reply = client.execute("AUTH", password);
                if (reply instanceof RespReply.Error error) {
                    client.close();
                    throw new IOException("AUTH 失败：" + error.message());
                }
            }
            return client;
        } catch (IOException ex) {
            throw new BusinessException(502, 50201,
                    "无法连接实例 " + instance.getHost() + ":" + instance.getPort()
                            + "（" + ex.getMessage() + "）");
        }
    }

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(RespClientPool::close);
        pools.clear();
    }
}
