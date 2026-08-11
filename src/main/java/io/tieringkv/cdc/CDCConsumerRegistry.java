package io.tieringkv.cdc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** CDC 多消费者组注册表（ADR-0112）：组间进度隔离。 */
public final class CDCConsumerRegistry {

    private final Path logDir;
    private final Path checkpointRoot;
    private final Map<String, ConsumerGroup> groups =
            new ConcurrentHashMap<>();

    public CDCConsumerRegistry(Path logDir, Path checkpointRoot) {
        this.logDir = logDir;
        this.checkpointRoot = checkpointRoot;
    }

    public ConsumerGroup register(String groupId) throws IOException {
        return groups.computeIfAbsent(groupId, id -> {
            try {
                return new ConsumerGroup(id, logDir, checkpointRoot);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public ConsumerGroup group(String groupId) {
        return groups.get(groupId);
    }

    public boolean unregister(String groupId) {
        return groups.remove(groupId) != null;
    }

    public List<String> groupIds() {
        return List.copyOf(groups.keySet());
    }

    public int size() {
        return groups.size();
    }
}
