package io.tieringkv.webconsole.observability;

import io.tieringkv.webconsole.service.RespGateway;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * RESP 连接池健康探针（v6.4.0 D2）：在 {@code /actuator/health} 授权视图下展示池水位与超时统计。
 */
@Component("respPool")
public class RespPoolHealthIndicator implements HealthIndicator {

    private final RespGateway gateway;

    public RespPoolHealthIndicator(RespGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Health health() {
        return Health.up()
                .withDetail("pools", gateway.poolCount())
                .withDetail("activeConnections", gateway.totalActiveConnections())
                .withDetail("idleConnections", gateway.totalIdleConnections())
                .withDetail("maxConnections", gateway.totalMaxConnections())
                .withDetail("borrowTimeouts", gateway.totalBorrowTimeouts())
                .build();
    }
}
