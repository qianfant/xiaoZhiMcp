# MCP 管理接口与页面说明

参见 [README.md](README.md) 获取安装、旧库迁移及完整配置说明。

页面入口：`/login.html`，登录后进入 `/mcp-admin.html`。界面包含小智节点、外部 MCP、工具测试、账号设置，管理员另有用户管理。

响应格式：成功 `{ "success": true, "data": ... }`，失败 `{ "success": false, "message": "..." }` 并返回相应 HTTP 状态。连接/节点/用户 ID 为服务端分配。

| 接口 | 功能 |
| --- | --- |
| GET /api/auth/csrf | 获取 CSRF token（data.token），写操作使用 X-CSRF-TOKEN 请求头 |
| GET /api/auth/options | 查询是否开放注册 |
| POST /api/auth/login | username、password 登录；客户端保存会话 Cookie，成功后重新获取 CSRF token |
| POST /api/auth/register | 注册普通用户（需服务端开放） |
| GET /api/auth/me | 当前用户 |
| POST /api/auth/logout | 退出当前会话 |
| POST /api/auth/password | oldPassword、newPassword 修改自己的密码 |
| GET /api/admin/users?page=1 | 管理员用户列表，每页 50 条 |
| POST /api/admin/users | username、password、role、enabled 创建用户 |
| PATCH /api/admin/users/{id} | role、enabled、password 可选，调整用户；至少保留一个启用的管理员 |
| GET /api/external-mcp/connections | 当前用户全部连接（最多 100 个） |
| POST /api/external-mcp/connections | 创建自己的连接 |
| PUT /api/external-mcp/connections/{id} | 编辑自己的连接 |
| DELETE /api/external-mcp/connections/{id} | 删除自己的连接并清理客户端 |
| POST /api/external-mcp/connections/test | 测试未保存的配置，字段同保存配置；connectionName 可传 draft-test |
| POST /api/external-mcp/connections/{id}/test | 测试已有连接 |
| POST /api/external-mcp/connections/refresh | 清理当前用户的客户端和工具缓存 |
| GET /api/external-mcp/tools/local | 本地工具目录 |
| GET /api/external-mcp/connections/{id}/tools | 自己的外部连接工具目录 |
| POST /api/external-mcp/tools/local/invoke | 本地真实工具调用 |
| POST /api/external-mcp/connections/{id}/tools/invoke | 外部真实工具调用 |
| GET /api/nodes | 自己的节点和运行状态 |
| POST /api/nodes | 创建节点，提交 name、endpoint、enabled、localTools、connectionIds 数组 |
| PUT /api/nodes/{id} | 编辑自己的节点，替换绑定与配置 |
| DELETE /api/nodes/{id} | 删除并停止节点 |
| POST /api/nodes/{id}/reconnect | 重连自己的节点 |

连接保存字段：`connectionName`、`enabled`、`transport`（SSE/STREAMABLE）、`url`、可选 `endpoint`、`headers`（字符串键值对象）。读取时 `headers` 为数据库 JSON 字符串。

工具调用字段：`toolName` 和 `arguments` 对象。工具目录在 `data.tools`，调用结果为 MCP 的 `content`、`isError` 等字段。`isError=true` 表示远端工具业务执行错误，应展示完整结果。

节点列表结构：`data` 是 `{ node: {...}, runtime: {state, message} }` 数组；state 为 CONNECTING / CONNECTED / RECONNECTING / STOPPED。节点 `connectionIds` 读取为 JSON 字符串，保存时提交数组。接入点及请求头包含凭证，仅返回所属用户，前端列表只展示主机名。

所有 ID 访问和绑定均检查当前账号归属，跨账号返回 404。原始共享 `/mcp` 接口禁止访问；小智从各自独立的 WebSocket 节点调用用户范围内的工具。
