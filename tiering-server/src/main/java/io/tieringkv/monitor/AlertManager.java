package io.tieringkv.monitor;

import java.util.List;
import java.util.Map;

/** 告警管理器（Goal 7）：按规则评估指标快照。 */
public final class AlertManager {

    private final List<AlertRule> rules;

    public AlertManager(List<AlertRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<String> evaluate(Map<String, Long> metrics) {
        return rules.stream()
                .filter(rule -> metrics.containsKey(rule.metric())
                        && rule.fires(metrics.get(rule.metric())))
                .map(rule -> rule.level() + ":" + rule.metric()
                        + "=" + metrics.get(rule.metric()))
                .toList();
    }
}
