package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor;
import io.tieringkv.capacity.ai.AutonomousCapacityController;
import io.tieringkv.capacity.ai.TrendPredictor;
import io.tieringkv.compliance.AuditExporter;
import io.tieringkv.compliance.ComplianceReport;
import io.tieringkv.compliance.ComplianceReport.Severity;
import io.tieringkv.compliance.ComplianceReport.Violation;
import io.tieringkv.console.api.SaasConsoleApi;
import io.tieringkv.datamesh.CloudFederatedExecutor;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.gateway.AutonomousTrafficController;
import io.tieringkv.gateway.RegionQuota;
import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.observability.tracing.TraceExporter;
import io.tieringkv.observability.tracing.TraceSampler;
import io.tieringkv.observability.tracing.Tracer;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.commerce.BillingSubscription;
import io.tieringkv.saas.commerce.MarketplaceCatalog;
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

/** Phase 34 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase34BenchmarkTest {

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10000})
    void saasConsoleApiThroughput(int ops) {
        CredentialManager credentials = new CredentialManager();
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        BillingSubscription subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        SaasConsoleApi api = new SaasConsoleApi(subscriptions, catalog,
                credentials);
        String token = credentials.issue(Role.ADMIN, 60_000);
        for (int i = 0; i < Math.min(ops, 100); i++) {
            api.subscribe(token, "t" + i, "p1", false);
        }
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            api.marketplace(token);
            api.status(token, "t" + (i % 100));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH SAAS-CONSOLE %d -> %d ops/s%n",
                ops, ops * 2_000L / elapsedMs);
    }

    @ParameterizedTest(name = "adjustments {0}")
    @ValueSource(ints = {1000, 10000})
    void autonomousCapacityLatency(int adjustments) {
        AutonomousCapacityController controller =
                new AutonomousCapacityController(2, 5, 1000, 100);
        AutoCapacityAdvisor advisor = new AutoCapacityAdvisor(
                new CapacityPlanner(), new TrendPredictor());
        List<TrendPredictor.Point> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new TrendPredictor.Point(i, 100 + 5.0 * i));
        }
        long start = System.nanoTime();
        for (int i = 0; i < adjustments; i++) {
            controller.apply(advisor.adviseLinear("qps", history,
                    30, 8, 1000, controller.currentNodes(),
                    500, 100));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH AUTO-CAPACITY %d -> %d ops/s%n",
                adjustments, adjustments * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "queries {0}")
    @ValueSource(ints = {1000, 10000})
    void crossCloudFederationThroughput(int queries) {
        CloudFederatedExecutor executor = new CloudFederatedExecutor(
                new io.tieringkv.compliance.ComplianceValidator(),
                new io.tieringkv.compliance.DataResidencyPolicy(
                        Map.of("aws-us", "us", "gcp-us", "us")));
        List<CloudShard> shards = List.of(
                new CloudShard("orders", "aws-us", "m"),
                new CloudShard("payments", "gcp-us", "m"));
        long start = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            executor.execute("aws-us", shards,
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 1, 1),
                    Aggregate.SUM);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH CROSS-CLOUD %d -> %d ops/s%n",
                queries, queries * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "spans {0}")
    @ValueSource(ints = {1000, 10000})
    void tracingThroughput(int spans) {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = new Tracer(new TraceSampler(1.0), exporter);
        long start = System.nanoTime();
        for (int i = 0; i < spans; i++) {
            io.tieringkv.observability.tracing.Tracer.Context
                    context = tracer.start("op");
            tracer.end(context);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH TRACING %d -> %d spans/s%n",
                spans, spans * 1_000L / elapsedMs);
    }

    @Test
    void complianceExportLatency() {
        ComplianceReport report = new ComplianceReport();
        for (int i = 0; i < 1000; i++) {
            report.add(new Violation("GDPR", "c" + i,
                    Severity.MEDIUM, "d"));
        }
        AuditExporter exporter = new AuditExporter();
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            exporter.toJson(report);
            exporter.toCsv(report);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH COMPLIANCE-EXPORT %d ms%n",
                elapsedMs);
    }

    @Test
    void trafficAutonomyLatency() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 1000);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 0.5, 100, 5000);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            controller.adjust("r1", 2000 + (i % 100));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH TRAFFIC-AUTO %d ms%n",
                elapsedMs);
    }

    @Test
    void commercialMetricsLatency() {
        MrrCalculator mrr = new MrrCalculator();
        TrialConversionTracker conversion =
                new TrialConversionTracker();
        ChurnDetector churn = new ChurnDetector();
        for (int i = 0; i < 100; i++) {
            mrr.setMonthlyAmount("t" + i, 100);
            conversion.startTrial();
            conversion.markConverted();
            churn.recordRenewal();
        }
        CommercialAlert alert = new CommercialAlert();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            assertThat(alert.evaluate(churn, conversion, 1000, 1000))
                    .isEmpty();
            mrr.mrr(Set.of("t1", "t2"));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE34-BENCH COMMERCIAL %d ms%n",
                elapsedMs);
    }
}
