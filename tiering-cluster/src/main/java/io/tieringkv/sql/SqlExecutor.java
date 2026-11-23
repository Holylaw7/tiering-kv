package io.tieringkv.sql;

import io.tieringkv.mvcc.MvccStorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** SQL 子集执行器（ADR-0113）：基于 MVCC Snapshot Read。 */
public final class SqlExecutor {

    public record Row(byte[] key, byte[] value) {
    }

    public List<Row> execute(SelectStatement statement,
                             MvccStorageEngine engine, long readTS) {
        List<Row> rows = new ArrayList<>();
        if (statement.exactKey() != null) {
            io.tieringkv.mvcc.MvccEntry entry = engine.read(
                    statement.exactKey(), readTS);
            if (entry != null && !entry.isDelete()) {
                rows.add(new Row(statement.exactKey(), entry.value()));
            }
            return rows;
        }
        Map<byte[], byte[]> scanned = engine.scan(
                statement.startKey() == null ? new byte[0]
                        : statement.startKey(),
                statement.endKey(), readTS);
        for (Map.Entry<byte[], byte[]> entry : scanned.entrySet()) {
            if (rows.size() >= statement.limit()) {
                break;
            }
            rows.add(new Row(entry.getKey(), entry.getValue()));
        }
        return rows;
    }
}
