package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.AlertLevel;
import io.tieringkv.webconsole.domain.AlertRule;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.repository.AlertEventRepository;
import io.tieringkv.webconsole.repository.AlertRuleRepository;
import io.tieringkv.webconsole.web.dto.AlertRuleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AlertServiceTest {

    @Autowired
    private AlertService alertService;

    @Autowired
    private AlertRuleRepository ruleRepository;

    @Autowired
    private AlertEventRepository eventRepository;

    private Long ruleId;

    @BeforeEach
    void setUp() {
        eventRepository.deleteAll();
        ruleRepository.deleteAll();
        AlertRule rule = alertService.create(new AlertRuleRequest(
                "延迟过高", null, "avg_latency_ms", "GT", 5.0, 0, AlertLevel.WARN, true));
        ruleId = rule.getId();
    }

    @Test
    void firesWhenThresholdExceededAndResolvesWhenRecovered() {
        LocalDateTime now = LocalDateTime.now();
        alertService.evaluate(1L, Map.of("avg_latency_ms", 9.5), now);

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(eventRepository.findByStatusOrderByFiredAtDesc(AlertStatus.FIRING))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getRuleId()).isEqualTo(ruleId);
                    assertThat(event.getValue()).isEqualTo(9.5);
                    assertThat(event.getLevel()).isEqualTo(AlertLevel.WARN);
                });

        alertService.evaluate(1L, Map.of("avg_latency_ms", 0.2), now.plusSeconds(10));
        assertThat(eventRepository.findByStatusOrderByFiredAtDesc(AlertStatus.FIRING)).isEmpty();
        assertThat(eventRepository.countByStatus(AlertStatus.RESOLVED)).isEqualTo(1);
    }

    @Test
    void doesNotFireTwiceForSameInstance() {
        LocalDateTime now = LocalDateTime.now();
        alertService.evaluate(1L, Map.of("avg_latency_ms", 9.5), now);
        alertService.evaluate(1L, Map.of("avg_latency_ms", 12.0), now.plusSeconds(5));
        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void ignoresRulesBoundToAnotherInstance() {
        ruleRepository.deleteAll();
        alertService.create(new AlertRuleRequest(
                "仅实例9", 9L, "qps", "GT", 1.0, 0, AlertLevel.CRIT, true));
        alertService.evaluate(1L, Map.of("qps", 100.0), LocalDateTime.now());
        assertThat(eventRepository.count()).isZero();
    }

    @Test
    void disabledRuleIsNotEvaluated() {
        ruleRepository.deleteAll();
        AlertRule rule = alertService.create(new AlertRuleRequest(
                "停用规则", null, "qps", "GT", 1.0, 0, AlertLevel.CRIT, false));
        assertThat(rule.isEnabled()).isFalse();
        alertService.evaluate(1L, Map.of("qps", 100.0), LocalDateTime.now());
        assertThat(eventRepository.count()).isZero();
    }
}
