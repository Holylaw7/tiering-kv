# Phase 14 Task Specification

# Production Hardening & Cross-Machine Readiness

Project:

Tiering-KV Distributed Tiering Storage Engine

Phase:

14

Previous Phase:

Phase 13 Distributed Optimization

Branch:

feature/phase14-production-hardening

---

# 0. Execution Rules

You are a senior distributed database engineer.

MUST follow:

```
Requirement
    ↓
Architecture Design
    ↓
ADR
    ↓
Implementation Plan
    ↓
TDD
    ↓
Tests
    ↓
Benchmark
    ↓
Review
    ↓
Git Merge
```

Before coding:

Create:

```
checkpoint-before-phase14-production-hardening
```

Rules:

- No direct coding before ADR.
- Do not remove previous tests.
- Do not weaken assertions.
- All performance claims must have benchmark evidence.
- All failures must be documented.

---

# 1. Phase Objective

Transform Phase 13 distributed prototype into production-ready storage.

Main goals:

## Storage

- Batch MemTable write
- Immutable MemTable
- Adaptive flush

## Raft

- Adaptive replication
- Async client proposal
- Better tail latency

## Security

- HMAC token rotation
- mTLS

## Metadata

- Metadata Raft persistence

## Deployment

- Cross-machine verification
- Failure injection

---

# 2. Architecture Baseline

Current:

```
Client

 |

ClusterClient

 |

Metadata Raft

 |

Shard Leader

 |

RaftNode

 |

ReplicatedStorageEngine

 |

TieringStorageEngine

 |

MemTable/WAL/SSTable
```

---

# 3. MemTable Batch Write Optimization

## ADR Required

Create:

```
ADR-0048-memtable-batch-write.md
```

Evaluate:

Option A:

single put

Option B:

batch mutation

Option C:

immutable memtable swap

Select design.

---

# Implementation

Introduce:

```
BatchWriteRequest

Mutation

BatchWriter
```

Example:

```
[
 SET key1 value1,
 SET key2 value2,
 DELETE key3
]
```

Support:

- atomic batch apply
- version ordering
- WAL batch record

---

Modify:

Current:

```
MemTable.put()
```

New:

```
MemTable.applyBatch()
```

Requirements:

- single lock acquisition
- reduced object allocation
- migration throughput improvement

---

Acceptance:

Benchmark:

Before:

100B migration:

18MB/s

Target:

> 100MB/s

Tests:

minimum 20

---

# 4. Adaptive Flush

Create:

```
ADR-0049-adaptive-flush-policy.md
```

Current:

fixed watermark

Improve:

Dynamic policy:

Factors:

```
memory pressure

write rate

flush latency

sstable count

disk queue
```

Example:

```
flush interval:

low load:
500ms

high load:
50ms
```

Implement:

```
AdaptiveFlushController
```

Metrics:

```
flush_queue_depth

flush_latency

write_rate
```

---

# 5. Raft Adaptive Replication

Create:

```
ADR-0050-adaptive-raft-replication.md
```

Current:

fixed:

```
batch size
flush interval
```

Implement:

Dynamic:

```
ReplicationController


Input:

pending entries

network latency

follower lag


Output:

batch size

flush interval
```

Example:

Low latency:

```
batch=16

flush=1ms
```

High throughput:

```
batch=512

flush=10ms
```

---

# Async Client Proposal

Current:

```
client

wait propose

response
```

Improve:

```
client

append queue

callback

future complete
```

Requirements:

- timeout
- cancellation
- retry

---

Benchmark:

Current:

22K ops/s

Target:

> 50K ops/s

Maintain:

- Raft safety
- no lost commit

---

# 6. RPC Security Upgrade

Create:

```
ADR-0051-rpc-security-upgrade.md
```

Current:

static token

Improve:

## HMAC Authentication

Token:

```
clientId

timestamp

nonce

signature
```

Signature:

```
HMAC-SHA256
```

Protection:

- replay attack
- expiration
- rotation

---

## mTLS

Support:

```
rpc.tls.mode:

ONE_WAY

MUTUAL
```

Implement:

Certificate validation:

- client cert
- server cert
- CA chain

---

Tests:

minimum:

25

Cover:

- invalid signature
- expired token
- replay request
- certificate failure
- rotation

---

# 7. Metadata Raft Persistence

Create:

```
ADR-0052-metadata-log-persistence.md
```

Current:

Metadata state:

```
memory only
```

Problem:

metadata restart loses topology.

Implement:

```
MetadataRaftLog

MetadataSnapshot

MetadataRecovery
```

Persist:

```
node registry

slot table

migration state
```

Recovery:

```
snapshot

+

raft log replay
```

Acceptance:

Kill metadata leader:

restart:

topology preserved.

Tests:

20

---

# 8. Cross Machine Deployment

Create:

```
docs/deployment/cross-machine-guide.md
```

Environment:

Minimum:

```
3 storage nodes

3 metadata nodes

1 gateway
```

Document:

Network:

```
client:
6379

raft:
7000

metadata:
7001

rpc:
7002
```

Configuration:

```yaml
cluster:
 nodeId:

 network:
 peers:

 security:
 tls:true
```

---

# 9. Failure Injection

Create:

```
docs/testing/failure-injection.md
```

Test:

## Network

- delay
- disconnect
- packet loss

## Node

- kill leader
- kill follower
- restart

## Storage

- corrupt log
- disk full

Acceptance:

System:

- no data loss
- automatic recovery

---

# 10. Testing Requirements

New tests:

minimum:

```
100
```

Breakdown:

MemTable batch:

20

Flush:

15

Raft adaptive:

20

Security:

25

Metadata recovery:

15

Failure:

5

Full:

```
mvn test
```

Requirement:

```
Phase1-14

0 failures
```

---

# 11. Benchmark

Create:

```
docs/benchmark/phase14-production-report.md
```

Measure:

## Migration

Before:

216MB/s

Target:

> 500MB/s

## Raft

Before:

22K

Target:

> 50K

## Security

Measure:

TLS overhead

HMAC overhead

## Recovery

Measure:

metadata restart

raft recovery

---

# 12. Documentation

Update:

```
README.md

ROADMAP.md

CHANGELOG.md

AGENT_CONTEXT.md
```

Add:

ADR:

```
0048
0049
0050
0051
0052
```

---

# 13. Git Workflow

Commits:

Example:

```
docs(storage): add batch write ADR

feat(storage): implement batch memtable

feat(flush): adaptive flush controller

feat(raft): adaptive replication

feat(rpc): add hmac authentication

feat(metadata): persist metadata raft

test(phase14): add production tests

perf(phase14): benchmark
```

Merge:

```
git checkout develop

git merge --no-ff feature/phase14-production-hardening
```

Create:

```
checkpoint-after-phase14-production-hardening
```

---

# 14. Final Review Report

Generate:

```
docs/review/phase14-production-hardening-review.md
```

Include:

1. Architecture changes

2. ADR decisions

3. Implementation summary

4. Test statistics

5. Benchmark comparison

6. Failure injection results

7. Remaining limitations

8. Phase 15 recommendation

---

# Phase 14 Acceptance Criteria

All required:

[x] ADR-0048

[x] Batch MemTable

[x] Adaptive Flush

[x] ADR-0050

[x] Adaptive Raft

[x] Async propose

[x] HMAC authentication

[x] mTLS

[x] Metadata persistence

[x] Cross-machine guide

[x] Failure injection

[x] 100+ tests

[x] Benchmarks

[x] Documentation

[x] Git clean merge

End Phase 14.

```

```
