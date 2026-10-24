package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertRule;
import io.tieringkv.webconsole.domain.AlertStatus;
import io.tieringkv.webconsole.repository.AlertEventRepository;
import io.tieringkv.webconsole.repository.AlertRuleRepository;
import io.tieringkv.webconsole.web.dto.AlertRuleRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 告警规则与事件：阈值求值、触发/恢复闭环。 */
@Service
public class AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertEventRepository eventRepository;
    private final AuditService auditService;

    /** ruleId:instanceId -> 首次满足时间（用于 durationSeconds 判定）。 */
    private final Map<String, LocalDateTime> satisfiedSince = new ConcurrentHashMap<>();

    public AlertService(AlertRuleRepository ruleRepository,
                        AlertEventRepository eventRepository,
                        AuditService auditService) {
        this.ruleRepository = ruleRepository;
        this.eventRepository = eventRepository;
        this.auditService = auditService;
    }

    public List<AlertRule> rules() {
        return ruleRepository.findAllByOrderByIdAsc();
    }

    @Transactional
    public AlertRule create(AlertRuleRequest request) {
        if (ruleRepository.existsByName(request.name().trim())) {
            throw BusinessException.conflict("规则名称已存在：" + request.name());
        }
        AlertRule rule = new AlertRule();
        apply(rule, request);
        ruleRepository.save(rule);
        auditService.record("ALERT_RULE_CREATE", rule.getName(), request.metric(), true);
        return rule;
    }

    @Transactional
    public AlertRule update(Long id, AlertRuleRequest request) {
        AlertRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("规则不存在：" + id));
        apply(rule, request);
        ruleRepository.save(rule);
        auditService.record("ALERT_RULE_UPDATE", rule.getName(), request.metric(), true);
        return rule;
    }

    @Transactional
    public void delete(Long id) {
        AlertRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("规则不存在：" + id));
        ruleRepository.delete(rule);
        satisfiedSince.keySet().removeIf(key -> key.startsWith(id + ":"));
        auditService.record("ALERT_RULE_DELETE", rule.getName(), null, true);
    }

    public PageResult<AlertEvent> events(String status, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, size)));
        Page<AlertEvent> result;
        if (status == null || status.isBlank()) {
            result = eventRepository.findAllByOrderByFiredAtDesc(request);
        } else {
            try {
                AlertStatus parsed = AlertStatus.valueOf(status.toUpperCase());
                List<AlertEvent> list = eventRepository.findByStatusOrderByFiredAtDesc(parsed);
                int from = Math.min(list.size(), (page - 1) * size);
                int to = Math.min(list.size(), from + size);
                return PageResult.of(list.subList(from, to), list.size(), page, size);
            } catch (IllegalArgumentException ex) {
                throw BusinessException.badRequest("非法状态：" + status);
            }
        }
        return PageResult.of(result.getContent(), result.getTotalElements(), page, size);
    }

    @Transactional
    public void resolve(Long eventId) {
        AlertEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> BusinessException.notFound("告警事件不存在：" + eventId));
        if (event.getStatus() == AlertStatus.RESOLVED) {
            return;
        }
        event.setStatus(AlertStatus.RESOLVED);
        event.setResolvedAt(LocalDateTime.now());
        eventRepository.save(event);
        auditService.record("ALERT_RESOLVE", event.getRuleName(), "event=" + eventId, true);
    }

    public long firingCount() {
        return eventRepository.countByStatus(AlertStatus.FIRING);
    }

    public long resolvedTodayCount() {
        return eventRepository.countByStatusAndFiredAtAfter(AlertStatus.RESOLVED,
                LocalDate.now().atStartOfDay());
    }

    /** 采集后求值：满足阈值（且超过持续时长）→ 触发；不满足 → 恢复。 */
    @Transactional
    public void evaluate(Long instanceId, Map<String, Double> metrics, LocalDateTime now) {
        for (AlertRule rule : ruleRepository.findByEnabledTrue()) {
            if (rule.getInstanceId() != null && !rule.getInstanceId().equals(instanceId)) {
                continue;
            }
            Double value = metrics.get(rule.getMetric());
            if (value == null) {
                continue;
            }
            String key = rule.getId() + ":" + instanceId;
            boolean hit = "GT".equalsIgnoreCase(rule.getOperator())
                    ? value > rule.getThreshold()
                    : value < rule.getThreshold();
            AlertEvent firing = eventRepository
                    .findFirstByRuleIdAndInstanceIdAndStatusOrderByFiredAtDesc(
                            rule.getId(), instanceId, AlertStatus.FIRING)
                    .orElse(null);
            if (hit) {
                LocalDateTime since = satisfiedSince.computeIfAbsent(key, ignored -> now);
                long lasted = Duration.between(since, now).getSeconds();
                if (firing == null && lasted >= rule.getDurationSeconds()) {
                    eventRepository.save(buildEvent(rule, instanceId, value, now));
                }
            } else {
                satisfiedSince.remove(key);
                if (firing != null) {
                    firing.setStatus(AlertStatus.RESOLVED);
                    firing.setResolvedAt(now);
                    eventRepository.save(firing);
                }
            }
        }
    }

    private AlertEvent buildEvent(AlertRule rule, Long instanceId, double value, LocalDateTime now) {
        AlertEvent event = new AlertEvent();
        event.setRuleId(rule.getId());
        event.setInstanceId(instanceId);
        event.setRuleName(rule.getName());
        event.setMetric(rule.getMetric());
        event.setValue(value);
        event.setThreshold(rule.getThreshold());
        event.setLevel(rule.getLevel());
        event.setStatus(AlertStatus.FIRING);
        event.setFiredAt(now);
        event.setMessage(rule.getName() + " 触发：" + rule.getMetric()
                + " " + rule.getOperator() + " " + rule.getThreshold()
                + "（当前 " + String.format(java.util.Locale.ROOT, "%.3f", value) + "）");
        return event;
    }

    private void apply(AlertRule rule, AlertRuleRequest request) {
        rule.setName(request.name().trim());
        rule.setInstanceId(request.instanceId());
        rule.setMetric(request.metric());
        rule.setOperator(request.operator().toUpperCase());
        rule.setThreshold(request.threshold());
        rule.setDurationSeconds(request.durationSeconds() == null ? 0 : request.durationSeconds());
        rule.setLevel(request.level());
        rule.setEnabled(request.enabled() == null || request.enabled());
    }
}
