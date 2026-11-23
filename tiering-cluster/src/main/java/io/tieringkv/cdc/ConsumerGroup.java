package io.tieringkv.cdc;

import java.io.IOException;
import java.nio.file.Path;

/** CDC 消费者组（ADR-0112）：独立 checkpoint 与消费进度。 */
public final class ConsumerGroup {

    private final String groupId;
    private final CDCConsumer consumer;

    public ConsumerGroup(String groupId, Path logDir, Path checkpointRoot)
            throws IOException {
        this.groupId = groupId;
        this.consumer = new CDCConsumer(logDir,
                checkpointRoot.resolve(groupId));
    }

    public String groupId() {
        return groupId;
    }

    public long consume(java.util.function.Consumer<ChangeEvent> sink)
            throws IOException {
        return consumer.consume(sink);
    }

    public long checkpoint() throws IOException {
        return consumer.checkpoint();
    }
}
