# 后端：AI Panel Studio

Java 21、Spring Boot 3.5.16 和 SQLite 后端，支持房间草稿、预设、讨论编排、实时结论、SSE 与管理接口。默认配置使用 OpenAI 兼容的 Chat Completions 协议，`dev` 在配置端点、模型和密钥时使用真实网关，缺少配置时使用离线演示。

## 运行

```powershell
# 在仓库根目录构建后端可执行包（前端由 Vite 单独启动）
mvn -B -f backend/pom.xml package

# 本地联调：dev profile 用确定性脚本生成，不访问外网
java -jar backend/target/ai-panel-backend-exec.jar `
  --spring.profiles.active=dev --panel.gateway.mode=scripted `
  --panel.db.path=backend/target/dev-db/panel.db `
  --panel.db.allowed-root=backend/target/dev-db
```

服务默认监听 `127.0.0.1:8080`，仅提供后端 API，基路径为 `/api/v1`。前端在独立终端运行 Vite，默认监听 `http://127.0.0.1:5173` 并把 `/api/v1` 代理到 `8080`。后台管理页是前端 `/admin` 路由，仅可通过该地址直达，不在任何页面提供入口。

## 配置与环境变量

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `panel.db.path` | `data/panel.db` | SQLite 文件路径，相对路径按进程工作目录解析 |
| `panel.db.allowed-root` | `data` | 允许打开数据库的目录，越界路径直接拒绝且不建立连接 |
| `panel.db.init-script` | `classpath:db/init.sql` | 唯一初始化脚本，构建时由根目录 `init.sql` 拷入 classpath |
| `panel.db.initialize` | `true` | 启动时是否执行初始化脚本 |
| `PANEL_AI_BASE_URL` | 空 | 经核实的供应商 API 基地址，例如含 `/v1` 的地址。网关追加 `/chat/completions`，也接受完整端点 |
| `PANEL_AI_MODE`（dev） | `auto` | 配齐三项模型参数时使用真实模型，显式 `scripted` 使用固定离线样例 |
| `PANEL_AI_MODEL` | 空 | 供应商支持的模型标识 |
| `PANEL_AI_API_KEY` | 空 | 后端运行进程读取的凭据 |
| `PANEL_AI_TEMPLATE_DIRECTORY` | 包内 `prompts/` | 自定义时指向含公共、角色、任务及四套议题策略的 UTF-8 模板目录，可附带 `workflow.json` |
| `PANEL_AI_TEMPLATE_VERSION` | `v2.7` | 模板版本号，修改模板时同步更新 |
| `PANEL_AI_CONTEXT_WINDOW` | `32768` | 供应商模型的上下文窗口，扣除本任务输出和包装预留 |
| `PANEL_AI_EVIDENCE_ENABLED` | `true` | 获选专家可请求查证，后台用 Crossref 核对原始摘要，并调用证据检索技能 |
| `panel.orchestration.capacity` | `2` | 同时执行的讨论数，与执行线程池大小一致 |
| `panel.orchestration.max-expert-utterances` | `0` | 默认按讨论进展收尾，显式正数可限定发言总数 |
| `panel.request.allowed-hosts` | `127.0.0.1,localhost,::1` | 回环 Host 白名单，越界返回 403 |
| `panel.request.allowed-origin-ports` | `5173,5183,8080` | 写请求 Origin 允许的本地端口 |

启动真实网关时设置前三项环境变量，并直接启动可执行包。供应商须支持 Chat Completions 的 JSON 对象输出，模型服务配置缺失时创建返回 `503`。可用 Spring 配置 `panel.gateway.templates` 将模板键映射到自定义文件，例如将键 `roles/host-opening.md` 映射到模板目录中的 `roles/custom-opening.md`。

## 接口

