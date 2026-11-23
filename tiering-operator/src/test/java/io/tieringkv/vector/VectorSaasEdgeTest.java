package io.tieringkv.vector;

import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantQuotaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vector/SaaS 边缘（ADR-0113）：检索边界与配额矩阵。 */
class VectorSaasEdgeTest {

    @ParameterizedTest(name = "dims {0}")
    @ValueSource(ints = {1, 3, 128})
    void parameterizedDimSearch(int dims) {
        VectorStore store = new VectorStore();
        float[] query = new float[dims];
        query[0] = 1;
        store.put(new Embedding("q", query));
        assertThat(store.search(query, 1)).hasSize(1);
    }

    @ParameterizedTest(name = "topK {0}")
    @ValueSource(ints = {0, 1, 5})
    void topKBoundaries(int topK) {
        VectorStore store = new VectorStore();
        store.put(new Embedding("a", new float[]{1, 0}));
        if (topK == 0) {
            assertThat(store.search(new float[]{1, 0}, 0)).isEmpty();
        } else {
            assertThat(store.search(new float[]{1, 0}, topK))
                    .hasSize(1);
        }
    }

    @Test
    void emptyEmbeddingValuesAllowed() {
        VectorStore store = new VectorStore();
        store.put(new Embedding("e", new float[0]));
        assertThat(store.search(new float[]{1}, 1)).isEmpty();
    }

    @Test
    void identicalVectorsRankedFirst() {
        VectorStore store = new VectorStore();
        float[] target = {3, 4};
        store.put(new Embedding("target", target));
        store.put(new Embedding("far", new float[]{-3, -4}));
        assertThat(store.search(target, 1).get(0).id())
                .isEqualTo("target");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {5, 50, 200})
    void parameterizedVectorVolume(int count) {
        VectorStore store = new VectorStore();
        for (int i = 0; i < count; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 5 + 1, 5 - i % 5}));
        }
        assertThat(store.search(new float[]{1, 0}, count))
                .hasSize(count);
    }

    @ParameterizedTest(name = "regions {0} storage {1}")
    @ValueSource(ints = {1, 5})
    void quotaExactBoundary(int quota) {
        ClusterTenant tenant = new ClusterTenant("t", "c", quota, quota);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThat(validator.validateAll(tenant, quota, quota)).isEmpty();
    }

    @Test
    void quotaViolationMessageContainsReason() {
        ClusterTenant tenant = new ClusterTenant("t", "c", 2, 10);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThatThrownBy(() -> validator.validate(tenant, 3, 10))
                .hasMessageContaining("regions");
        assertThatThrownBy(() -> validator.validate(tenant, 2, 11))
                .hasMessageContaining("storage");
    }

    @Test
    void multipleTenantsIndependent() {
        TenantQuotaValidator validator = new TenantQuotaValidator();
        ClusterTenant small = new ClusterTenant("a", "c1", 2, 10);
        ClusterTenant large = new ClusterTenant("b", "c2", 100, 1000);
        assertThatThrownBy(() -> validator.validate(small, 3, 5))
                .isInstanceOf(IllegalStateException.class);
        validator.validate(large, 3, 5);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {1, 100, 1000})
    void parameterizedStorageQuota(int quota) {
        ClusterTenant tenant = new ClusterTenant("t", "c", 3, quota);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        validator.validate(tenant, 1, quota);
    }

    private record QuotaCase(int regions, int storage) {
    }
}
