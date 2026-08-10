# Phase 11 — Distributed Tiering-KV Cluster

状态：✅ 已完成（2026-08-10）

## Role

You are the lead distributed storage engineer responsible for evolving Tiering-KV from a single-node production storage engine into a distributed KV database.

You MUST follow the existing engineering workflow:

Requirement
→ Architecture Design
→ ADR Creation
→ TDD
→ Implementation
→ Benchmark
→ Code Review
→ Documentation Update
→ Git Commit

Do NOT directly modify code before completing design documents.

---

# Project Context

Current system:

Tiering-KV Phase 0-10 completed.

Existing capabilities:

- RESP compatible Redis protocol
- Netty async server
- Key shard executor
- Memory engine
- LFU eviction
- WAL persistence
- LSM cold storage
- SSTable
- Bloom filter
- Compaction
- mmap IO
- Block cache
- Production lifecycle

Current limitation:

Single-node only.

Phase 11 goal:

Introduce distributed architecture:

- shard cluster
- metadata service
- replication
- raft consensus
- failover

Target:

Architecture comparable to:

- Redis Cluster
- TiKV
- CockroachDB storage layer

---

# Phase 11 Scope

Implement distributed foundation.

The goal is NOT full cloud-scale production.

The goal:

Build a correct distributed KV architecture prototype with production-grade design.

---

# Phase 11 Milestones

## Phase 11.0 Engineering Preparation

Before coding:

Create checkpoint:

```

checkpoint-before-phase11-cluster

```

Create branch:

```

feature/phase11-cluster

```

Verify:

```

git status clean
mvn test

```

No implementation before ADR approval.

---

# Phase 11.1 Cluster Architecture Design

Create:

```

docs/architecture/distributed-architecture.md

```

Design:

```

Client

|

Gateway

|

Cluster Router

|

Metadata Service

|

Shard Leader

|

Raft Group

|

Tiering Storage Engine

```

Define:

## Node Types

### Gateway Node

Responsibilities:

- client connection
- request routing
- cluster topology cache

---

### Metadata Node

Responsibilities:

Maintain:

```

ShardID

↓

Node Group

↓

Leader

```

Example:

```

Shard 0
Leader Node A
Replica Node B,C

Shard 1
Leader Node B
Replica Node A,C

```

---

### Storage Node

Reuse:

```

TieringStorageEngine

```

Do NOT modify storage engine core.

Use adapter:

```

DistributedStorageEngine

```

    |

```

TieringStorageEngine

```

---

# Phase 11.2 ADR Requirements

Automatically create:

## ADR-0035

File:

```

docs/adr/ADR-0035-cluster-sharding-strategy.md

```

Decision:

Shard strategy.

Compare:

- Hash slot
- Consistent hash
- Range partition

Default:

Redis Cluster style:

```

16384 hash slots

```

Reason:

- predictable
- rebalance friendly

---

## ADR-0036

File:

```

docs/adr/ADR-0036-metadata-service-design.md

```

Define:

metadata storage.

Compare:

- ZooKeeper style
- Raft metadata
- Static config

Choose:

Raft based metadata service.

---

## ADR-0037

File:

```

docs/adr/ADR-0037-replication-model.md

```

Compare:

- async replication
- semi-sync
- raft replication

Choose:

Raft replication.

---

## ADR-0038

File:

```

docs/adr/ADR-0038-failure-detection-strategy.md

```

Define:

- heartbeat
- timeout
- leader election

---

# Phase 11.3 Sharding

Implement:

Package:

```

io.tieringkv.cluster.sharding

```

Components:

```

HashSlotRouter

SlotTable

ShardGroup

ShardId

PartitionKey

```

Requirements:

Given:

```

key

```

Calculate:

```

hashslot 0-16383

```

Route:

```

slot

↓

ShardGroup

↓

Leader Node

```

Tests:

- same key always same shard
- distribution test
- collision test

---

# Phase 11.4 Metadata Service

Create:

```

io.tieringkv.cluster.metadata

```

Components:

```

MetadataServer

ClusterMetadata

NodeRegistry

ShardRegistry

TopologyManager

```

Functions:

Register node:

```

JOIN cluster

```

Query:

```

GET shard topology

```

Update:

```

leader change

```

---

# Phase 11.5 Raft Integration

Do NOT implement simplified fake consensus.

Implement minimal real Raft model.

Package:

```

io.tieringkv.cluster.raft

```

Components:

```

RaftNode

RaftState

LogEntry

Term

VoteRequest

VoteResponse

AppendEntriesRequest

AppendEntriesResponse

LeaderElection

ReplicationManager

```

Support:

## Roles

```

Follower

Candidate

Leader

```

## Operations

- leader election
- heartbeat
- log replication
- commit index

---

# Phase 11.6 Storage Replication Adapter

Do NOT modify:

```

MemTable

WAL

SSTable

```

Create:

```

ReplicatedStorageEngine

```

Architecture:

```

Client Write

↓

Raft Leader

↓

Replicated Log

↓

Apply

↓

TieringStorageEngine

```

Write path:

```

append raft log

↓

majority acknowledge

↓

apply local storage

↓

response client

```

---

# Phase 11.7 Failover

Implement:

Failure scenarios:

## Leader crash

Expected:

```

Leader down

↓

Election

↓

New leader

↓

Client redirect

```

---

## Replica crash

Expected:

```

remove node

↓

metadata update

↓

continue service

```

---

# Testing Requirements

Minimum:

## Unit Tests

Add:

```

src/test/java/io/tieringkv/cluster

```

Coverage:

### Sharding

10 tests

### Metadata

10 tests

### Raft

20 tests

### Failover

10 tests

Total:

> =50 new tests

---

# Integration Tests

Create:

```

tests/cluster

```

Scenario:

3 node cluster:

```

node1
node2
node3

```

Test:

1.

write

```

SET user:1 value

```

2.

replicate

3.

kill leader

4.

new leader elected

5.

GET returns correct value

---

# Benchmark

Create:

```

docs/benchmark/cluster-report.md

```

Measure:

## Single shard

- throughput
- latency

## Multi shard

- distribution
- routing overhead

## Replication

- write latency
- replication delay

## Failover

- election time

Target:

Leader election:

```

<5 seconds

```

---

# Documentation Updates

Update:

README.md

ROADMAP.md

CHANGELOG.md

AGENT_CONTEXT.md

Add:

Distributed architecture section.

---

# Git Workflow

Use semantic commits:

Example:

```

docs(cluster): add distributed architecture ADR

feat(cluster): implement hash slot routing

feat(metadata): add cluster metadata service

feat(raft): implement leader election

feat(replication): add replicated storage engine

test(cluster): add distributed integration tests

perf(cluster): add cluster benchmark

```

Merge:

```

--no-ff

```

Final:

```

merge: integrate Phase 11 distributed cluster

```

---

# Acceptance Criteria

Phase 11 complete only when:

## Architecture

✅ Cluster architecture document

✅ ADR-0035 ~ ADR-0038

## Functionality

✅ Hash slot routing

✅ Metadata service

✅ Raft leader election

✅ Log replication

✅ Failover recovery

## Testing

✅ >=50 new tests

✅ 3 node integration test

## Performance

Provide:

- throughput
- latency
- election time

## Engineering

✅ Git history clean

✅ checkpoint created

✅ rollback possible

---

# Final Report

Generate:

```

docs/review/phase11-cluster-review.md

```

Include:

1. Architecture

2. ADR decisions

3. Implementation

4. Tests

5. Benchmark

6. Limitations

7. Next Phase

---

Execute Phase 11 only after completing design review.

```

```
