package io.tieringkv.distributed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 线性一致性验证（ADR-0297）：单键读写历史 + 线性化点搜索。
 * 小历史（≤8）全排列搜索；大历史以 invoke 序为线性化序（登记限制）。
 */
public final class LinearizabilityChecker {

    public enum OpType {
        GET,
        PUT
    }

    public record Operation(long invokeNs, long responseNs,
                            OpType type, String key,
                            String value, String result) {
    }

    private static final int MAX_PERMUTATION = 8;

    private LinearizabilityChecker() {
    }

    public static boolean isLinearizable(List<Operation> history) {
        if (history.size() <= MAX_PERMUTATION) {
            return checkPermutations(history);
        }
        return validOrder(history,
                history.stream().sorted(
                        java.util.Comparator.comparingLong(
                                Operation::invokeNs)).toList());
    }

    private static boolean checkPermutations(
            List<Operation> history) {
        return permute(history, new ArrayList<>(),
                new boolean[history.size()]);
    }

    private static boolean permute(List<Operation> history,
                                   List<Operation> order,
                                   boolean[] used) {
        if (order.size() == history.size()) {
            return validOrder(history, order);
        }
        for (int i = 0; i < history.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            order.add(history.get(i));
            if (permute(history, order, used)) {
                return true;
            }
            order.remove(order.size() - 1);
            used[i] = false;
        }
        return false;
    }

    private static boolean validOrder(
            List<Operation> history, List<Operation> order) {
        Map<String, String> state = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            Operation current = order.get(i);
            for (int j = i + 1; j < order.size(); j++) {
                Operation later = order.get(j);
                if (later.responseNs() <= current.invokeNs()) {
                    return false; // 违反实时序
                }
            }
            if (current.type() == OpType.PUT) {
                state.put(current.key(), current.value());
            } else {
                String expected = state.get(current.key());
                if (!java.util.Objects.equals(expected,
                        current.result())) {
                    return false;
                }
            }
        }
        return true;
    }
}
