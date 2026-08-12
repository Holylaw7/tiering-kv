package io.tieringkv.platform;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor;
import io.tieringkv.capacity.ai.AutonomousCapacityController;
import io.tieringkv.capacity.ai.TrendPredictor;
import io.tieringkv.compliance.AuditExporter;
import io.tieringkv.compliance.ComplianceReport;
import io.tieringkv.compliance.ComplianceReport.Severity;
import io.tieringkv.compliance.ComplianceReport.Violation;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.compliance.RegulationMapper;
import io.tieringkv.compliance.RegulationMapper.Control;
import io.tieringkv.console.api.SaasConsoleApi;
import io.tieringkv.datamesh.CloudFederatedExecutor;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.gateway.AutonomousTrafficController;
import io.tieringkv.gateway.RegionQuota;
import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.observability.cost.CostAttribution;
import io.tieringkv.observability.cost.CostAttribution.CostEntry;
import io.tieringkv.observability.tracing.TraceExporter;
import io.tieringkv.observability.tracing.TraceSampler;
import io.tieringkv.observability.tracing.Tracer;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.commerce.BillingSubscription;
import io.tieringkv.saas.commerce.MarketplaceCatalog;
import io.tieringkv.saas.commerce.Subscription;
import io.tieringkv.saas.operations.ChurnDetector;
import io.tieringkv.saas.operations.CommercialAlert;
import io.tieringkv.saas.operations.MrrCalculator;
import io.tieringkv.saas.operations.TrialConversionTracker;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 34 生产门禁（JVM 级扩展）：闭环 + 护栏 + 主权 + 可观测。 */
class Phase34ProductionGateTest {

