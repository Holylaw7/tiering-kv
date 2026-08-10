# Phase 13 Task Specification

# Distributed Production Optimization

Project:
Tiering-KV Distributed Tiering Storage Engine

Phase:
13

Previous Phase:
Phase 12 Distributed Production

Branch:
feature/phase13-distributed-optimization

Target:
Improve distributed throughput, metadata reliability, migration efficiency and production security.

---

# 0. Execution Rules (MANDATORY)

You are an autonomous senior distributed systems engineer.

You MUST follow the engineering workflow:

Requirement Analysis
↓
Architecture Design
↓
ADR Creation
↓
Implementation Plan
↓
TDD Development
↓
Unit Tests
↓
Integration Tests
↓
Benchmark
↓
Performance Analysis
↓
Documentation Update
↓
Git Commit

Forbidden:

- Do not directly modify code without design.
- Do not skip ADR.
- Do not merge unfinished code.
- Do not remove existing tests.
- Do not weaken assertions to make tests pass.
- Do not rewrite previous architecture unless ADR approves.

Before coding:

Create checkpoint:

checkpoint-before-phase13-distributed-optimization

---

# 1. Phase Objective

Phase 13 focuses on production-grade distributed optimization.

Main goals:

1. Improve Raft replication throughput.
2. Reduce migration overhead.
3. Introduce secure RPC communication.
4. Transform metadata service into Raft replicated service.
5. Prepare cross-machine deployment capability.

---

# 2. Current Architecture Baseline

Current:

Client

↓

ClusterClient

↓

MetadataServer

↓

Shard Leader

↓

RaftNode

├── RaftLog

├── SnapshotManager

├── RaftTransport

└── ReplicatedStorageEngine

↓

TieringStorageEngine

---

Current limitations:

TD-026:
Replication is synchronous serial propose.

Problem:

Leader:

append entry

↓

send follower1

↓

wait

↓

send follower2

↓

commit

Target:

Batch replication.

Support:

- AppendEntries batching
- Pipeline replication
- Group commit

---

TD-027:
Migration rebuilds iterator snapshot repeatedly.

Problem:

Large slot migration causes unnecessary scanning.

Target:

Cursor based migration.

---

TD-028:
RPC has no:

- TLS
- Authentication
- Rate limiting

Target:

Production RPC security layer.

---

TD-029:
MetadataServer single node.

Target:

Raft replicated metadata service.

---

# 3. Required Deliverables

## 3.1 Raft Replication Optimization

Create:

ADR-0044-raft-batch-replication.md

Decision:

Evaluate:

Option A:

Serial replication

Option B:

Batch AppendEntries

Option C:

Pipeline replication

Select based on:

- throughput
- latency
- consistency

Implement:

## Batch AppendEntries

Support:

```

Leader

pending queue

```

|
|

```

batch collector

```

|
|

```

AppendEntries RPC

```

|

```

Followers

```

Requirements:

- max batch entries configurable
- max batch bytes configurable
- timeout flush

Example:

```

raft.replication.batch.size=128

raft.replication.batch.bytes=1MB

raft.replication.flush.interval=5ms

```

---

## Pipeline Replication

Leader should support:

```

entry1 ---> follower
entry2 ---> follower
entry3 ---> follower

without waiting previous response

```

Need:

ReplicationTracker:

```

nextIndex

matchIndex

inflightRequests

```

---

Acceptance:

3 node cluster:

Before:

700-1300 ops/s

Target:

> 5000 ops/s

Maintain:

- no lost commit
- no stale read
- leader failover

---

# 4. Migration Optimization

Create:

ADR-0045-slot-cursor-migration.md

Replace:

snapshot iterator

with:

Cursor Migration

Architecture:

```

SlotMigrationTask

```

    |

```

MigrationCursor

```

    |

```

lastKey

lastVersion

checkpointOffset

```

Requirements:

Support:

- pause
- resume
- crash recovery

Migration checkpoint:

```

migration/
slot-100.cursor

```

Format:

CRC protected.

State:

```

INIT

COPYING

PAUSED

VERIFYING

SWITCHING

DONE

FAILED

```

