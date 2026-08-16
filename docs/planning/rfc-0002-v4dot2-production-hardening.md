# RFC-0002: v4.2.0 Production Hardening & EXPERIMENTAL → PRODUCT Promotion

## 状态

**Proposed（2026-08-16）**：方案已保存，待评审批准后开展；批准前不进行
任何实现。当前版本 v4.1.0 收尾优先，新开发等待后续指令。

## 摘要

v4.2.0 候选范围：

- WP-A：K8s Operator 生产化 + 真实集群验证（优先级最高）；
- WP-B：真实对象存储 / 市场 ADAPTER 补证（S3/MinIO、Spot 等）；
- WP-C：SQL 子集 / 向量核心 / SaaS 基础从 EXPERIMENTAL 转 PRODUCT；
- WP-D：v4.2.0 发布收尾。

v4.1.0 GA 声明保持不变；所有工作按 RFC → 评审 → ADR → TDD → 全量回归
→ 真实 Runner → Conventional Commit 流程推进。

## 动机

现状缺口（2026-08-16 代码核对）：

1. **Operator 只完成逻辑闭环**：`TieringKVOperator`（fabric8 informer
   watch/reconcile/status 回写）、`TieringKVReconciler`、
   `Fabric8OperatorClient`、CRD 清单、`scripts/kind-e2e.sh` 均已存在；
   但 `ActionApplier` 仅有 `LoggingActionApplier`（动作只打日志，
   不真正变更 StatefulSet/Deployment/CronJob），kind-e2e.sh 只验证
   静态清单 + RESP 冒烟（未走 Operator CR 流程），镜像版本停在
   v3.7.0，且未接入 CI workflow；
2. **S3/市场 ADAPTER 为模拟数据面**：`S3ObjectStorage` 是内存 map
   （put/get/delete 不发起真实 HTTP），`CredentialProbe` 只做端点/
   凭据探测；真实对象存储的备份/恢复/大对象行为未验证；
3. **SQL/向量/SaaS 停留在 EXPERIMENTAL**：SQL 31 主文件/24 测试、
   向量 14/14、SaaS 20/12，功能与基准已具备（Phase 27–46），但未做
   转正评审与门禁升级；
4. **环境证据缺口**：真实 K8s 集群 Operator 运行、真实 S3/市场凭据
   无执行记录，被基线标注为 EXPERIMENTAL / ADAPTER 状态。

## 设计

### WP-A：K8s Operator 生产化 + 真实集群验证（约 13–19 人日）

| 任务 | 现状 → 交付 |
| --- | --- |
| A1 真实动作执行器 | `LoggingActionApplier` → `K8sActionApplier`：OperatorAction 映射为 StatefulSet/Deployment/Service/CronJob 创建/缩放/镜像升级/删除，含 ownerReference + finalizer |
| A2 CRD 与 RBAC 完善 | status subresource、finalizer、validation、operator 部署清单（SA/Role/ClusterRole/LeaderElection） |
| A3 生命周期演练 | CR 全流程：创建→就绪→扩容→镜像升级→备份 CronJob→删除清理，含失败注入 |
| A4 真实 kind CI | 新增 `k8s-e2e.yml`：kind 集群→装 CRD→部署 operator→apply CR→断言资源与 status→RESP 冒烟→cleanup；同步修正 kind-e2e.sh 镜像版本与引用 |
| A5 Operator 测试 | fabric8 mock server：informer/reconcile 竞争、幂等、冲突重试、finalizer 清理 |

验收：真实 Runner `k8s-e2e` 全绿；一条 CR 完成“创建→就绪→扩容→升级→
备份→删除”；ADR-0353 记录决策。

### WP-B：真实对象存储 / 市场 ADAPTER 补证（约 13–18 人日）

| 任务 | 现状 → 交付 |
| --- | --- |
| B1 真实 S3 数据面 | 内存模拟 → 真实 S3/MinIO 客户端（签名 v4 或 SDK）：Put/Get/Delete/List + multipart 大对象 + 超时/重试/限速 |
| B2 备份/恢复接线 | ObjectStorageArchive + Backup/Restore/PITR 接真实存储：大对象、断点、校验、生命周期过期清理 |
| B3 MinIO CI | MinIO 容器服务 + 凭据注入；权限策略、100MB+ 大对象、连接中断/限速故障注入 |
| B4 Spot/市场 API | fake → 真实 HTTP 客户端（限流/缓存/降级 + 凭据门控 E2E；凭据由自托管 Runner 注入） |

