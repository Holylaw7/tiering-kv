package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpec;
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Kubernetes {@code coordination.k8s.io/v1 Lease} 的轻量选主器（v5.4.0 A2）。
 *
 * <p>利用 K8s 原生 {@code resourceVersion} 乐观锁实现多副本 Operator 互斥选主、
 * 续租、宕机到期自动接管与优雅退主释放。</p>
 */
public final class OperatorLeaderElector {

    private final KubernetesClient client;
    private final String namespace;
    private final String leaseName;
    private final String identity;
    private final int leaseDurationSeconds;
    private final Clock clock;
    private final AtomicBoolean leader = new AtomicBoolean(false);

    public OperatorLeaderElector(KubernetesClient client,
                                 String namespace,
                                 String leaseName,
                                 String identity,
                                 int leaseDurationSeconds) {
        this(client, namespace, leaseName, identity, leaseDurationSeconds,
                Clock.systemUTC());
    }

    public OperatorLeaderElector(KubernetesClient client,
                                 String namespace,
                                 String leaseName,
                                 String identity,
                                 int leaseDurationSeconds,
                                 Clock clock) {
        if (client == null) {
            throw new IllegalArgumentException("client required");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace required");
        }
        if (leaseName == null || leaseName.isBlank()) {
            throw new IllegalArgumentException("leaseName required");
        }
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("identity required");
        }
        this.client = client;
        this.namespace = namespace.trim();
        this.leaseName = leaseName.trim();
        this.identity = identity.trim();
        this.leaseDurationSeconds = Math.max(5, leaseDurationSeconds);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public boolean isLeader() {
        return leader.get();
    }

    public String identity() {
        return identity;
    }

    public String leaseName() {
        return leaseName;
    }

    public String namespace() {
        return namespace;
    }

    public int leaseDurationSeconds() {
        return leaseDurationSeconds;
    }

    /** 尝试获取或续租 Lease；返回本次调用后当前实例是否持有主控权。 */
    public boolean tryAcquireOrRenew() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC);
        try {
            Lease existing = client.leases().inNamespace(namespace)
                    .withName(leaseName).get();
            if (existing == null) {
                Lease created = new LeaseBuilder()
                        .withMetadata(new ObjectMetaBuilder()
                                .withName(leaseName)
                                .withNamespace(namespace)
                                .build())
                        .withSpec(new LeaseSpecBuilder()
                                .withHolderIdentity(identity)
                                .withLeaseDurationSeconds(leaseDurationSeconds)
                                .withAcquireTime(now)
                                .withRenewTime(now)
                                .withLeaseTransitions(0)
                                .build())
                        .build();
                client.leases().inNamespace(namespace).resource(created).create();
                leader.set(true);
                return true;
            }

            LeaseSpec spec = existing.getSpec();
            String currentHolder = spec == null ? null : spec.getHolderIdentity();
            if (identity.equals(currentHolder)) {
                spec.setRenewTime(now);
                spec.setLeaseDurationSeconds(leaseDurationSeconds);
                client.leases().inNamespace(namespace).resource(existing).replace();
                leader.set(true);
                return true;
            }

            if (isExpired(spec, now)) {
                int transitions = spec != null && spec.getLeaseTransitions() != null
                        ? spec.getLeaseTransitions() + 1 : 1;
                LeaseSpec nextSpec = new LeaseSpecBuilder()
                        .withHolderIdentity(identity)
                        .withLeaseDurationSeconds(leaseDurationSeconds)
                        .withAcquireTime(now)
                        .withRenewTime(now)
                        .withLeaseTransitions(transitions)
                        .build();
                existing.setSpec(nextSpec);
                client.leases().inNamespace(namespace).resource(existing).replace();
                leader.set(true);
                return true;
            }

            leader.set(false);
            return false;
        } catch (KubernetesClientException ex) {
            // 409 Conflict 表示并发竞争未胜出；其他瞬时错误本轮保守放弃主控权
            leader.set(false);
            return false;
        }
    }

    /** 优雅退出时主动释放主控权，允许备副本立即接管无需等待租约自然到期。 */
    public void release() {
        if (!leader.compareAndSet(true, false)) {
            return;
        }
        try {
            Lease existing = client.leases().inNamespace(namespace)
                    .withName(leaseName).get();
            if (existing == null || existing.getSpec() == null
                    || !identity.equals(existing.getSpec().getHolderIdentity())) {
                return;
            }
            ZonedDateTime expired = ZonedDateTime.now(clock)
                    .withZoneSameInstant(ZoneOffset.UTC)
                    .minusSeconds(leaseDurationSeconds + 1L);
            existing.getSpec().setHolderIdentity("");
            existing.getSpec().setRenewTime(expired);
            client.leases().inNamespace(namespace).resource(existing).replace();
        } catch (RuntimeException ignored) {
            // 退出路径尽力释放即可，租约过期会兜底
        }
    }

    private boolean isExpired(LeaseSpec spec, ZonedDateTime now) {
        if (spec == null) {
            return true;
        }
        String holder = spec.getHolderIdentity();
        if (holder == null || holder.isBlank()) {
            return true;
        }
        ZonedDateTime renew = spec.getRenewTime();
        if (renew == null) {
            return true;
        }
        int duration = spec.getLeaseDurationSeconds() != null
                && spec.getLeaseDurationSeconds() > 0
                ? spec.getLeaseDurationSeconds()
                : leaseDurationSeconds;
        return !renew.plusSeconds(duration).isAfter(now);
    }
}
