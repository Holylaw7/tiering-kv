# 测试环境、门控条件与 GitHub CI

状态：Accepted（2026-09-18）

## 1. 常规本地回归

常规单元测试、进程内集成测试和 JVM 等价故障测试只需要 JDK 21 与
Maven 3.9，可在 Windows、macOS 或 Linux 运行，无需 Docker。Surefire
测试 JVM 的最大堆配置为 2 GiB。

```bash
# 功能回归：与 test workflow 一致，排除 @Tag("benchmark") 性能组
mvn -B -Dsurefire.excludedGroups=benchmark test

# 编译和打包：与 build workflow 一致
mvn -B -DskipTests clean verify
```

`pom.xml` 默认不排除 `benchmark` 标签，因此直接执行 `mvn test` 会把
性能测试也包含进来，耗时和结果波动都会更大。日常功能回归应显式排除
该标签。

## 2. 额外环境与执行门控

下表中的环境变量是**执行门控**，表示外部环境已经准备完成。设置变量
本身不会创建容器、kind 集群、网络规则或 loop 块设备；若只设置变量而
未先执行准备脚本，测试会失败。

| 测试范围 | 必要条件 | 入口或门控条件 | GitHub Actions 覆盖 |
| --- | --- | --- | --- |
| 分布式事务容器栈与冒烟 | Docker Engine，或 Docker Desktop 的 Linux containers；Docker Compose v2；Bash | `deploy/docker-compose.transaction.yml`、`scripts/container-smoke.sh` | `transaction-e2e / container-e2e` |
| kind 集群内验证 | Docker、`kind`、`kubectl`、Bash；预先构建并加载项目镜像 | `TIERINGKV_KIND_CLUSTER=true`；先运行 `scripts/kind-e2e.sh run` | `transaction-e2e / kind-e2e` |
| 真实网络混沌 | 运行 Maven 的 JVM 位于 Linux；事务容器栈已启动；后端容器具有 `NET_ADMIN`，镜像包含 `tc` | `TIERINGKV_NETWORK_CHAOS=true`、`TIERINGKV_NETEM_EXPECT=<阶段>` | `transaction-e2e / container-e2e` |
| 真实 loop 块设备 | 可控的 Linux 主机或 Runner；root；`losetup`、`mkfs.ext4`、`mount`；Maven 可写挂载点 | `TIERINGKV_CONTAINER_CHAOS=true`、`TIERINGKV_BLOCK_DEVICE_READY=true`；只读用例另需 `TIERINGKV_BLOCK_READONLY=true` | `transaction-e2e / block-device-chaos` |
| 容器磁盘故障与恢复 | loop 设备已 bind 到 `txn-meta:/data`，事务栈已启动，网关可访问 | `TIERINGKV_CONTAINER_CHAOS=true`、`TIERINGKV_BLOCK_EXPECT=failure|recovered` | `transaction-e2e / block-device-chaos` |
| Jepsen 式外部验证 | JDK 21、Maven、Docker Compose、Bash | `scripts/jepsen-run.sh run` | `transaction-e2e / jepsen-e2e` |
| 常规可复现性能测试 | JDK 21、Maven、Bash；不需要 Docker | `scripts/reproducible-benchmark.sh` | `benchmark` 定时或手动任务；`release` 完整性能门禁 |
| 真实冷缓存性能测试 | Linux root，允许写入 `/proc/sys/vm/drop_caches` | `scripts/reproducible-benchmark.sh --cold` | 常规 `benchmark` workflow 不执行，需在受控 Linux 环境单独运行 |

## 3. 容器和故障注入测试方法

本节命令使用 Bash。每次演练结束后都必须清理容器、网络规则、挂载点和
loop 设备；GitHub Actions workflow 使用 `if: always()` 执行清理。

### 3.1 分布式事务容器冒烟

```bash
docker compose -f deploy/docker-compose.transaction.yml build
docker compose -f deploy/docker-compose.transaction.yml up -d --wait
scripts/container-smoke.sh
docker compose -f deploy/docker-compose.transaction.yml down -v
```