验收：MinIO 真实对象存储备份/恢复 E2E 全绿；CredentialProbe REAL 模式
获得真实凭据证据；ADAPTER 升级为“真实 + 降级”双路径。

### WP-C：EXPERIMENTAL → PRODUCT 转正（约 17–25 人日）

| 任务 | 内容 |
| --- | --- |
| C1 转正标准 | promotion checklist：语义/错误码/边界/兼容承诺/SLA/压测基线/真实环境证据；更新能力分层基线 |
| C2 SQL 子集转正 | SQL 2PC 接线、错误码与 EXPLAIN 完整性、并发/容量边界、压测基线 |
| C3 向量转正 | HNSW 并发读写、持久化/恢复、容量模型、冷热口径基线补测 |
| C4 SaaS 基础转正 | 租户隔离/配额/计量计费/审计导出 + 控制面 REST + 多租户权限 E2E |
| C5 门禁升级 | release workflow 纳入转正能力门禁、白皮书/README 能力分层更新 |

验收：SQL 子集 / 向量核心 / SaaS 基础从 EXPERIMENTAL 移到 PRODUCT；
转正评审文档归档；Runner 门禁纳入新类目。全量 SQL / 完整 SaaS 商业化
不在此包内，明确留后续。

### WP-D：v4.2.0 发布收尾（约 3–5 人日）

全量回归 + 门禁 3 连绿、Trivy、GHCR 镜像、Release notes；
CHANGELOG / ROADMAP / README / ADR / 能力分层基线同步。

### 工作量与排期

| 方案 | 范围 | 工作量 | 单人 | 双人并行 |
| --- | --- | --- | --- | --- |
| 最小补证 | A4 + B3 + B4 + 最小 A1/A2 | 20–28 人日 | 4–6 周 | 2–3 周 |
| 完整演进 | A + B + C + D | **46–67 人日** | 2.5–3.5 人月 | 6–8 周 |

里程碑：M1（1–2 周）A 包 kind/operator 证据落地；M2（3–4 周）B 包
MinIO/S3 闭环；M3（5–7 周）C 包转正评审；M4（8 周）D 包 v4.2.0 发布。

## 备选

1. 只做最小环境补证（A4/B3/B4）：证据补齐但 Operator 仍无真实动作
   执行器，S3 仍为模拟数据面；
2. 只做转正不做 Operator：能力分层提前升级，但云原生运维与对象存储
   生产承诺仍缺证据；
3. 维持现状：v4.1.0 继续维护，EXPERIMENTAL / ADAPTER 标注保持不变，
   不引入新依赖面。

## 兼容性

- v4.1.0 GA 声明与冻结协议不变；新能力 additive + ADR；
- 真实凭据不入库：MinIO/自托管 Runner 注入，仓库零凭据；
- fabric8 / S3 SDK 等依赖升级需过 Trivy 与全量门禁；
- 能力分层基线变更以评审文档为准，禁止单点改标。

## 影响范围

`operator/k8s` + `datamesh` / `observability.cost` + `sql` / `vector` /
`saas` + `.github/workflows`（k8s-e2e、MinIO job）+ 文档
（architecture / review / benchmark / operations / 能力分层基线）。

## 风险

1. 托管 Runner 跑 kind：Docker-in-Docker 通常可行，受限时改 k3d 或
   自托管 Runner；
2. 真实云凭据不可入库：MinIO 容器为主证据，云 S3 凭据自托管一次性
   补证；
3. 转正范围膨胀：仅转“SQL 子集 / 向量核心 / SaaS 基础”；
4. 工作量含测试放大：按 TDD + 门禁流程约为纯实现 1.5–2 倍。

## 评审结论

**Proposed（待评审）**：2026-08-16 由首席架构师生成并保存；未获批，
未开展任何实现。批准后按 WP-A → WP-B → WP-C → WP-D 顺序推进，
并为本 RFC 创建对应 ADR 与 feature 分支。