Acceptance:

1 billion key theoretical design support.

Benchmark:

Compare:

Phase12:

16-20MB/s

Target:

> 100MB/s

---

# 5. Secure RPC Layer

Create:

ADR-0046-rpc-security.md

Implement:

## TLS

Netty TLS:

Support:

```

rpc.ssl.enabled=true

```

Certificates:

```

config/cert/
server.crt
server.key

```

---

## Authentication

Add:

RpcAuthInterceptor

Protocol:

```

client

Authorization Token

```

    |

```

server verify

```

Requirements:

- invalid token rejected
- expired token rejected

---

## Rate Limiting

Implement:

Token Bucket:

```

rpc.rate.limit.qps=10000

```

Reject:

```

ERR RATE_LIMIT

```

---

Tests:

minimum:

15

Cover:

- TLS handshake
- invalid certificate
- authentication failure
- rate limit

---

# 6. Metadata Raftization

Create:

ADR-0047-metadata-raft.md

Current:

```

MetadataServer
(single)

```

Change:

```

Metadata Cluster

Metadata Raft Group

NodeRegistry

SlotTable

Topology

```

Architecture:

```

Client

|

MetadataClient

|

Metadata Leader

|

Raft

|

Metadata Followers

```

Metadata stored:

- node information
- shard assignment
- slot ownership
- migration status

Acceptance:

Leader failure:

metadata still available.

---

# 7. Cross Machine Deployment Preparation

Create:

docs/deployment/distributed-deployment.md

Include:

## Node roles

```

gateway-node

metadata-node

storage-node

```

## Network ports

Example:

```

6379 client

7000 raft

7001 metadata

```

## Configuration

yaml:

```

cluster:
nodeId:

raft:
peers:

metadata:
endpoints:

```

---

# 8. Testing Requirements

New tests:

minimum:

80

Breakdown:

## Raft batch

20

## Migration cursor

15

## RPC security

20

## Metadata raft

20

## Integration

5

Full regression:

```

mvn test

```

Requirement:

Phase 1-13:

0 failure

---

# 9. Benchmark Requirements

Create:

```

docs/benchmark/phase13-report.md

```

Measure:

## Replication

Before:

700-1300 ops/s

After:

- throughput
- P99
- replication lag

---

## Migration

Measure:

- MB/s
- resume latency
- checkpoint overhead

---

## RPC

Measure:

- TLS overhead
- authentication overhead

---

## Metadata

Measure:

- failover time
- write throughput

---

# 10. Documentation Updates

Update:

README.md

ROADMAP.md

CHANGELOG.md

AGENT_CONTEXT.md

Add:

Phase13 architecture diagram

New ADR list:

ADR-0044

ADR-0045

ADR-0046

ADR-0047

---

# 11. Git Requirements

Commit style:

Example:

```

docs(raft): add batch replication ADR

feat(raft): implement pipeline replication

feat(migration): add cursor checkpoint

feat(rpc): add TLS authentication

feat(metadata): raft based metadata service

test(distributed): add phase13 tests

perf(distributed): add benchmark report

```

Finally:

```

git checkout develop

git merge --no-ff feature/phase13-distributed-optimization

```

Create:

checkpoint:

```

checkpoint-after-phase13-distributed-optimization

```

---

# 12. Final Report Required

Generate:

```

docs/review/phase13-distributed-optimization-review.md

```

Include:

1. Architecture changes

2. ADR summary

3. Code changes

4. Test statistics

5. Benchmark comparison

6. Bottleneck analysis

7. Known limitations

8. Next phase recommendation

---

# Phase 13 Success Criteria

All must pass:

[ ] ADR-0044 created

[ ] Raft batch replication implemented

[ ] replication throughput >5000 ops/s

[ ] cursor migration implemented

[ ] migration >100MB/s

[ ] TLS RPC working

[ ] RPC authentication working

[ ] rate limiting working

[ ] Metadata Raft cluster working

[ ] 80+ new tests

[ ] Full regression green

[ ] Benchmark completed

[ ] Documentation updated

[ ] Git merged cleanly

End of Phase 13.

```

```
