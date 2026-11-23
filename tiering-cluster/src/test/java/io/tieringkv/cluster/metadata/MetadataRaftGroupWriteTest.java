package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.raft.RaftNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.tieringkv.cluster.RaftTestSupport.ELECTION;
import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 元数据写路径选举窗口（ADR-0353）：
 * leader 暂缺时有界等待而非 fail-fast；leader 故障切换期间写入自动恢复。
 */
class MetadataRaftGroupWriteTest {

    @Test
    void writeWaitsForLeaderBeforeFailing() {
        // 组未启动：永无 leader，write 必须先有界等待（约 1s）再失败，
        // 而不是秒失败（慢 Runner 选举窗口会瞬时无 leader）。
        MetadataRaftGroup group = MetadataRaftGroup.create(
                List.of("m1", "m2", "m3"), ELECTION, 25, 10);
        MetadataClient client = new MetadataClient(group);
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.join("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no metadata leader");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - start);
            assertThat(elapsedMillis).as("bounded wait elapsed")
                    .isBetween(800L, 3000L);
        } finally {
            group.close();
        }
    }

    @Test
    void writeSurvivesLeaderFailoverWindow() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.create(
                List.of("m1", "m2", "m3"), ELECTION, 25, 10);
        group.start();
        MetadataClient client = new MetadataClient(group);
        try {
            awaitLeader(group.nodes(), 5000);
            RaftNode firstLeader = group.leader();
            assertThat(firstLeader).isNotNull();
            firstLeader.suspend();
            firstLeader.close();

            long start = System.nanoTime();
            assertThatCode(() -> client.join("survivor-key"))
                    .doesNotThrowAnyException();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - start);
            assertThat(elapsedMillis).as("failover write elapsed")
                    .isLessThan(10_000L);

            RaftNode newLeader = awaitLeader(group.nodes(), 5000);
            assertThat(newLeader).isNotEqualTo(firstLeader);
            assertThat(group.state(newLeader.id()).nodes()
                    .contains("survivor-key")).isTrue();
        } finally {
            group.close();
        }
    }
}
