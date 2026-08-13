package io.tieringkv.operations;

import io.tieringkv.operations.ProductCompletenessBaseline
        .Capability;
import io.tieringkv.operations.ProductCompletenessBaseline
        .DebtDisposition;
import io.tieringkv.operations.ProductCompletenessBaseline
        .TechDebt;
import io.tieringkv.operations.ProductCompletenessBaseline.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 产品完成度基线（ADR-0268）：能力分层 + 技术债终态 + 判定清单。 */
class ProductCompletenessBaselineTest {

    @Test
    void capabilityMatrixNonEmpty() {
        assertThat(ProductCompletenessBaseline.capabilities())
                .isNotEmpty();
    }

    @Test
    void coreStorageMarkedProduct() {
        assertThat(ProductCompletenessBaseline.capabilities().stream()
                .filter(capability -> capability.tier()
                        == Tier.PRODUCT)
                .map(Capability::name))
                .contains("LSM cold/hot tiering",
                        "WAL persistence and recovery",
                        "Multi-Raft replication",
                        "MVCC + Percolator 2PC");
    }

    @Test
    void prototypeLayersMarkedExperimental() {
        assertThat(ProductCompletenessBaseline.capabilities().stream()
                .filter(capability -> capability.tier()
                        == Tier.EXPERIMENTAL)
                .map(Capability::name))
                .contains("SQL engine", "Vector/HNSW search",
                        "Console/SaaS UI",
                        "Federated learning pushdown");
    }

    @Test
    void externalIntegrationsMarkedAdapter() {
        assertThat(ProductCompletenessBaseline.capabilities().stream()
                .filter(capability -> capability.tier()
                        == Tier.ADAPTER)
                .map(Capability::name))
                .contains("Quantum/satellite time device",
                        "S3 object storage", "Spot market data");
    }

    @Test
    void everyDebtHasTerminalDisposition() {
        assertThat(ProductCompletenessBaseline.techDebts())
                .allSatisfy(debt -> assertThat(debt.disposition())
                        .isNotNull());
    }

    @Test
    void baselinePasses() {
        assertThat(ProductCompletenessBaseline.passes()).isTrue();
    }

    @Test
    void judgmentChecklistHasSixItems() {
        assertThat(ProductCompletenessBaseline.judgmentChecklist())
                .hasSize(6);
    }

    @Test
    void noRolloverPhraseInDebts() {
        assertThat(ProductCompletenessBaseline.techDebts())
                .noneMatch(debt -> debt.note()
                        .contains("next phase"));
    }

    @ParameterizedTest(name = "capability {0}")
    @MethodSource("capabilities")
    void everyCapabilityHasTierAndStatus(Capability capability) {
        assertThat(capability.tier()).isNotNull();
        assertThat(capability.status()).isNotBlank();
        assertThat(capability.name()).isNotBlank();
    }

    @ParameterizedTest(name = "debt {0}")
    @MethodSource("debts")
    void everyDebtHasNoteAndDisposition(TechDebt debt) {
        assertThat(debt.disposition()).isNotNull();
        assertThat(debt.note()).isNotBlank();
    }

    static Stream<Capability> capabilities() {
        return ProductCompletenessBaseline.capabilities().stream();
    }

    static Stream<TechDebt> debts() {
        return ProductCompletenessBaseline.techDebts().stream();
    }
}
