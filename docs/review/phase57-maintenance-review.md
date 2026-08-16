# Phase 57 Review — Maintenance Mode & v4 Planning

## 总体结论

Phase 57 建立维护模式（hotfix/backport/补丁流水线）、真实 Runner
复审执行包、v4.0 规划框架、年度复核、维护门禁、发布卫生与社区就绪；
全量回归 **≥14820 次测试执行全绿**（Surefire 口径）。

## 评审要点

1. fix-only 流程可执行（hotfix.sh + 文档）；
2. SEALED_GA → CLOSED 复审路径明确（runner-review.sh + 证据模板）；
3. v4.0 方向可评审（路线图 + RFC 模板）；
4. 年度复核与维护门禁脚本化；
5. 发布卫生（semver/SBOM/签名）与安全披露流程文档化。

## 已知限制

- 复审执行仍待真实 Runner；
- v4.0 特性开发待 RFC 获批。

## 2026-08-14 增补：v3.7.1 发布归档

- 真实 GitHub Runner 门禁连续多轮全绿（build / test / transaction-e2e /
  release，commit `580ae34` → `cd3db80` → `7b5de37`）；
- 补丁内容全部合入 main/develop：GHCR 命名、依赖漏洞（Trivy 0）、
  容器入口契约、TestPorts 端口分配器、surefire 失败重跑、BuildKit 重试、
  benchmark 组分离（71 类补全）；
- v3.7.1 tag 已发布：GitHub Release v3.7.1 + ghcr 镜像
  `ghcr.io/holylaw7/tiering-kv:v3.7.1`；
- 发布说明脚本修复：移除无条件 v1.0 模板（曾污染所有版本 body）；
- 后续：v4.0 M1（向量存储接入）按 docs/planning/v4-roadmap.md 推进。
