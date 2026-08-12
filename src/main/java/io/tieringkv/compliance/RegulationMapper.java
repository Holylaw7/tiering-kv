package io.tieringkv.compliance;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 法规映射（ADR-0153）：法规 → 控制项 + 覆盖率。 */
public final class RegulationMapper {

    /** 控制项：是否已实现。 */
    public record Control(String controlId, String description,
                          boolean implemented) {

        public Control {
            if (controlId == null || controlId.isBlank()) {
                throw new IllegalArgumentException(
                        "controlId required");
            }
        }
    }

    private final Map<String, Set<Control>> regulations =
            new ConcurrentHashMap<>();

    public void register(String regulation, Control... controls) {
        if (regulation == null || regulation.isBlank()) {
            throw new IllegalArgumentException(
                    "regulation required");
        }
        regulations.put(regulation,
                java.util.Arrays.stream(controls)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    public Set<Control> controls(String regulation) {
        Set<Control> controls = regulations.get(regulation);
        return controls == null ? Set.of() : controls;
    }

    public Set<String> regulations() {
        return Set.copyOf(regulations.keySet());
    }

    public boolean covers(String regulation, String controlId) {
        return controls(regulation).stream()
                .anyMatch(control -> control.controlId()
                        .equals(controlId));
    }

    public double coverage(String regulation) {
        Set<Control> controls = controls(regulation);
        if (controls.isEmpty()) {
            return 0;
        }
        long implemented = controls.stream()
                .filter(Control::implemented).count();
        return (double) implemented / controls.size();
    }

    public List<String> missingControls(String regulation) {
        return controls(regulation).stream()
                .filter(control -> !control.implemented())
                .map(Control::controlId)
                .sorted()
                .toList();
    }

    public int controlCount(String regulation) {
        return controls(regulation).size();
    }
}
