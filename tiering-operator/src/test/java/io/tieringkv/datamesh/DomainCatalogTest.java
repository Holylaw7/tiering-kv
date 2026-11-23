package io.tieringkv.datamesh;

import io.tieringkv.datamesh.DomainCatalog.Domain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据网格域目录（ADR-0148）：注册 + 域级 RBAC 隔离。 */
class DomainCatalogTest {

    @Test
    void registerAndQuery() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN", "READER")));
        assertThat(catalog.domain("orders")).isPresent();
        assertThat(catalog.require("orders").owner())
                .isEqualTo("team-a");
    }

    @Test
    void duplicateRegistrationRejected() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("READER")));
        assertThatThrownBy(() -> catalog.register(
                new Domain("orders", "team-b", Set.of("ADMIN"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingDomainEmpty() {
        assertThat(new DomainCatalog().domain("missing")).isEmpty();
    }

    @Test
    void requireMissingDomainRejected() {
        assertThatThrownBy(() -> new DomainCatalog()
                .require("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void authorizedRoleAccepted() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN", "ANALYST")));
        assertThat(catalog.authorized("orders", "ANALYST")).isTrue();
        assertThat(catalog.authorized("orders", "ADMIN")).isTrue();
    }

    @Test
    void unauthorizedRoleRejected() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN")));
        assertThat(catalog.authorized("orders", "GUEST")).isFalse();
    }

    @Test
    void unknownDomainNotAuthorized() {
        assertThat(new DomainCatalog().authorized(
                "missing", "ADMIN")).isFalse();
    }

    @Test
    void blankDomainIdRejected() {
        assertThatThrownBy(() -> new Domain("", "owner",
                Set.of("ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyRolesRejected() {
        assertThatThrownBy(() -> new Domain("d", "owner",
                Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDomainRejected() {
        assertThatThrownBy(() -> new DomainCatalog()
                .register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rolesAreCopied() {
        Set<String> roles = new java.util.HashSet<>();
        roles.add("ADMIN");
        Domain domain = new Domain("d", "owner", roles);
        roles.add("GUEST");
        assertThat(domain.allowedRoles()).containsExactly("ADMIN");
    }

    @Test
    void multipleDomainsListed() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("a", "o1", Set.of("ADMIN")));
        catalog.register(new Domain("b", "o2", Set.of("ADMIN")));
        catalog.register(new Domain("c", "o3", Set.of("READER")));
        assertThat(catalog.domainIds()).containsExactlyInAnyOrder(
                "a", "b", "c");
        assertThat(catalog.size()).isEqualTo(3);
    }

    @ParameterizedTest(name = "domain {0}")
    @ValueSource(strings = {"finance", "marketing", "analytics"})
    void parameterizedDomainRegistration(String domainId) {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain(domainId, "owner",
                Set.of("ADMIN")));
        assertThat(catalog.domain(domainId)).isPresent();
    }

    @Test
    void concurrentRegistrationAndQuery() throws Exception {
        DomainCatalog catalog = new DomainCatalog();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                catalog.register(new Domain("d" + i, "owner",
                        Set.of("ADMIN")));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                catalog.domain("d" + (i % 50));
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(catalog.size()).isEqualTo(50);
    }
}
