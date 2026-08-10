# Phase 12 — Distributed Productionization

## Role

You are the principal distributed storage engineer responsible for upgrading
Tiering-KV from an in-memory Raft prototype into a production-oriented
distributed storage system.

You MUST follow the existing project engineering workflow:

Requirement
↓
Architecture Design
↓
ADR Creation
↓
Implementation Plan
↓
TDD
↓
Coding
↓
Integration Test
↓
Benchmark
↓
Documentation Update
↓
Git Commit

Do NOT directly modify code without completing design documents and ADRs.

---

# 0. Current System Context

Project:

Tiering-KV

Current completed phases:

Phase 0:
Engineering initialization

Phase 1:
RESP protocol + Netty server

Phase 2:
Memory Engine

- MemTable
- SkipList
- TTL
- Striped Lock

Phase 3:
Cache Eviction

- LFU
- ARC prototype

Phase 4:
WAL Persistence

Phase 5:
LSM Cold Storage

- SSTable
- Bloom Filter
- Compaction

Phase 6:
Tier Scheduling

- Flush scheduler
- Migration scheduler
- Backpressure

Phase 7:
Concurrency Optimization

- Key shard executor
- Hot key mitigation

Phase 8:
IO Optimization

- mmap
- Block Cache
- OffHeap MemoryPool

Phase 9:
Production Benchmark

Phase 10:
Production Service Lifecycle

Phase 11:
Distributed Cluster Prototype

Implemented:

- Hash Slot Routing
- Metadata Service
- Raft Leader Election
- Raft Replication
- ReplicatedStorageEngine
- Failover

Current limitation:

Raft state is memory-only.
RPC is process-local.
No snapshot.
No dynamic slot migration.

---

# Phase 12 Objective

Upgrade:

```

Distributed Prototype
|
v
Production-oriented Distributed Storage

```

Introduce:

1. Persistent Raft Log

2. Raft Snapshot

3. Network RPC Layer

4. Replication Optimization

5. Dynamic Slot Migration

---

# Phase 12 Architecture Target

Target architecture:

                 Client

                   |

             Cluster Router

                   |

             Metadata Service
                   |
              Raft Metadata


                   |

        +----------+----------+

        |                     |

    Shard Group A        Shard Group B


        |

      Raft Node


        |

Persistent Raft Log

        |

ReplicatedStorageEngine

        |

TieringStorageEngine

---

# Phase 12 Deliverables

## Task 1 — Raft Persistent Log

Goal:

Replace memory-only Raft log.

Implement:

```

raft/
├── RaftLog
├── LogEntry
├── LogSegment
├── RaftLogWriter
├── RaftLogReader
└── RaftLogRecovery

```

Requirements:

### Log Format

Define binary format:

```

MAGIC
VERSION
TERM
INDEX
COMMAND_TYPE
DATA_LENGTH
DATA
CRC32C

```

Must create ADR:

```

ADR-0039-raft-log-storage-format.md

```

---

### Durability

Support:

```

SYNC
ASYNC
NONE

```

similar to WAL.

Default:

```

ASYNC

```

Document durability tradeoff.

---

### Recovery

On restart:

```

load segment

↓

validate CRC

↓

recover entries

↓

restore commitIndex

```

Corrupted tail:

truncate safely.

---

# Task 2 — Raft Snapshot

Goal:

Avoid infinite Raft log growth.

Implement:

```

snapshot/

SnapshotManager

SnapshotWriter

SnapshotReader

SnapshotMetadata

```

Snapshot contains:

```

lastIncludedIndex

lastIncludedTerm

state machine data

checksum

```

Create:

```

ADR-0040-raft-snapshot-strategy.md

```

Requirements:

Support:

```

log:

1
2
3
...
100000

snapshot(index=90000)

remaining:

90001-100000

```

Recovery:

```

load snapshot

*

replay remaining log

```

---

# Task 3 — Netty RPC Layer

Current:

```

NodeA
|
Java method call
|
NodeB

```

Replace:

```

NodeA

TCP

NodeB

```

Implement:

```

rpc/

RpcServer

RpcClient

RpcRequest

RpcResponse

RpcCodec

RequestId

```

Use:

- Netty TCP
- Binary protocol

Support:

Raft RPC:

