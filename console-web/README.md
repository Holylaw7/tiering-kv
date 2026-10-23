# Tiering-KV Console Web（展示面前端）

Vue 3 + TypeScript + Vite + Element Plus + ECharts 构建的运维控制台，
对接 `console-server` 的 `/api/v1` REST 接口。

## 页面

| 路由 | 说明 |
| --- | --- |
| `/login` | 登录（JWT，记住最近用户） |
| `/dashboard` | 运行总览：6 个实时指标卡 + 3 张趋势图 + 实例表，5s 自动刷新 |
| `/instances` | 实例纳管、探测、编辑、删除 |
| `/keyspace` | SCAN 游标分页浏览、类型识别、值查看/改写、TTL 设置、删除 |
| `/terminal` | 命令终端：执行、结果格式化、历史回放、快捷命令 |
| `/alerts` | 告警事件与规则（阈值 + 持续时长 + 级别） |
| `/backups` | 备份列表、立即备份、下载、恢复、删除 |
| `/audit` | 审计日志检索（ADMIN） |
| `/users` | 用户与角色管理（ADMIN） |

## 开发

```bash
npm install
npm run dev        # http://127.0.0.1:5173，/api 代理到 127.0.0.1:8080
npm run build      # vue-tsc 类型检查 + 生产构建
npm run preview    # 预览构建产物（4173）
```

## 冒烟测试

```bash
npm install --no-save playwright && npx playwright install chromium
node scripts/smoke.mjs    # 登录 → 总览 → 键空间 → 终端 → 告警 → 实例，并输出 screenshots/
```

截图产物见 `screenshots/`。

## 约定

- 所有接口调用集中在 `src/api/`，统一解包 `{code,message,data}`；
- 401 自动清理令牌并跳转登录页；
- 权限控制：`VIEWER` 隐藏写操作按钮，路由级拦截 `adminOnly`；
- 图表统一下沉到 `components/ChartPanel.vue`（ECharts 按需注册）。