| 方法 | 路径 | 成功 | 主要失败 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/discussions?status=ACTIVE` | `200` 运行中/总结中房间摘要 | `400` |
| `POST` | `/api/v1/discussions` | `201` DRAFT 快照 + `Location` | `400`、`500`、`502`、`503`、`504` |
| `GET` | `/api/v1/discussions/{id}` | `200` 快照（含实录与结论） | `400`、`404` |
| `DELETE` | `/api/v1/discussions/{id}` | `204` 删除中断房间及其记录 | `400`、`403`、`404`、`409` |
| `POST` | `/api/v1/discussions/{id}/start` | `202` `{id,status}` | `400`、`404`、`409`、`429` |
| `GET` | `/api/v1/discussions/{id}/events?after=` | `200` `text/event-stream`，回放后接实时；终态追平 `204` | `400`、`404`、`429` |
| `GET` | `/api/v1/presets` | `200` 六条内置预设，按 slug 升序 | `500` |
| `POST` | `/api/v1/presets/{slug}/discussions` | `201` 新草稿 + `Location` | `400`、`404`、`500` |
| `GET` | `/api/v1/admin/overview` | `200` 状态计数与记录量 | `500` |
| `GET` | `/api/v1/admin/discussions?status=&keyword=&date=` | `200` 房间列表（含计数），支持按名称与日期检索 | `400` |
| `GET` | `/api/v1/admin/discussions/{id}` | `200` 快照 + 事件流 | `400`、`404` |

统一响应体 `{code,message,data,timestamp}`。详细字段见 [API 文档](../docs/api.md)。

## 讨论执行

1. `start` 用条件更新 `DRAFT → RUNNING` 并发布 `discussion.status`，重复确认只返回当前状态；终态 409、容量满 429。
2. 主持开场后按议题目标加载流程模板的阶段顺序、发言名额和报名窗口。每轮专家共享冻结实录并独立报名，服务端按意图、缺席时长和阶段关注点选人。每次成功提交专家发言即尝试提炼。收尾预留两次模型调用和 22 秒。 [流程模板](../prompts/workflow.json)
3. 席位状态、发言与公开事件在同一事务提交；`publicFocus` 只取受控映射，`IDLE` 时清空。
4. 失败时房间置为 `FAILED`、复位席位并发布失败事件，已提交内容保留。
5. 事件流按房间和序号读取，`Last-Event-ID` 优先于 `after`；全局 16 条、单房间 8 条连接上限。

## 结构

```text
src/main/java/com/aipanel
  config/     数据源守卫、初始化、故障注入钩子、执行线程池、回环请求过滤、配置绑定
  web/        Controller、请求 DTO、响应 VO、统一响应体、SSE 事件流、管理接口
  service/    草稿与快照、开场、预设复制、阵容校验、讨论编排、事件发布
  service/debate/  阶段计划、报名裁决、预算控制、模型调用与证据检索
  repository/ Spring JDBC 仓储（全部参数化，房间查询带 discussion_id）
  gateway/    OpenAI 兼容协议、模板装配、JSON Schema 与本地阵容替身
  exception/  业务错误码与全局异常映射
  model/      行对象
src/test/java/com/aipanel
  unit/       守卫、规范化、阵容校验、质量校验、slug
  it/         P05-*、P06-*、P07-*、P08-* 集成用例（MockMvc + 临时 SQLite）
  support/    网关替身、故障注入器、合成阵容样例、测试基类
```

## 数据与隔离

- 初始化脚本只有一个：仓库根目录 [init.sql](../init.sql)。构建时以 `classpath:db/init.sql` 打包，因此不存在第二份 DDL。
- 打开任何连接前先经 `SqlitePathGuard` 校验；越界或符号链接逃逸直接抛错，驱动调用次数保持为 0。
- 每个连接强制 `foreign_keys=ON`、`journal_mode=WAL`、`busy_timeout=5000`，并用 `PRAGMA` 复核。
- 集成测试只使用 `target/test-db/p05-<uuid>/` 下的临时文件库，进程退出时清理；业务库 `data/panel.db` 不会出现在测试运行中。
- 草稿创建在短事务内写 `discussion` 与 `participant`，**不写任何公开事件**，`last_event_seq` 恒为 0；模型调用与讨论执行都在事务之外。
