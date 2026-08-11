package io.tieringkv.dr;

import java.util.List;

/** 切换计划（ADR-0115）：动作序列 + 预期 RPO。 */
public record SwitchPlan(List<String> actions, long expectedRpoMillis,
                         boolean safe) {
}
