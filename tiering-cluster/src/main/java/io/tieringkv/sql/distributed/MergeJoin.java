package io.tieringkv.sql.distributed;

import io.tieringkv.sql.SqlEngine;

import java.util.ArrayList;
import java.util.List;

/** 分布式 JOIN 合并（ADR-0120）：各 Region 结果拼接（去重）。 */
public final class MergeJoin {

    public List<SqlEngine.Row> merge(
            List<List<SqlEngine.Row>> regionResults) {
        List<SqlEngine.Row> merged = new ArrayList<>();
        java.util.Set<Key> seen = new java.util.HashSet<>();
        for (List<SqlEngine.Row> rows : regionResults) {
            for (SqlEngine.Row row : rows) {
                if (seen.add(new Key(row.key()))) {
                    merged.add(row);
                }
            }
        }
        return merged;
    }

    private record Key(byte[] bytes) {
        private Key {
            bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && java.util.Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }
    }
}
