package io.tieringkv.cluster.multiraft;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多 Raft 宿主（ADR-0058）：注册/启动/销毁/状态查询。 */
class MultiRaftNodeTest {

    private static RaftNode soloRaft(String id) {
        return new RaftNode(id, List.of(), (index, command) -> {
        }, RaftTestSupport.ELECTION, 25, 10);
    }

    @Test
    void registerAndGroupCount() {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        host.register("g2", soloRaft("n1"));
        assertThat(host.groupCount()).isEqualTo(2);
        assertThat(host.groupIds()).containsExactlyInAnyOrder("g1", "g2");
        host.close();
    }

    @Test
    void registerDuplicateRejected() {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        assertThatThrownBy(() -> host.register("g1", soloRaft("n1")))
                .isInstanceOf(IllegalArgumentException.class);
        host.close();
    }

    @Test
    void requireUnknownGroupThrows() {
        MultiRaftNode host = new MultiRaftNode("n1");
        assertThatThrownBy(() -> host.require("missing"))
                .isInstanceOf(IllegalArgumentException.class);
        host.close();
    }

    @Test
    void destroyRemovesGroup() {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        host.register("g2", soloRaft("n1"));
        host.destroy("g1");
        assertThat(host.groupCount()).isEqualTo(1);
        assertThat(host.get("g1")).isNull();
        assertThat(host.get("g2")).isNotNull();
        host.close();
    }

    @Test
    void destroyUnknownGroupIsNoop() {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.destroy("missing");
        assertThat(host.groupCount()).isZero();
        host.close();
    }

    @Test
    void closeClosesAllGroups() {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        host.register("g2", soloRaft("n1"));
        host.close();
        assertThat(host.groupCount()).isZero();
    }

    @Test
    void startAllElectsBothSoloGroups() throws Exception {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        host.register("g2", soloRaft("n1"));
        host.startAll();
        awaitTrue("g1 leader", () -> host.get("g1").state() == RaftState.LEADER, 3000);
        awaitTrue("g2 leader", () -> host.get("g2").state() == RaftState.LEADER, 3000);
        host.close();
    }

    @Test
    void statesReflectEachGroup() throws Exception {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        host.register("g2", soloRaft("n1"));
        host.startAll();
        awaitTrue("g1 leader", () -> host.states().get("g1") == RaftState.LEADER, 3000);
        awaitTrue("g2 leader", () -> host.states().get("g2") == RaftState.LEADER, 3000);
        assertThat(host.states()).containsKeys("g1", "g2");
        host.close();
    }

    @Test
    void startOnlyOneGroup() throws Exception {
        MultiRaftNode host = new MultiRaftNode("n1");
        host.register("g1", soloRaft("n1"));
        host.register("g2", soloRaft("n1"));
        host.start("g1");
        awaitTrue("g1 leader", () -> host.get("g1").state() == RaftState.LEADER, 3000);
        assertThat(host.get("g2").state()).isNotEqualTo(RaftState.LEADER);
        host.close();
    }

    @Test
    void nodeIdAccessor() {
        MultiRaftNode host = new MultiRaftNode("n9");
        assertThat(host.nodeId()).isEqualTo("n9");
        host.close();
    }
}