```

AppendEntries

RequestVote

InstallSnapshot

```

Create:

```

ADR-0041-distributed-rpc-design.md

```

Requirements:

- timeout
- retry
- request correlation
- connection reuse

---

# Task 4 — Replication Optimization

Current:

Leader:

```

append

↓

wait majority

↓

commit

```

Improve:

## Commit Propagation

After commit:

leader immediately sends:

```

commitIndex

```

to followers.

Goal:

Reduce:

```

replication lag
13-35ms

```

Target:

<5ms

---

Implement:

```

ReplicationTracker

CommitNotifier

FollowerProgress

```

---

Create:

```

ADR-0042-replication-lag-optimization.md

```

---

# Task 5 — Dynamic Slot Migration

Goal:

Support:

```

node join

↓

slot move

↓

data migration

↓

traffic switch

```

Implement:

```

migration/

SlotMigrationManager

MigrationTask

MigrationState

MigrationCheckpoint

```

State machine:

```

INIT

↓

COPYING

↓

VERIFYING

↓

SWITCHING

↓

DONE

```

Requirements:

- resumable
- checkpoint
- checksum verify
- no data loss

Create:

```

ADR-0043-slot-migration-strategy.md

```

---

# Testing Requirements

All existing tests MUST remain green.

Before merge:

```

mvn test

```

Required new tests:

## Raft Persistence

minimum 15:

- append recovery
- crc failure
- truncate tail
- restart recovery
- term recovery

---

## Snapshot

minimum 10:

- create snapshot
- restore snapshot
- partial log replay
- corrupted snapshot

---

## RPC

minimum 15:

- request response
- timeout
- retry
- reconnect
- raft message transport

---

## Migration

minimum 10:

- slot copy
- checksum verify
- resume
- failure recovery

---

## Integration

Required:

3 node real TCP cluster:

Scenario:

```

write key

↓

replicate

↓

kill leader

↓

elect new leader

↓

read success

```

---

# Benchmark Requirements

Create:

```

docs/benchmark/distributed-production-report.md

```

Measure:

## Raft

- append latency
- commit latency
- replication lag

## RPC

- QPS
- P99
- connection count

## Migration

- MB/s
- recovery time

Compare:

Phase 11:

```

process-local

```

Phase 12:

```

TCP distributed

```

---

# ADR Requirements

Must create:

```

ADR-0039 raft log storage format

ADR-0040 raft snapshot strategy

ADR-0041 distributed rpc design

ADR-0042 replication lag optimization

ADR-0043 slot migration strategy

```

Each ADR must include:

- Context
- Problem
- Options
- Decision
- Consequences
- Future evolution

---

# Documentation Updates

Update:

```

README.md

ROADMAP.md

CHANGELOG.md

AGENT_CONTEXT.md

```

Update architecture:

```

docs/architecture/distributed-architecture.md

```

---

# Git Workflow

Before coding:

create checkpoint:

```

checkpoint-before-phase12-distributed-production

```

Branch:

```

feature/distributed-production

```

Commit style:

Example:

```

docs(raft): add persistent log ADR

feat(raft): implement raft WAL

feat(snapshot): add snapshot manager

feat(rpc): implement netty rpc transport

feat(cluster): add slot migration

test(distributed): add integration tests

perf(cluster): add benchmark report

```

Merge:

```

git merge --no-ff feature/distributed-production

```

---

# Acceptance Criteria

Phase 12 is complete only when:

## Functionality

[ ] Raft log survives restart

[ ] Snapshot recovery works

[ ] TCP RPC replaces local calls

[ ] Replication lag improved

[ ] Slot migration works

## Quality

[ ] All tests pass

[ ] ADR complete

[ ] Benchmark complete

[ ] Documentation updated

## Final Report

Generate:

```

docs/review/phase12-distributed-production-review.md

```

Include:

- architecture changes
- ADR list
- test statistics
- benchmark results
- limitations
- next roadmap

---

# Important Rules

1. Do not simplify Raft correctness.

2. Do not bypass persistence.

3. Do not fake distributed communication.

4. Do not remove existing Tiering Storage components.

5. Maintain backward compatibility with RESP protocol.

6. Every important architecture decision requires ADR.

7. Every phase change requires Git checkpoint.

Execute Phase 12 step by step.

```

---

```