冒烟测试经 `127.0.0.1:6379` 发送真实 RESP `SET`/`GET`，并断言响应，
不能用仅建立 TCP 连接代替。

### 3.2 kind 集群内验证

```bash
mvn -q -DskipTests package
docker build -t ghcr.io/holylaw7/tiering-kv:v3.7.0 \
  -f deploy/Dockerfile .
TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh run
TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh pdb-drain
TIERINGKV_KIND_CLUSTER=true \
  mvn -q -Dtest=KubernetesInClusterValidationTest \
  -DfailIfNoTests=false test
TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh cleanup
```

`scripts/kind-e2e.sh run` 会创建或复用 kind 集群、加载上述固定标签镜像、
应用 Kubernetes 清单、等待工作负载就绪，并生成测试所需的两个标记文件。
详细步骤见
[Kubernetes 集群内验证指南](../deployment/kubernetes-in-cluster-guide.md)。

### 3.3 真实网络混沌

先启动事务容器栈，再对四个后端容器的 `eth0` 注入 netem。以下示例验证
100 ms 延迟；丢包、分区和恢复阶段应使用对应的脚本参数、期望值和测试
方法。

```bash
scripts/network-chaos.sh delay 100ms
TIERINGKV_NETWORK_CHAOS=true TIERINGKV_NETEM_EXPECT=delay \
  mvn -q -Dtest=RealNetworkChaosTest#setGetRoundTripUnderNetem \
  -DfailIfNoTests=false test
scripts/network-chaos.sh recover
```

| 阶段 | 脚本 | `TIERINGKV_NETEM_EXPECT` | 测试方法 |
| --- | --- | --- | --- |
| 延迟 | `delay 100ms` | `delay` | `setGetRoundTripUnderNetem` |
| 丢包 | `loss 10%` | `loss` | `setGetRoundTripUnderNetem` |
| 全分区 | `partition` | `partition` | `partitionBlocksRoundTrip` |
| 恢复 | `recover` | `recovered` | `setGetRoundTripUnderNetem` |

详细步骤见 [真实网络混沌](../deployment/network-chaos.md)。

### 3.4 真实块设备和容器磁盘故障

这组命令会创建 64 MiB loop/ext4 文件系统并执行填满、只读挂载等真实
故障，只能在允许创建 loop 设备的可控 Linux 环境运行。

```bash
sudo scripts/block-device-chaos.sh setup

TIERINGKV_CONTAINER_CHAOS=true \
TIERINGKV_BLOCK_DEVICE_READY=true \
  mvn -q \
  -Dtest=RealBlockDeviceChaosTest,RealBlockDeviceExerciseTest \
  -DfailIfNoTests=false test

sudo scripts/block-device-chaos.sh readonly
TIERINGKV_CONTAINER_CHAOS=true \
TIERINGKV_BLOCK_READONLY=true \
  mvn -q \
  -Dtest=RealBlockDeviceExerciseTest#readonlyAppendFailsWithoutLoss \
  -DfailIfNoTests=false test

sudo mount -o remount,rw /mnt/tiering-kv-block
sudo scripts/block-device-chaos.sh cleanup
```

容器级磁盘故障还需要把挂载点 bind 到 `txn-meta:/data`，并在 disk-full、
readonly 和 recovered 阶段停止或恢复 metadata 容器。完整且可重复的顺序
以 `.github/workflows/transaction-e2e.yml` 的 `block-device-chaos` job 为准；
详细说明见 [真实磁盘故障注入](../deployment/real-disk-chaos.md)。

### 3.5 Jepsen 式外部验证

```bash
scripts/jepsen-run.sh run
scripts/jepsen-run.sh cleanup
```

脚本依次注入 coordinator、participant、metadata 容器终止及网络分区，
每个阶段都通过独立 JVM 进程运行一致性校验，报告写入
`target/jepsen-report.txt`。

## 4. 性能测试方法

```bash
# 内存、IO、回环网络，各 3 轮
scripts/reproducible-benchmark.sh

# 正式 5 轮
scripts/reproducible-benchmark.sh --rounds 5

# 增加较重的服务端测试
scripts/reproducible-benchmark.sh --server

# Linux root：增加真实 cold-cache 测试
sudo scripts/reproducible-benchmark.sh --cold
```

