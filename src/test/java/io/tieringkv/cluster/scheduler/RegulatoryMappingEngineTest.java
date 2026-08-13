package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.RegulatoryMappingEngine
        .MappingRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 监管法规自动映射（ADR-0252）：条款 → 事件 → 证据链。 */
class RegulatoryMappingEngineTest {

    @Test
    void registerRule() {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("GDPR", "Article-17",
                "data_erasure");
        assertThat(engine.rules()).hasSize(1);
    }

    @Test
    void mapEventMatches() {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("GDPR", "Article-17",
                "data_erasure");
        String entry = engine.mapEvent("data_erasure");
        assertThat(entry).contains("GDPR/Article-17");
        assertThat(engine.evidenceCount("data_erasure"))
                .isEqualTo(1);
    }

    @Test
    void mapEventNoMatch() {
        RegulatoryMappingEngine engine = engine();
        String entry = engine.mapEvent("unknown_event");
        assertThat(entry).endsWith("-> ");
        assertThat(engine.evidenceChain()).hasSize(1);
    }

    @Test
    void evidenceChainAppendOnly() {
        RegulatoryMappingEngine engine = engine();
        engine.mapEvent("audit");
        engine.mapEvent("audit");
        assertThat(engine.evidenceChain()).hasSize(2);
        assertThatThrownBy(() ->
                engine.evidenceChain().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evidenceCount() {
        RegulatoryMappingEngine engine = engine();
        engine.mapEvent("audit");
        engine.mapEvent("audit");
        engine.mapEvent("migration");
        assertThat(engine.evidenceCount("audit"))
                .isEqualTo(2);
        assertThat(engine.evidenceCount("migration"))
                .isEqualTo(1);
    }

    @Test
    void invalidRuleRejected() {
        RegulatoryMappingEngine engine = engine();
        assertThatThrownBy(() -> engine.registerRule(
                "", "c", "e"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.registerRule(
                "r", "", "e"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.registerRule(
                "r", "c", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidEventRejected() {
        RegulatoryMappingEngine engine = engine();
        assertThatThrownBy(() -> engine.mapEvent(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleRulesOneEvent() {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("GDPR", "A17", "delete");
        engine.registerRule("CCPA", "C1", "delete");
        String entry = engine.mapEvent("delete");
        assertThat(entry).contains("GDPR/A17");
        assertThat(entry).contains("CCPA/C1");
    }

    @Test
    void rulesExposedImmutably() {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("GDPR", "A17", "delete");
        List<MappingRule> rules = engine.rules();
        assertThatThrownBy(() -> rules.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chainWithMultipleEvents() {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("SOX", "SEC-404", "financial");
        engine.mapEvent("financial");
        engine.mapEvent("migration");
        engine.mapEvent("financial");
        assertThat(engine.evidenceChain()).hasSize(3);
        assertThat(engine.evidenceCount("financial"))
                .isEqualTo(2);
    }

    @Test
    void deterministicMapping() {
        RegulatoryMappingEngine a = engine();
        RegulatoryMappingEngine b = engine();
        a.registerRule("GDPR", "A17", "delete");
        b.registerRule("GDPR", "A17", "delete");
        assertThat(a.mapEvent("delete"))
                .isEqualTo(b.mapEvent("delete"));
    }

    @Test
    void concurrentMappingStable() throws Exception {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("GDPR", "A17", "delete");
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    engine.mapEvent("delete");
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(engine.evidenceCount("delete"))
                .isEqualTo(400);
    }

    @ParameterizedTest(name = "reg={0} clause={1} event={2} n={3}")
    @CsvSource({
            "GDPR,A17,delete,1",
            "GDPR,A17,delete,2",
            "GDPR,A17,delete,5",
            "GDPR,A17,delete,10",
            "GDPR,A32,erasure,1",
            "GDPR,A32,erasure,3",
            "CCPA,C1,optout,1",
            "CCPA,C1,optout,4",
            "CCPA,C2,delete,1",
            "SOX,S404,financial,1",
            "SOX,S404,financial,5",
            "HIPAA,H164,health,1",
            "HIPAA,H164,health,2",
            "PIPEDA,P5,consent,1",
            "PIPEDA,P5,consent,3",
            "ISO,27001,audit,1",
            "ISO,27001,audit,4",
            "PCI,DSS11,card,1",
            "PCI,DSS11,card,2",
            "NIST,800-53,access,1",
            "NIST,800-53,access,5",
            "FIPS,140-2,crypto,1",
            "FIPS,140-2,crypto,3",
            "SEC,17a-4,records,1",
            "SEC,17a-4,records,2",
            "FINRA,4511,archive,1",
            "FINRA,4511,archive,4",
            "GLBA,501,privacy,1",
            "GLBA,501,privacy,2",
            "COPPA,312,children,1",
            "COPPA,312,children,3",
            "FERPA,99,education,1",
            "FERPA,99,education,2",
            "CSF,PR.AC,identity,1",
            "CSF,PR.AC,identity,5"
    })
    void parameterizedMapping(String regulation, String clause,
                              String eventType, int events) {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule(regulation, clause, eventType);
        for (int i = 0; i < events; i++) {
            engine.mapEvent(eventType);
        }
        assertThat(engine.evidenceCount(eventType))
                .isEqualTo(events);
        assertThat(engine.evidenceChain().get(0))
                .contains(regulation + "/" + clause);
    }

    @ParameterizedTest(name = "event={0} rules={1}")
    @CsvSource({
            "delete,1,GDPR",
            "delete,2,GDPR",
            "delete,3,GDPR",
            "audit,1,ISO",
            "audit,2,ISO",
            "audit,3,ISO",
            "migration,1,SOX",
            "migration,2,SOX",
            "migration,3,SOX",
            "erasure,1,GDPR",
            "erasure,2,GDPR",
            "optout,1,CCPA",
            "optout,2,CCPA",
            "financial,1,SOX",
            "financial,2,SOX",
            "health,1,HIPAA",
            "health,2,HIPAA",
            "access,1,NIST",
            "access,2,NIST",
            "archive,1,FINRA"
    })
    void parameterizedRuleSets(String eventType, int ruleCount,
                               String regulation) {
        RegulatoryMappingEngine engine = engine();
        for (int i = 0; i < ruleCount; i++) {
            engine.registerRule(regulation,
                    "clause-" + i, eventType);
        }
        String entry = engine.mapEvent(eventType);
        assertThat(entry).contains(regulation);
        assertThat(engine.evidenceCount(eventType))
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10, 20, 50, 100,
            200, 500, 1000, 2000, 5000})
    void parameterizedChainSizes(int events) {
        RegulatoryMappingEngine engine = engine();
        engine.registerRule("GDPR", "A17", "delete");
        for (int i = 0; i < events; i++) {
            engine.mapEvent("delete");
        }
        assertThat(engine.evidenceChain()).hasSize(events);
        assertThat(engine.evidenceCount("delete"))
                .isEqualTo(events);
    }

    private static RegulatoryMappingEngine engine() {
        return new RegulatoryMappingEngine();
    }
}
