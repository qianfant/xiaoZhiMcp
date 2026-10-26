# 小智 MCP 多人工作空间

Java 21 / Spring Boot 3.4 / Spring AI MCP / MySQL。默认端口 `9998`，入口 `/login.html`。

支持：
- 登录、退出、修改密码；管理员创建用户、调整角色、停用账号、重置密码。
- 可选开放注册（普通用户），默认关闭。
- 每个账号独立管理外部 SSE / Streamable HTTP MCP 连接。
- 每人最多 10 个小智节点，每个节点配置独立的 WebSocket 接入点，绑定本人的最多 20 个外部 MCP，并选择是否开放本地工具。
- 本地工具、动态参数表单、真实工具测试。个人记录写入和检索均按用户 ID 隔离。

## 首次部署

1. 新数据库执行 `src/main/resources/sql/external_mcp_connections.sql`。
2. **已有单人数据库：先备份，执行一次 `src/main/resources/sql/multi_user_migration.sql`，不要再执行新库脚本。**
3. 设置以下环境变量。默认使用 `prod` 配置，不再默认加载旧的 `application-dev.yml`。

本地 `application-dev.yml` 保留用于开发，但已加入 Git 忽略规则，且不会进入发布 JAR；生产凭证通过环境变量提供。

```powershell
$env:XIAOZHI_EXTERNAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/xiaozhi?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:XIAOZHI_EXTERNAL_DB_USERNAME = 'xiaozhi'
$env:XIAOZHI_EXTERNAL_DB_PASSWORD = '替换为数据库密码'
$env:XIAOZHI_ADMIN_USERNAME = 'admin'
$env:XIAOZHI_ADMIN_PASSWORD = '替换为至少12位的初始密码'
mvn clean package
java -jar target/xiaozhi-spring-ai-mcp-0.0.1-SNAPSHOT.jar
```

管理员密码仅在用户表为空时使用，后续通过界面修改。已有单人外部连接在首次管理员初始化时归入该管理员，后注册用户不会继承。若用户表已有数据，迁移后仍为 NULL 的 owner_id 需由维护者核对归属后手工分配。

4. 打开 `http://127.0.0.1:9998/login.html`，使用初始管理员登录，在“用户管理”创建普通账号。
5. 每位用户登录自己的账号，新增外部 MCP；在“小智节点”粘贴自己的接入点并绑定工具。旧 `xiaozhi.bridge.endpoint` 不再自动连接，需在界面重新建节点。同一接入点只能保存一次。

可选配置：

| 环境变量 | 用途 / 默认值 |
| --- | --- |
| `SERVER_PORT` | HTTP 端口，9998 |
| `SERVER_ADDRESS` | 监听地址，0.0.0.0 |
| `XIAOZHI_REGISTRATION_ENABLED` | 是否允许自行注册，false |
| `XIAOZHI_COOKIE_SECURE` | HTTPS 部署时设 true，默认 false 便于本地 HTTP 调试 |
| `XIAOZHI_MAX_MCP_CLIENTS` | 活跃外部客户端上限，256 |
| `EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS` | 记录工具的向量服务；维度默认 4096，应与 Qdrant 集合一致 |
| `QDRANT_HOST` / `QDRANT_PORT` / `QDRANT_COLLECTION` | 记录向量库，localhost / 6334 / xiaozhi-records |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | 邮件工具配置，所有用户通过服务器的发件账号发送 |

向量记录需已有匹配维度的 Qdrant 集合。未配置邮件或向量服务时，相关工具不可正常执行，账号、连接、节点和日期工具仍可使用。旧向量记录没有可靠用户归属，不会出现在新账号的检索中；请核对后补充 `owner_id` 字符串元数据，勿直接全局共享。

## 使用与隔离

工作空间中可编辑、测试、启停、删除自己的外部 MCP，配置不同小智节点的工具绑定。节点具有独立连接状态、指数退避及随机抖动重连，停用或删除节点后取消重连。

所有 `/api` 数据接口要求登录；写操作校验 CSRF。会话使用 HttpOnly、SameSite=Strict Cookie，登录更换会话 ID；密码 BCrypt 哈希保存。密码、角色或启用状态变更后，既有会话在下个请求失效。禁用账号会停止其节点并清理其客户端。管理员没有读取其他用户连接凭证的业务接口。

原始 `/mcp`、`/sse` 不再对外提供共享工具访问。小智通过每个节点的独立 WebSocket 连接及带用户身份的工具分发器访问。外部工具使用稳定的连接 ID + 工具名哈希作为别名，避免同名工具互相覆盖；说明中保留来源名称。

## 性能边界

- 外部 MCP 按连接 ID 延迟建立并复用客户端；单个连接串行调用，避免会话互相干扰。
- 工具目录缓存 30 秒，空闲客户端 5 分钟后回收；编辑/删除只清理对应客户端，刷新仅作用于当前账号。
- 外部请求全局最多 32 个并发，工具执行全局 64 个、每个用户所在限流分组 8 个。固定 256 个分组避免限流表随用户增长；哈希落在同组的用户共享额度。
- 节点工具发现并行执行，全局最多 32 个发现任务，整体等待最多 15 秒；某个来源故障时仍可列出其他来源及本地工具。过载或超时时暂缺的工具可重新加载。
- 每个 WebSocket 一次处理一条完整消息，通过需求控制接收节奏；消息上限 1MiB，发送队列最多 8 条，发送超时 10 秒。
- 全局最多 512 个小智节点。共享 HTTP 客户端、两个重连调度线程与受并发门控的 Java 21 虚拟线程；网络请求不阻塞重连调度线程。
- Hikari 最大连接池 16，数据库按用户名、用户+连接名、用户+节点名建立索引。
- 前端一次渲染工具选项，去掉原逐条延迟绘制；节点仅在页面可见且当前选中节点页时每 15 秒刷新，避免并发轮询。用户列表每页 50 人；每人连接最多 100 个。

当前按**一个应用实例**管理节点和 HTTP 会话。不能把多个实例直接连同一库同时运行；集群部署需另加节点租约、共享会话及分布式限流。上述数值为资源边界，未进行生产容量压测。

## 验证

```powershell
mvn test
node --check src/main/resources/static/app.js
```

测试使用独立 H2 内存库和模拟网络客户端，不使用 dev 数据库、真实小智接入点或发送邮件。覆盖登录/CSRF、会话失效、管理员权限、跨账号连接与节点访问、个人记录过滤、客户端复用与缓存、慢节点隔离、断线重连及停止后的生命周期。