结果写入 `target/reproducible-benchmark/<时间戳>/`。workload、预热、P99
统计和结果记录规则见
[可复现 Benchmark 说明](../benchmark/reproducible-benchmark-guide.md)。

## 5. Windows、WSL2 与 Docker Desktop

Docker Desktop 能为 Compose、kind 和网络混沌提供 Linux 容器。相关脚本
使用 Bash；Windows 本地应在启用 Docker Desktop WSL2 integration 的
WSL2 发行版中执行。

`@EnabledOnOs(OS.LINUX)` 检查的是**运行 Maven 的 JVM 所在系统**，不是
Docker 容器的系统。从 Windows PowerShell 启动 Maven 时，安装 Docker
Desktop 也不会启用 Linux 专属测试；必须在 WSL2 或 Linux 中运行
JDK/Maven。

真实块设备测试还要求在 Maven 所在的 Linux 主机上以 root 操作
loop/ext4/mount。Docker Desktop 本身不提供这一宿主机控制面，应使用
GitHub Actions Ubuntu Runner 或独立的可控 Linux 环境。

## 6. `skipped` 测试

按当前 v4.1.0 测试套件，普通本地环境未设置门控变量时，预期有 13 项
测试被 JUnit 标记为 `skipped`。这在 Windows 和未启用门控的 Linux 上
都属于正常结果。

| 测试类 | 数量 | 启用条件 |
| --- | ---: | --- |
| `KubernetesInClusterValidationTest` | 3 | `TIERINGKV_KIND_CLUSTER=true`，且 kind 脚本已生成两个就绪标记 |
| `RealNetworkChaosTest` | 2 | Linux + `TIERINGKV_NETWORK_CHAOS=true`，且容器栈处于对应 netem 状态 |
| `RealBlockDeviceChaosTest` | 3 | Linux + `TIERINGKV_CONTAINER_CHAOS=true`，且 `TIERINGKV_BLOCK_DEVICE_READY=true` |
| `RealBlockDeviceExerciseTest` | 3 | Linux + `TIERINGKV_CONTAINER_CHAOS=true`；只读方法还要求 `TIERINGKV_BLOCK_READONLY=true` |
| `RealContainerDiskChaosTest` | 2 | Linux + `TIERINGKV_CONTAINER_CHAOS=true`，且容器栈和 bind-mounted loop 设备处于指定状态 |

普通 `test` workflow 虽然运行在 Ubuntu，但不会设置这些门控变量，因此
也会跳过这 13 项。`transaction-e2e` workflow 会先准备真实环境，再分阶段
显式运行它们。`RealDiskChaosTest` 使用 JVM 内故障实现，不依赖 Docker 或
Linux，会包含在常规/JVM E2E 回归中。

## 7. GitHub Actions 触发与覆盖范围

| Workflow | 触发方式 | 主要检查 |
| --- | --- | --- |
| `build` | push 到 `main`/`develop`；任意 pull request | JDK 21 编译和打包，不运行测试 |
| `test` | push 到 `main`/`develop`；任意 pull request | Ubuntu 三分片功能回归（排除 benchmark）和 JFR 冒烟；Surefire/JFR 产物保留 7 天 |
| `transaction-e2e` | push 到 `main`/`develop`；目标为 `main`/`develop` 的 pull request | JVM E2E、Compose、kind、网络混沌、loop 块设备、容器磁盘故障和 Jepsen 式验证 |
| `benchmark` | 每周日 02:00 UTC；`workflow_dispatch` 手动运行 | 核心 benchmark；结果和日志保留 7 天 |
| `release` | 受支持的版本 tag；手动 `workflow_dispatch` | 分片测试、完整发布 benchmark、Trivy、镜像推送和 GitHub Release |

仅把提交 push 到 `feature/*` 不会触发这些 workflow 的 `push` 事件。远端
Linux 全量验证应从功能分支创建目标为 `main` 或 `develop` 的 pull request，
以同时触发 `build`、`test` 和 `transaction-e2e`。性能改动可再手动运行
`benchmark`；`release` 仅用于正式发布流程。
