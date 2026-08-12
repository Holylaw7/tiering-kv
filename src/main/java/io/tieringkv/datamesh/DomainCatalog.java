package io.tieringkv.datamesh;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 数据网格域目录（ADR-0148）：域注册与域级 RBAC 隔离。 */
public final class DomainCatalog {

    /** 数据域：所有者 + 允许角色。 */
    public record Domain(String domainId, String owner,
                         Set<String> allowedRoles) {

        public Domain {
            if (domainId == null || domainId.isBlank()) {
                throw new IllegalArgumentException(
                        "domainId required");
            }
            if (allowedRoles == null || allowedRoles.isEmpty()) {
                throw new IllegalArgumentException(
                        "allowed roles required");
            }
            allowedRoles = Set.copyOf(allowedRoles);
        }
    }

    private final Map<String, Domain> domains =
            new ConcurrentHashMap<>();

    public void register(Domain domain) {
        if (domain == null) {
            throw new IllegalArgumentException("domain required");
        }
        if (domains.putIfAbsent(domain.domainId(), domain) != null) {
            throw new IllegalArgumentException(
                    "domain already registered: "
                            + domain.domainId());
        }
    }

    public Optional<Domain> domain(String domainId) {
        return Optional.ofNullable(domains.get(domainId));
    }

    public Domain require(String domainId) {
        Domain domain = domains.get(domainId);
        if (domain == null) {
            throw new IllegalArgumentException(
                    "unknown domain " + domainId);
        }
        return domain;
    }

    public boolean authorized(String domainId, String role) {
        Domain domain = domains.get(domainId);
        return domain != null && domain.allowedRoles().contains(role);
    }

    public List<String> domainIds() {
        return List.copyOf(domains.keySet());
    }

    public int size() {
        return domains.size();
    }
}