    @Test
    void saasOrderToBillingToMrrClosure() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        BillingSubscription subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        CredentialManager credentials = new CredentialManager();
        SaasConsoleApi api = new SaasConsoleApi(subscriptions, catalog,
                credentials);
        String admin = credentials.issue(Role.ADMIN, 60_000);
        api.subscribe(admin, "t1", "p1", false);
        subscriptions.billing().meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 100);
        assertThat(api.roll(admin, "t1")).isPresent();
        MrrCalculator mrr = new MrrCalculator();
        subscriptions.tenants().forEach(tenant -> mrr.record(
                new io.tieringkv.saas.billing.Invoice(tenant, "p1",
                        new io.tieringkv.saas.billing.BillingPeriod(
                                0, 1, true),
                        List.of(new io.tieringkv.saas.billing.Invoice
                                .LineItem(UsageMeter.MeterType.REQUESTS,
                                100, 0.01, 1.0)))));
        assertThat(mrr.mrr(Set.of("t1"))).isEqualTo(1.0);
    }

    @Test
    void autonomousCapacityGuardrails() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 3, 2, 10);
        AutoCapacityAdvisor advisor = new AutoCapacityAdvisor(
                new CapacityPlanner(), new TrendPredictor());
        List<TrendPredictor.Point> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new TrendPredictor.Point(i, 100.0));
        }
        var advice = advisor.adviseLinear("qps", history, 10,
                8, 1000, controller.currentNodes(), 500, 100);
        assertThat(controller.apply(advice).outcome()).isIn(
                AutonomousCapacityController.Outcome.EXECUTED,
                AutonomousCapacityController.Outcome.SKIPPED);
        assertThat(controller.rejectedReasons()).isEmpty();
    }

    @Test
    void autonomousTrafficRollbackAfterApply() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 100);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 0.5, 10, 500);
        controller.adjust("r1", 300);
        assertThat(quota.quota("r1")).isEqualTo(150);
        controller.rollback();
        assertThat(quota.quota("r1")).isEqualTo(100);
    }

    @Test
    void autonomousTrafficCircuitBreaker() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 100);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 0.5, 10, 500);
        controller.openCircuit("gate failure");
        assertThat(controller.adjust("r1", 200).outcome())
                .isEqualTo(AutonomousTrafficController.Outcome.REJECTED);
        assertThat(quota.quota("r1")).isEqualTo(100);
    }

    @Test
    void crossCloudSovereigntyGate() {
        CloudFederatedExecutor executor = new CloudFederatedExecutor(
                new io.tieringkv.compliance.ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-eu", "eu")));
        assertThatThrownBy(() -> executor.execute("aws-us",
                List.of(new CloudShard("orders", "aws-us", "m"),
                        new CloudShard("payments", "gcp-eu", "m")),
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void complianceRegulationCoverageGate() {
        RegulationMapper mapper = new RegulationMapper();
        mapper.register("GDPR", new Control("g1", "residency", true),
                new Control("g2", "audit", true),
                new Control("g3", "erasure", false));
        assertThat(mapper.coverage("GDPR")).isEqualTo(2.0 / 3);
        assertThat(mapper.missingControls("GDPR"))
                .containsExactly("g3");
    }

    @Test
    void complianceReportAndExportGate() {
        ComplianceReport report = new ComplianceReport();
        report.add(new Violation("GDPR", "g3", Severity.HIGH,
                "erasure missing"));
        AuditExporter exporter = new AuditExporter();
        assertThat(exporter.toJson(report))
                .contains("\"severity\":\"HIGH\"");
        assertThat(exporter.toCsv(report))
                .contains("\"GDPR\",\"g3\",\"HIGH\"");
    }

    @Test
    void auditExportJsonValidShape() {
        TenantAuditLog log = new TenantAuditLog();
        log.record("t1", "subscribe");
        String json = new AuditExporter().toJson(log);
        assertThat(json).startsWith("[{").endsWith("}]");
    }

    @Test
    void tracingAcrossRpcGate() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = new Tracer(new TraceSampler(1.0), exporter);
        Tracer.Context gateway = tracer.start("gateway");
        String header = tracer.inject(gateway);
        Tracer.Context remote = tracer.extract(header);
        Tracer.Context shard = tracer.start("shard", remote);
        tracer.end(shard);
        tracer.end(gateway);
        assertThat(exporter.spans()).hasSize(2);
        assertThat(exporter.spans().get(0).traceId())
                .isEqualTo(gateway.traceId());
        assertThat(exporter.spans().get(0).parentSpanId())
                .isEqualTo(gateway.spanId());
    }

    @Test
    void costAttributionGate() {
        CostAttribution attribution = new CostAttribution();
        attribution.add(new CostEntry("t1", "orders", "aws-us",
                "storage", 10));
        attribution.add(new CostEntry("t1", "payments", "gcp-us",
                "compute", 5));
        assertThat(attribution.byTenant()).containsEntry("t1", 15.0);
        assertThat(attribution.byCloud())
                .containsEntry("aws-us", 10.0)
                .containsEntry("gcp-us", 5.0);
    }

    @Test
    void commercialAlertGate() {
        ChurnDetector churn = new ChurnDetector();
        churn.recordChurn();
        churn.recordRenewal();
        TrialConversionTracker conversion =
                new TrialConversionTracker();
        conversion.startTrial();
        conversion.markExpired();
        List<CommercialAlert.Alert> alerts =
                new CommercialAlert().evaluate(churn, conversion,
                        80, 100);
        assertThat(alerts).extracting(CommercialAlert.Alert::type)
                .containsExactlyInAnyOrder("CHURN", "CONVERSION",
                        "MRR_DROP");
    }

    @Test
    void trialToActiveToChurnGate() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        BillingSubscription subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        subscriptions.subscribe("t1", "p1", true);
        subscriptions.activate("t1");
        assertThat(subscriptions.status("t1").state())
                .isEqualTo(Subscription.State.ACTIVE);
        subscriptions.cancel("t1");
        assertThat(subscriptions.status("t1").state())
                .isEqualTo(Subscription.State.CANCELED);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedSaaSMassSubscribe(int count) {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        BillingSubscription subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        for (int i = 0; i < count; i++) {
            subscriptions.subscribe("t" + i, "p1", i % 2 == 0);
        }
        assertThat(subscriptions.count()).isEqualTo(count);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {2, 4, 8})
    void parameterizedCrossCloudAggregates(int shardCount) {
        CloudFederatedExecutor executor = new CloudFederatedExecutor(
                new io.tieringkv.compliance.ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")));
        List<CloudShard> shards = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            shards.add(new CloudShard("d" + i,
                    i % 2 == 0 ? "aws-us" : "gcp-us", "m"));
        }
        var result = executor.execute("aws-us", shards,
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1),
                Aggregate.SUM);
        assertThat(result.value()).isEqualTo(shardCount);
    }

    @ParameterizedTest(name = "spans {0}")
    @ValueSource(ints = {10, 100})
    void parameterizedTraceChains(int depth) {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = new Tracer(new TraceSampler(1.0), exporter);
        Tracer.Context root = tracer.start("root");
        List<Tracer.Context> contexts = new ArrayList<>();
        contexts.add(root);
        for (int i = 0; i < depth; i++) {
            contexts.add(tracer.start("hop" + i,
                    contexts.get(contexts.size() - 1)));
        }
        for (int i = contexts.size() - 1; i >= 0; i--) {
            tracer.end(contexts.get(i));
        }
        assertThat(exporter.spans()).hasSize(depth + 1);
    }

    @Test
    void autonomousCapacityDailyResetGate() {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 10, 1, 100);
        AutoCapacityAdvisor advisor = new AutoCapacityAdvisor(
                new CapacityPlanner(), new TrendPredictor());
        List<TrendPredictor.Point> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(new TrendPredictor.Point(i, 100.0));
        }
        controller.apply(advisor.adviseLinear("qps", history, 10,
                8, 1000, controller.currentNodes(), 500, 100));
        controller.newDay();
        assertThat(controller.adjustmentsToday()).isZero();
    }

    @Test
    void commercialMetricsConcurrentGate() throws Exception {
        MrrCalculator mrr = new MrrCalculator();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    mrr.setMonthlyAmount("t" + i, 1);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(mrr.byTenant()).hasSize(50);
        assertThat(mrr.total()).isEqualTo(50);
    }

    @Test
    void sovereigntyGateIsDeterministic() {
        CloudFederatedExecutor executor = new CloudFederatedExecutor(
                new io.tieringkv.compliance.ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-eu", "eu")));
        List<CloudShard> shards = List.of(
                new CloudShard("a", "aws-us", "m"),
                new CloudShard("b", "gcp-eu", "m"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> executor.execute("aws-us",
                    shards,
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 1, 1),
                    Aggregate.SUM))
                    .isInstanceOf(SecurityException.class);
        }
    }
}
