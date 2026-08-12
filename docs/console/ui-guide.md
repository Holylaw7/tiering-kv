# 控制台 UI 原型指南（ADR-0146）

## 视图

| 视图 | 权限 | 内容 |
| --- | --- | --- |
| overview | READ | 租户数（降级）+ 指标 + 告警数（降级） |
| tenants | ADMIN | 租户列表 + 自服务创建表单 |
| billing | ADMIN | 租户计量快照（请求/存储/出口） |
| metrics | READ | 全量指标表 |
| alerts | ADMIN | 告警列表 |

## 渲染

```java
ConsoleUiService ui = new ConsoleUiService(api, billing, credentials);
ConsoleUiService.Page page = ui.render(token, "tenants");
// page.status() == 200 / 403 / 404
```

HTML 输出做转义（`& < > "`），防止注入。
