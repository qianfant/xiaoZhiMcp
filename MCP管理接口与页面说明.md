# MCP 管理接口与页面说明

## 1. 功能概览

当前项目已实现一套完整的 MCP 管理与测试能力，包含：

- 外部 MCP 连接配置的新增、列表、删除、刷新
- MCP 连通性测试（临时连接 / 已保存连接）
- 工具列表加载（本地 LOCAL / 已保存外部连接）
- 工具真实调用并返回 JSON 结果
- 根据工具参数 schema 动态生成参数输入表单（支持中文描述展示）

相关核心文件：

- `src/main/java/me/xiaozhi/mcp/external/ExternalMcpAdminController.java`
- `src/main/java/me/xiaozhi/mcp/external/ExternalMcpConnectivityService.java`
- `src/main/resources/static/mcp-admin.html`

---

## 2. 后端接口清单

Base Path：`/api/external-mcp`

### 2.1 连接管理

1. `GET /connections`
- 作用：获取数据库中外部 MCP 连接列表

2. `POST /connections`
- 作用：新增连接并刷新运行中的 MCP 客户端
- 入参：
```json
{
  "connectionName": "remote-sse-2",
  "enabled": true,
  "transport": "SSE",
  "url": "https://xxx/mcp",
  "endpoint": "/mcp",
  "headers": {
    "Authorization": "Bearer xxx"
  }
}
```

3. `DELETE /connections/{id}`
- 作用：删除外部连接并刷新运行中的 MCP 客户端

4. `POST /connections/refresh`
- 作用：手动刷新运行中的 MCP 客户端

### 2.2 连通性测试

1. `POST /connections/test`
- 作用：测试临时连接（不入库）

2. `POST /connections/{id}/test`
- 作用：测试已保存连接

### 2.3 工具列表与调用

1. `GET /tools/local`
- 作用：加载本地工具列表（连接 ID 固定为 `LOCAL`）
- 说明：已按本地工具名做过滤，不会展示外部 MCP 工具

2. `GET /connections/{id}/tools`
- 作用：加载指定已保存连接的工具列表

3. `POST /tools/local/invoke`
- 作用：调用本地工具

4. `POST /connections/{id}/tools/invoke`
- 作用：调用指定已保存连接的工具

调用入参示例：
```json
{
  "toolName": "获取今天星期几",
  "arguments": {}
}
```

---

## 3. 前端页面说明（mcp-admin.html）

页面路径：`/mcp-admin.html`

### 3.1 连接区

- 新增连接：填写连接名、协议、URL、endpoint、headers 后保存
- 测试连通性：可先验证连通
- 刷新运行中客户端：触发后端 refresh 接口
- 数据库连接列表操作：
  - `测试`
  - `删除`

### 3.2 工具调用区

- 目标类型：
  - `LOCAL`（本地 MCP）
  - `SAVED`（已保存连接）
- 连接 ID：已改为下拉框（SAVED 模式下选择）
- 工具名：已改为下拉框（加载工具后选择）
- 参数输入：已改为动态表单（替代“参数 JSON 文本框”）
- 调用结果：统一在“调用结果 JSON”文本区展示

---

## 4. 动态参数表单规则

前端从工具 `inputSchema` 动态渲染参数输入项：

- 标签文案优先显示中文描述（`description`），格式：`描述（参数名）`
  - 示例：`记录内容（message）`
- 必填字段在标签后追加 `（必填）`
- 类型映射：
  - `string` -> 文本输入框
  - `number` / `integer` -> 数字输入框
  - `boolean` -> 下拉框（true/false）
  - `array` / `object` -> 文本域（JSON）
- 无参数工具：显示提示“当前工具无参数，将自动提交 {}”

提交调用时，前端会把动态表单聚合为 `arguments` 对象并发给后端。

---

## 5. LOCAL 工具过滤说明

`/tools/local` 接口会对工具列表做本地过滤：

- 通过 `DemoMcpTools` 上 `@Tool` 注解提取本地工具名集合
- 仅保留名称命中的工具
- 解决了“LOCAL 加载工具时混入外部 MCP 工具”的问题

---

## 6. 常见调用流程

1. 打开 `/mcp-admin.html`
2. 在“新增连接”中配置并保存外部连接
3. 在“工具调用测试”中选择目标类型：
- LOCAL：直接加载本地工具并调用
- SAVED：先选连接 ID，再加载工具并调用
4. 选择工具后填写（或确认自动生成的）参数
5. 点击“调用工具”，在调用结果 JSON 区查看结果

---

## 7. 备注

- 连接名需要唯一，否则保存失败
- URL 为空会被后端校验拦截
- 工具调用失败时，返回结构中会包含失败原因（message）

