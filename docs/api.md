# AI Panel Studio 前后端 API 文档

## 一、文档概述

- **项目名称** AI Panel Studio
- **接口基础地址** `http://127.0.0.1:8080/api/v1` 为本地联调示例。前端从同源入口使用相对路径 `/api/v1`。本文接口路径均相对于该基础地址。
- **接口版本** `v1`，由基础路径标识。
- **认证方式** 本地单实例，接口无需用户登录和 token。模型 API Key 由后端环境变量提供。
- **请求头通用规则**
  - 常规 API 使用 `Accept: application/json`。携带 JSON 请求体的接口使用 `Content-Type: application/json`。
  - SSE 使用 `Accept: text/event-stream`，重连头见订阅接口。
  - 所有请求须通过服务端的 `Host` 检查。状态变更请求携带 `Origin` 时，须匹配配置的本地来源。未携带 `Origin` 的本地命令行请求仍须通过 `Host` 检查。

## 二、通用约定

### 2.1 数据格式和字段

- 请求体和常规响应使用 UTF-8 编码的 `application/json`，字段名使用 `camelCase`。SSE 握手成功使用 `text/event-stream`，每个事件的 `data` 仍为 JSON。SSE 握手失败按常规 JSON 响应处理。
- 带 JSON 请求体的接口接收一个完整对象，拒绝重复键、未知字段和尾随文本。选填入参用省略表示。标注可空的响应字段始终返回，空值为 `null`。空集合用 `[]`。
- 创建议题先按 NFC 规范化，将制表符和换行折叠为空格并去除首尾空白。拒绝 NUL、其余控制字符以及双向覆盖或隔离控制符。移除开头 BOM 和无语义的零宽空格 `U+200B`。长度按规范化后的 Unicode 码点数计算。 [输入规范](../prompts/runtime.md#输入脱敏与生成内容安全)
- 房间 ID 为小写带连字符 UUID。成员与观点 ID 为房间内标识。预设 `slug` 由小写字母、数字及连字符组成。
- 事件序号、发言序号和来源游标均为 JSON 安全整数。事件与发言各自从 1 开始按房间递增。快照事件游标和来源游标可为 0。

### 2.2 分页和时间

- v1 的讨论列表和预设列表返回完整集合，`data` 为数组。当前版本未定义分页请求参数或响应元数据。
- 所有时间使用 UTC ISO 8601 字符串，例如 `2026-11-20T02:00:00Z`。秒的小数位可省略，提供时为 1–9 位。`timestamp` 是响应生成时间，`occurredAt` 是已提交事件的时间，`createdAt` 是发言创建时间。结束时间不得早于开始时间。

### 2.3 统一返回体

- `code`（整数，必填）与 HTTP 响应状态一致。
- `message`（字符串，必填）为中文提示，可用于展示。
- `data`（必填）成功时为对应接口的业务数据，失败时为 `null`。
- `timestamp`（时间字符串，必填）为响应生成时间。
- SSE 成功连接使用事件流，终态追平的 HTTP `204` 使用空响应体。

```json
{
  "code": 200,
  "message": "成功",
  "data": [],
  "timestamp": "2026-11-20T02:00:00Z"
}
```

### 2.4 通用错误码

- HTTP `400` 表示参数错误，`403` 表示请求来源被拒绝，`404` 表示资源不存在。
- HTTP `409` 表示状态冲突，`429` 表示容量已满，`500` 表示本地系统异常。
- HTTP `502` 表示上游调用或输出校验失败，`503` 表示模型配置缺失，`504` 表示上游超时。
- 客户端按 HTTP 状态处理错误。具体触发条件见 [错误码字典](#41-错误码字典)。

## 三、接口列表

### 3.1 查询进行中的讨论

- **请求方法** `GET`
- **接口路径** `/discussions`
- **接口描述** 获取大厅正在直播或正在总结的讨论。按 `startedAt` 降序排列，时间相同时按 `id` 升序。

#### 请求头

无需 token。使用 `Accept: application/json`。请求体为空。

#### 请求参数

- Query `status`（字符串，选填）仅支持 `ACTIVE`，省略时也为 `ACTIVE`。
- Body 为空。

#### 请求示例

以下 JSON 表示路径和查询参数。实际请求通过 URL 传参，请求体为空。

```json
{"query":{"status":"ACTIVE"}}
```

#### 响应参数

通用包装字段见 [统一返回体](#23-统一返回体)。

- `data` 是房间摘要数组，空列表为 `[]`。
- `data[].id`（UUID）为房间 ID，`data[].topic`（字符串）为议题。
- `data[].status`（枚举）为 `RUNNING` 或 `SUMMARIZING`。
- `data[].expertCount`（整数）为专家数量，`data[].startedAt`（时间字符串）为开始时间。

#### 成功响应示例

HTTP `200`。

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "id": "00000000-0000-4000-8000-00000000000a",
      "topic": "中小团队应优先自研还是采购 AI 客服？",
      "status": "RUNNING",
      "expertCount": 2,
      "startedAt": "2026-11-20T02:00:01Z"
    },
    {
      "id": "00000000-0000-4000-8000-00000000000b",
      "topic": "如何设计百人公司的混合办公规则？",
      "status": "RUNNING",
      "expertCount": 2,
      "startedAt": "2026-11-20T02:00:01Z"
    }
  ],
  "timestamp": "2026-11-20T02:00:45Z"
}
```

#### 失败响应示例

`400` 参数错误，`403` Host 拒绝，`500` 本地服务异常。

HTTP `400` 示例。

```json
{"code":400,"message":"请求参数无效","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

### 3.2 新建讨论草稿

- **请求方法** `POST`
- **接口路径** `/discussions`
- **接口描述** 根据议题和专家人数生成阵容。R01 输出和阵容校验通过后保存草稿。创建失败时回滚本次写入。

#### 请求头

无需 token。使用 `Accept: application/json` 和 `Content-Type: application/json`。浏览器写请求的 `Origin` 须通过通用规则。

#### 请求参数

- Query 为空。
- Body `topic`（字符串，必填）为议题，按通用规则规范化后须为 3–120 个 Unicode 码点。
- Body `expertCount`（整数，选填）取 2–6，省略时取 4。显式 `null` 非法。

#### 请求示例

以下为实际发送的 JSON 请求体。

```json
{"topic":"中小团队应优先自研还是采购 AI 客服？","expertCount":2}
```

#### 响应参数

通用包装字段见 [统一返回体](#23-统一返回体)。

- `id`（UUID）为房间 ID。`topic`（字符串）为议题。`expertCount`（整数）为专家数，范围 2–6。
- `discussionGoal`（枚举）由有效 R01 或预设确定并写入草稿。后续阶段使用持久化值。阶段号和报名窗口号由后端保管。
- `status`（`DiscussionStatus`）为房间状态。`startedAt`、`endedAt`（时间字符串或 `null`）以及 `errorCode`（业务码或 `null`）按状态返回。
- `participants`（`Participant[]`）包含 1 位主持和指定数量的专家。主持排首位，专家按成员 ID 升序。成员 ID、姓名和色标在房间内唯一。
- `transcript`（`Utterance[]`）按发言序号升序。`insights`（`Insight[]`）为当前完整结论投影。
- `insightStatus`（`InsightStatus`）为提炼状态。`insightsThroughUtteranceSeq`（非负整数）为该投影覆盖的最新专家发言序号。没有成功投影时为 0，对应数据库的 SQL `NULL`。
- `summary`（字符串或 `null`）在完成时返回两句中立收尾。主持归因整理专家观点、共识范围、未决分歧与证据缺口。`lastEventSeq`（非负整数）用于接续 SSE，与快照内容来自同一只读事务。
- 草稿的实录和结论均为 `[]`，`insightStatus` 为 `PENDING`，两个游标均为 0，`startedAt`、`endedAt`、`errorCode`、`summary` 均为 `null`。完整子对象见 [响应对象字典](#43-响应对象字典)。

#### 成功响应示例

HTTP `201`。响应头 `Location: /api/v1/discussions/00000000-0000-4000-8000-00000000000a`。

```json
{
  "code": 201,
  "message": "成功",
  "data": {
    "id": "00000000-0000-4000-8000-00000000000a",
    "topic": "中小团队应优先自研还是采购 AI 客服？",
    "expertCount": 2,
    "discussionGoal": "EVALUATE",
    "status": "DRAFT",
    "startedAt": null,
    "endedAt": null,
    "errorCode": null,
    "participants": [
      {
        "id": "host",
        "role": "HOST",
        "displayName": "顾言",
        "profession": "节目主持",
        "title": "圆桌主持人",
        "stance": null,
        "perspective": null,
        "colorKey": "violet",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "e1",
        "role": "EXPERT",
        "displayName": "陈简",
        "profession": "成本管理",
        "title": "成本控制负责人",
        "stance": "四周内交付时优先采购成熟方案",
        "perspective": "COST",
        "colorKey": "blue",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "e2",
        "role": "EXPERT",
        "displayName": "林昭",
        "profession": "软件工程",
        "title": "平台架构师",
        "stance": "规则频繁变更时维护自研编排",
        "perspective": "DELIVERY",
        "colorKey": "green",
        "stageStatus": "IDLE",
        "publicFocus": null
      }
    ],
    "transcript": [],
    "insightStatus": "PENDING",
    "insightsThroughUtteranceSeq": 0,
    "insights": [],
    "summary": null,
    "lastEventSeq": 0
  },
  "timestamp": "2026-11-20T02:00:45Z"
}
```

#### 失败响应示例

`400` 参数错误，`403` 来源拒绝，`500` 本地异常，`502` 上游调用或输出校验失败，`503` 模型配置缺失，`504` 上游超时。

HTTP `502` 示例。

```json
{"code":502,"message":"暂时无法生成合格的专家阵容","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

### 3.3 查询预设讨论

- **请求方法** `GET`
- **接口路径** `/presets`
- **接口描述** 返回系统预设议题、目标和阵容。包含 6 条内置预设，按 `slug` 升序。

#### 请求头

无需 token。使用 `Accept: application/json`。请求体为空。

#### 请求参数

- Query 为空。
- Body 为空。

#### 请求示例

以下 JSON 表示参数为空。实际请求体为零字节。

```json
{}
```

#### 响应参数

通用包装字段见 [统一返回体](#23-统一返回体)。

- `data` 是预设数组。
- `data[].slug`（字符串）为预设标识。`data[].topic`（字符串）为议题。
- `data[].discussionGoal`（`DiscussionGoal`）为预设目标，`data[].expertCount`（整数）为专家数。
- `data[].participants`（`PresetParticipant[]`）为预设阵容。字段见 [响应对象字典](#43-响应对象字典)。

#### 成功响应示例

HTTP `200`。

```json
{
  "code": 200,
  "message": "成功",
  "data": [
    {
      "slug": "ai-graduate-careers",
      "topic": "AI 对应届计算机毕业生的影响有多大？",
      "discussionGoal": "EXPLORE",
      "expertCount": 4,
      "participants": [
        {
          "id": "host",
          "role": "HOST",
          "displayName": "闻知夏",
          "profession": "议题访谈",
          "title": "圆桌主持人",
          "stance": null,
          "perspective": null,
          "colorKey": "violet"
        },
        {
          "id": "e1",
          "role": "EXPERT",
          "displayName": "陆行之",
          "profession": "软件研发校招",
          "title": "研发校招负责人",
          "stance": "如果外包与正式岗位都缩招，我会先看招聘预算；只有相同预算下例行编码岗名额降得更多，才倾向于认为 AI 替代了部分新人任务。",
          "perspective": "GOVERNANCE",
          "colorKey": "blue"
        },
        {
          "id": "e2",
          "role": "EXPERT",
          "displayName": "许闻川",
          "profession": "研发工程",
          "title": "初级开发带教工程师",
          "stance": "我预期 AI 能让新人更快完成有清晰验收标准的小改动；若返工耗时抵消初稿节省，我会下调对实际交付收益的判断。",
          "perspective": "DELIVERY",
          "colorKey": "green"
        },
        {
          "id": "e3",
          "role": "EXPERT",
          "displayName": "方宁",
          "profession": "高校就业服务",
          "title": "计算机专业毕业去向跟踪教师",
          "stance": "我倾向于认为 AI 辅助作品先改善无实习学生的面试机会；若同类岗位中首份录用同样提前，收益就可能延伸到初筛之后。",
          "perspective": "USER_IMPACT",
          "colorKey": "amber"
        },
        {
          "id": "e4",
          "role": "EXPERT",
          "displayName": "梁若衡",
          "profession": "劳动经济研究",
          "title": "技术变迁与就业研究员",
          "stance": "我会用低暴露岗位作对照估计影响；若高低暴露岗位在普及前就有不同的招聘趋势，这个差值不能直接解释为 AI 的影响。",
          "perspective": "EVIDENCE",
          "colorKey": "rose"
        }
      ]
    },
    {
      "slug": "bus-demand-shuttle",
      "topic": "近郊产业园如何试点预约接驳，让夜班通勤更可靠？",
      "discussionGoal": "PROPOSE",
      "expertCount": 4,
      "participants": [
        {
          "id": "host",
          "role": "HOST",
          "displayName": "苏迢",
          "profession": "议题访谈",
          "title": "圆桌主持人",
          "stance": null,
          "perspective": null,
          "colorKey": "violet"
        },
        {
          "id": "e1",
          "role": "EXPERT",
          "displayName": "沈芮",
          "profession": "公交运营",
          "title": "园区接驳线路调度主管",
          "stance": "我主张保留下班高峰的固定班次，把预约车放到尾班后；若低谷预约能稳定合乘并让等车时间更短，再扩大覆盖。",
          "perspective": "DELIVERY",
          "colorKey": "blue"
        },
        {
          "id": "e2",
          "role": "EXPERT",
          "displayName": "龚可",
          "profession": "工业园区用工服务",
          "title": "园区工会通勤协调员",
          "stance": "我主张保留电话预约和临时登车名额，优先帮助排班随时变化的人；如果这些名额长期闲置，再缩减预留。",
          "perspective": "USER_IMPACT",
          "colorKey": "green"
        },
        {
          "id": "e3",
          "role": "EXPERT",
          "displayName": "贺嘉树",
          "profession": "公共交通财务",
          "title": "公交成本与排班分析员",
          "stance": "我倾向于先承诺到主要换乘站的接驳，按每次完成的夜班换乘算成本；若远端站点能减少大量单独接送，可纳入试点。",
          "perspective": "COST",
          "colorKey": "amber"
        },
        {
          "id": "e4",
          "role": "EXPERT",
          "displayName": "彭逸",
          "profession": "交通服务监管",
          "title": "城市公交服务质量主管",
          "stance": "我主张先明确错过末班车的兜底责任，再扩大预约服务；如果试点能记录并处理漏接投诉，可放宽固定站点限制。",
          "perspective": "GOVERNANCE",
          "colorKey": "rose"
        }
      ]
    },
    {
      "slug": "clinic-message-reminders",
      "topic": "社区诊所应把复诊提醒默认改为微信消息吗？",
      "discussionGoal": "EVALUATE",
      "expertCount": 4,
      "participants": [
        {
          "id": "host",
          "role": "HOST",
          "displayName": "程蔓",
          "profession": "议题访谈",
          "title": "圆桌主持人",
          "stance": null,
          "perspective": null,
          "colorKey": "violet"
        },
        {
          "id": "e1",
          "role": "EXPERT",
          "displayName": "贾映雪",
          "profession": "基层医疗运营",
          "title": "社区卫生服务中心预约主管",
          "stance": "我倾向对已验证绑定的患者默认用微信提醒；若未读比例升高且爽约率没有下降，就按联系习惯分流渠道。",
          "perspective": "DELIVERY",
          "colorKey": "blue"
        },
        {
          "id": "e2",
          "role": "EXPERT",
          "displayName": "陈言秋",
          "profession": "基层卫生服务研究",
          "title": "复诊依从性研究员",
          "stance": "只有同年龄、同随访频率患者的实际复诊率提升，我才支持替换默认渠道；消息成功送达只能说明渠道可用。",
          "perspective": "EVIDENCE",
          "colorKey": "green"
        },
        {
          "id": "e3",
          "role": "EXPERT",
          "displayName": "谭晓岚",
          "profession": "老年数字服务",
          "title": "老年就医协助项目负责人",
          "stance": "我主张由患者自己选择，特别是使用家属代管手机的人；如果电话随访能及时覆盖未读患者，我可以接受微信作为默认。",
          "perspective": "USER_IMPACT",
          "colorKey": "amber"
        },
        {
          "id": "e4",
          "role": "EXPERT",
          "displayName": "严明舟",
          "profession": "医疗隐私治理",
          "title": "基层医疗数据保护负责人",
          "stance": "只有绑定可撤销且锁屏通知不显示诊疗信息，我才支持默认微信；共用手机的告知与授权若无法核验，仍应沿用已确认的渠道。",
          "perspective": "RISK",
          "colorKey": "rose"
        }
      ]
    },
    {
      "slug": "grocery-pickup-delays",
      "topic": "生鲜门店上线自提预约后，晚高峰等待为何反而变长？",
      "discussionGoal": "SOLVE",
      "expertCount": 4,
      "participants": [
        {
          "id": "host",
          "role": "HOST",
          "displayName": "孟澄",
          "profession": "议题访谈",
          "title": "圆桌主持人",
          "stance": null,
          "perspective": null,
          "colorKey": "violet"
        },
        {
          "id": "e1",
          "role": "EXPERT",
          "displayName": "韩池",
          "profession": "生鲜门店运营",
          "title": "门店拣货班组长",
          "stance": "我怀疑预约单在高峰前集中下单，拣货赶不上取货；若到店前备齐的订单同样排长队，拣货就不是首要瓶颈。",
          "perspective": "DELIVERY",
          "colorKey": "blue"
        },
        {
          "id": "e2",
          "role": "EXPERT",
          "displayName": "樊岑",
          "profession": "零售履约分析",
          "title": "门店履约数据分析师",
          "stance": "我会核对上线前后等待起止点是否一致；若用相同到店和交付时间重算后差异仍在，再按门店和时段查原因。",
          "perspective": "EVIDENCE",
          "colorKey": "green"
        },
        {
          "id": "e3",
          "role": "EXPERT",
          "displayName": "庄雨亭",
          "profession": "零售服务设计",
          "title": "门店取货体验研究员",
          "stance": "我怀疑顾客到店后找不到专用取货入口，导致二次排队；若入口清晰的门店也出现同等延迟，应优先查柜台交接。",
          "perspective": "USER_IMPACT",
          "colorKey": "amber"
        },
        {
          "id": "e4",
          "role": "EXPERT",
          "displayName": "丁叙",
          "profession": "冷链质量管理",
          "title": "门店冷链品控主管",
          "stance": "我会先排查冷藏品临时补拣是否堵住交接；若常温单同幅变慢，不能用冷链异常解释全部等待增长。",
          "perspective": "RISK",
          "colorKey": "rose"
        }
      ]
    },
    {
      "slug": "office-retrofit-heat",
      "topic": "老旧办公楼降温，应优先换空调还是先做外遮阳？",
      "discussionGoal": "EVALUATE",
      "expertCount": 4,
      "participants": [
        {
          "id": "host",
          "role": "HOST",
          "displayName": "常栖",
          "profession": "议题访谈",
          "title": "圆桌主持人",
          "stance": null,
          "perspective": null,
          "colorKey": "violet"
        },
        {
          "id": "e1",
          "role": "EXPERT",
          "displayName": "纪文翊",
          "profession": "建筑节能",
          "title": "既有建筑能耗诊断工程师",
          "stance": "若西晒区负荷集中在午后，我倾向先装外遮阳；如果夜间和背阴区也普遍过热，应先查空调制冷能力。",
          "perspective": "EVIDENCE",
          "colorKey": "blue"
        },
        {
          "id": "e2",
          "role": "EXPERT",
          "displayName": "顾晴",
          "profession": "楼宇运维",
          "title": "商业楼宇暖通运维主管",
          "stance": "若故障主要来自老机组频繁停机，我支持先更换故障机组；若保养后制冷能力达标，就先处理窗面得热。",
          "perspective": "DELIVERY",
          "colorKey": "green"
        },
        {
          "id": "e3",
          "role": "EXPERT",
          "displayName": "卢清越",
          "profession": "物业资产管理",
          "title": "办公楼改造造价负责人",
          "stance": "我支持以全周期成本比较两条路径：遮阳若要长时间脚手架和外墙审批，局部换机可能更划算；低成本可拆方案会改变排序。",
          "perspective": "COST",
          "colorKey": "amber"
        },
        {
          "id": "e4",
          "role": "EXPERT",
          "displayName": "乔予安",
          "profession": "室内环境设计",
          "title": "办公舒适度评估师",
          "stance": "我优先考虑工位体感与日照眩光，倾向在窗边试装遮阳；若遮阳后相邻区域温差和投诉没有下降，优先调整空调分区。",
          "perspective": "USER_IMPACT",
          "colorKey": "rose"
        }
      ]
    },
    {
      "slug": "rental-repair-disputes",
      "topic": "租赁社区如何改进报修流程，减少超时和押金纠纷？",
      "discussionGoal": "PROPOSE",
      "expertCount": 4,
      "participants": [
        {
          "id": "host",
          "role": "HOST",
          "displayName": "夏临",
          "profession": "议题访谈",
          "title": "圆桌主持人",
          "stance": null,
          "perspective": null,
          "colorKey": "violet"
        },
        {
          "id": "e1",
          "role": "EXPERT",
          "displayName": "季雪宁",
          "profession": "住房租赁服务",
          "title": "长租社区管家主管",
          "stance": "我主张受理时即区分漏水断电与普通维修，并给租客明确上门时段；若分级后普通故障积压，需要单独安排小修班次。",
          "perspective": "DELIVERY",
          "colorKey": "blue"
        },
        {
          "id": "e2",
          "role": "EXPERT",
          "displayName": "乔晟",
          "profession": "住宅维修运营",
          "title": "小区维修派工负责人",
          "stance": "我倾向把批量上门用于低风险小修；若同类问题重复返修率上升，先独立处理易误诊的故障，再决定是否合并。",
          "perspective": "COST",
          "colorKey": "green"
        },
        {
          "id": "e3",
          "role": "EXPERT",
          "displayName": "袁珂",
          "profession": "消费权益调解",
          "title": "住房租赁纠纷调解员",
          "stance": "我主张报修时记录设备初始状态和正常损耗，扣押金时逐项出示依据；若现场照片不能说明责任，安排双方核对。",
          "perspective": "GOVERNANCE",
          "colorKey": "amber"
        },
        {
          "id": "e4",
          "role": "EXPERT",
          "displayName": "韩舒",
          "profession": "社区租客服务",
          "title": "租客服务站协调员",
          "stance": "我优先提供可重新预约的晚间时段和进度通知；若爽约集中在日间第三方维修商，先改善上门承诺再增加通知。",
          "perspective": "USER_IMPACT",
          "colorKey": "rose"
        }
      ]
    }
  ],
  "timestamp": "2026-11-20T02:00:45Z"
}
```

#### 失败响应示例

`400` 请求参数错误，`403` Host 拒绝，`500` 读取或初始化异常。

HTTP `500` 示例。

```json
{"code":500,"message":"服务暂时不可用","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

### 3.4 从预设新建讨论草稿

- **请求方法** `POST`
- **接口路径** `/presets/{slug}/discussions`
- **接口描述** 复制指定预设的议题、目标、人数和阵容，生成独立的房间 ID 和成员 ID。写入失败时回滚。

#### 请求头

无需 token。使用 `Accept: application/json`。浏览器写请求的 `Origin` 须通过通用规则。请求体为空。

#### 请求参数

- Path `slug`（字符串，必填）为预设标识，例如 `bus-demand-shuttle`。
- Query 为空。
- Body 为空。

#### 请求示例

以下 JSON 表示路径和查询参数。实际请求通过 URL 传参，请求体为空。

```json
{"params":{"slug":"bus-demand-shuttle"}}
```

#### 响应参数

通用包装字段见 [统一返回体](#23-统一返回体)。

- `data` 是处于 `DRAFT` 的完整快照。字段、类型和初始空值见 [新建讨论草稿](#32-新建讨论草稿)。
- `data.discussionGoal`、`data.expertCount` 和成员角色属性来自所选预设。

#### 成功响应示例

HTTP `201`。响应头 `Location: /api/v1/discussions/00000000-0000-4000-8000-00000000000c`。

```json
{
  "code": 201,
  "message": "成功",
  "data": {
    "id": "00000000-0000-4000-8000-00000000000c",
    "topic": "近郊产业园如何试点预约接驳，让夜班通勤更可靠？",
    "expertCount": 4,
    "discussionGoal": "PROPOSE",
    "status": "DRAFT",
    "startedAt": null,
    "endedAt": null,
    "errorCode": null,
    "participants": [
      {
        "id": "copy-host",
        "role": "HOST",
        "displayName": "苏迢",
        "profession": "议题访谈",
        "title": "圆桌主持人",
        "stance": null,
        "perspective": null,
        "colorKey": "violet",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "copy-e1",
        "role": "EXPERT",
        "displayName": "沈芮",
        "profession": "公交运营",
        "title": "园区接驳线路调度主管",
        "stance": "我主张保留下班高峰的固定班次，把预约车放到尾班后；若低谷预约能稳定合乘并让等车时间更短，再扩大覆盖。",
        "perspective": "DELIVERY",
        "colorKey": "blue",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "copy-e2",
        "role": "EXPERT",
        "displayName": "龚可",
        "profession": "工业园区用工服务",
        "title": "园区工会通勤协调员",
        "stance": "我主张保留电话预约和临时登车名额，优先帮助排班随时变化的人；如果这些名额长期闲置，再缩减预留。",
        "perspective": "USER_IMPACT",
        "colorKey": "green",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "copy-e3",
        "role": "EXPERT",
        "displayName": "贺嘉树",
        "profession": "公共交通财务",
        "title": "公交成本与排班分析员",
        "stance": "我倾向于先承诺到主要换乘站的接驳，按每次完成的夜班换乘算成本；若远端站点能减少大量单独接送，可纳入试点。",
        "perspective": "COST",
        "colorKey": "amber",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "copy-e4",
        "role": "EXPERT",
        "displayName": "彭逸",
        "profession": "交通服务监管",
        "title": "城市公交服务质量主管",
        "stance": "我主张先明确错过末班车的兜底责任，再扩大预约服务；如果试点能记录并处理漏接投诉，可放宽固定站点限制。",
        "perspective": "GOVERNANCE",
        "colorKey": "rose",
        "stageStatus": "IDLE",
        "publicFocus": null
      }
    ],
    "transcript": [],
    "insightStatus": "PENDING",
    "insightsThroughUtteranceSeq": 0,
    "insights": [],
    "summary": null,
    "lastEventSeq": 0
  },
  "timestamp": "2026-11-20T02:00:45Z"
}
```

#### 失败响应示例

`400` 参数错误，`403` 来源拒绝，`404` 预设不存在，`500` 本地异常。

HTTP `404` 示例。

```json
{"code":404,"message":"资源不存在","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

### 3.5 确认开始讨论

- **请求方法** `POST`
- **接口路径** `/discussions/{id}/start`
- **接口描述** 首次确认将草稿切换为 `RUNNING`，提交后只派发一次执行任务。活跃房间重复确认时返回其当前状态。终态返回 `409`。检查模型配置和执行配额仅发生在草稿首次确认时。

#### 请求头

无需 token。使用 `Accept: application/json`。浏览器写请求的 `Origin` 须通过通用规则。请求体为空。

#### 请求参数

- Path `id`（UUID，必填）为房间 ID。
- Query 为空。
- Body 为空。

#### 请求示例

以下 JSON 表示路径和查询参数。实际请求通过 URL 传参，请求体为空。

```json
{"params":{"id":"00000000-0000-4000-8000-00000000000a"}}
```

#### 响应参数

通用包装字段见 [统一返回体](#23-统一返回体)。

- `data.id`（UUID）为房间 ID。
- `data.status`（枚举）首次为 `RUNNING`。重复确认活跃房间时为 `RUNNING` 或 `SUMMARIZING`。

#### 成功响应示例

HTTP `202`。

```json
{
  "code": 202,
  "message": "成功",
  "data": {"id":"00000000-0000-4000-8000-00000000000a","status":"RUNNING"},
  "timestamp": "2026-11-20T02:00:45Z"
}
```

#### 失败响应示例

`400` 参数错误，`403` 来源拒绝，`404` 房间不存在，`409` 已结束，`429` 执行槽已满，`500` 本地异常，`503` 模型配置缺失。容量不足或配置缺失时可重试确认草稿。任务派发失败时提交失败终态。

HTTP `429` 示例。

```json
{"code":429,"message":"容量已满，请稍后重试","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

### 3.6 查询讨论快照

- **请求方法** `GET`
- **接口路径** `/discussions/{id}`
- **接口描述** 读取任意状态的讨论快照。阵容、实录、结论和 SSE 游标在同一只读事务中取得。终态仍可读取已提交实录。

#### 请求头

无需 token。使用 `Accept: application/json`。请求体为空。

#### 请求参数

- Path `id`（UUID，必填）为房间 ID。
- Query 为空。
- Body 为空。

#### 请求示例

以下 JSON 表示路径和查询参数。实际请求通过 URL 传参，请求体为空。

```json
{"params":{"id":"00000000-0000-4000-8000-00000000000a"}}
```

#### 响应参数

通用包装字段见 [统一返回体](#23-统一返回体)。

- `data` 是完整快照，字段和类型见 [新建讨论草稿](#32-新建讨论草稿)。
- `data.lastEventSeq` 是本次快照覆盖的最后一条已提交事件，可作为首次 SSE 连接的 `after`。
- `data.startedAt`、`data.endedAt`、`data.errorCode`、`data.summary` 的空值规则见 [讨论状态](#讨论状态)。

#### 成功响应示例

HTTP `200`。

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "id": "00000000-0000-4000-8000-00000000000a",
    "topic": "中小团队应优先自研还是采购 AI 客服？",
    "expertCount": 2,
    "discussionGoal": "EVALUATE",
    "status": "COMPLETED",
    "startedAt": "2026-11-20T02:00:01Z",
    "endedAt": "2026-11-20T02:00:20Z",
    "errorCode": null,
    "participants": [
      {
        "id": "host",
        "role": "HOST",
        "displayName": "顾言",
        "profession": "节目主持",
        "title": "圆桌主持人",
        "stance": null,
        "perspective": null,
        "colorKey": "violet",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "e1",
        "role": "EXPERT",
        "displayName": "陈简",
        "profession": "成本管理",
        "title": "成本控制负责人",
        "stance": "四周内交付时优先采购成熟方案",
        "perspective": "COST",
        "colorKey": "blue",
        "stageStatus": "IDLE",
        "publicFocus": null
      },
      {
        "id": "e2",
        "role": "EXPERT",
        "displayName": "林昭",
        "profession": "软件工程",
        "title": "平台架构师",
        "stance": "规则频繁变更时维护自研编排",
        "perspective": "DELIVERY",
        "colorKey": "green",
        "stageStatus": "IDLE",
        "publicFocus": null
      }
    ],
    "transcript": [
      {
        "seq": 1,
        "participantId": "host",
        "kind": "OPENING",
        "content": "本场讨论采购与自研，请各位说明你们的评估标准和证据出处。",
        "replyToUtteranceSeq": null,
        "createdAt": "2026-11-20T02:00:02Z"
      },
      {
        "seq": 2,
        "participantId": "e1",
        "kind": "EXPERT",
        "content": "A 可先采购完整方案，通过小流量验证首次解决率。",
        "replyToUtteranceSeq": null,
        "createdAt": "2026-11-20T02:00:05Z"
      },
      {
        "seq": 3,
        "participantId": "e2",
        "kind": "EXPERT",
        "content": "A 也应通过小流量验证首次解决率。核心编排可先自研。",
        "replyToUtteranceSeq": 2,
        "createdAt": "2026-11-20T02:00:10Z"
      },
      {
        "seq": 4,
        "participantId": "e1",
        "kind": "EXPERT",
        "content": "A 补充用规则迁移成本作为可退出的判断条件。",
        "replyToUtteranceSeq": 3,
        "createdAt": "2026-11-20T02:00:15Z"
      },
      {
        "seq": 5,
        "participantId": "host",
        "kind": "SUMMARY",
        "content": "两位发言专家都提出用小流量验证首次解决率。陈简倾向采购完整方案，林昭主张自研核心编排，双方对首版方案仍有分歧。",
        "replyToUtteranceSeq": null,
        "createdAt": "2026-11-20T02:00:19Z"
      }
    ],
    "insightStatus": "STALE",
    "insightsThroughUtteranceSeq": 3,
    "insights": [{"id":"i1","kind":"CONSENSUS","text":"先用小流量验证首次解决率","evidenceUtteranceSeqs":[2,3]}],
    "summary": "两位发言专家都提出用小流量验证首次解决率。陈简倾向采购完整方案，林昭主张自研核心编排，双方对首版方案仍有分歧。",
    "lastEventSeq": 20
  },
  "timestamp": "2026-11-20T02:00:45Z"
}
```

#### 失败响应示例

`400` 参数错误，`403` Host 拒绝，`404` 房间不存在，`500` 本地异常。

HTTP `404` 示例。

```json
{"code":404,"message":"资源不存在","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

### 3.7 订阅讨论事件

- **请求方法** `GET`
- **接口路径** `/discussions/{id}/events`
- **接口描述** 通过服务器发送事件（Server-Sent Events，SSE）回放指定游标之后的已提交事件，并接续实时事件。前端使用浏览器原生 `EventSource`。

#### 请求头

无需 token。SSE 请求使用 `Accept: text/event-stream`。首次连接通过 Query 带上快照游标。浏览器重连时可自动携带 `Last-Event-ID`（十进制数字文本，选填）。

#### 请求参数

- Path `id`（UUID，必填）为房间 ID。
- Query `after`（十进制数字文本，选填）为房间内事件游标。`Last-Event-ID` 和 `after` 均省略时使用 `0`。
- Body 为空。

#### 请求示例

以下 JSON 表示路径和查询参数。实际请求通过 URL 传参，请求体为空。

```json
{"params":{"id":"00000000-0000-4000-8000-00000000000a"},"query":{"after":"2"}}
```

#### 响应参数

- HTTP `200` 使用 `text/event-stream; charset=utf-8` 和 `Cache-Control: no-cache, no-transform`。每帧包括 `id`、`event`、`data`，以空行结束。建议约每 15 秒发送一次 `: heartbeat` 注释，心跳无 ID 且不推进事件游标。
- `id` 为房间内事件序号的十进制文本。`event` 为公开事件名。`data` 是 JSON 对象，包含 `discussionId`（UUID）、`seq`（正整数）、`occurredAt`（时间字符串）和 `payload`（事件载荷）。`id` 等于 `String(data.seq)`。载荷见本节的事件类型。
- HTTP `204` 表示讨论已结束且客户端游标已追平，响应体为空。

#### 成功响应示例

HTTP `200` 的四类事件示例取自同一房间的不同时间点。完整流按序号连续发送。

```text
id: 1
event: discussion.status
data: {"discussionId":"00000000-0000-4000-8000-00000000000a","seq":1,"occurredAt":"2026-11-20T02:00:01Z","payload":{"status":"RUNNING","startedAt":"2026-11-20T02:00:01Z","endedAt":null,"errorCode":null}}

id: 3
event: participant.status
data: {"discussionId":"00000000-0000-4000-8000-00000000000a","seq":3,"occurredAt":"2026-11-20T02:00:03Z","payload":{"participantId":"e1","stageStatus":"PREPARING","publicFocus":"关注关键取舍"}}

id: 10
event: utterance.added
data: {"discussionId":"00000000-0000-4000-8000-00000000000a","seq":10,"occurredAt":"2026-11-20T02:00:10Z","payload":{"seq":3,"participantId":"e2","kind":"EXPERT","content":"A 也应通过小流量验证首次解决率。核心编排可先自研。","replyToUtteranceSeq":2,"createdAt":"2026-11-20T02:00:10Z"}}

id: 17
event: insight.updated
data: {"discussionId":"00000000-0000-4000-8000-00000000000a","seq":17,"occurredAt":"2026-11-20T02:00:17Z","payload":{"insightStatus":"STALE","insightsThroughUtteranceSeq":3,"insights":[{"id":"i1","kind":"CONSENSUS","text":"先用小流量验证首次解决率","evidenceUtteranceSeqs":[2,3]}]}}

```

#### 失败响应示例

`400` 房间 ID 或有效游标不合法，`403` Host 拒绝，`404` 房间不存在，`429` 订阅配额已满，`500` 握手阶段发生本地异常。握手失败使用 JSON。流建立后，传输异常关闭连接，讨论失败则发布 `FAILED` 事件。

HTTP `429` 示例。

```json
{"code":429,"message":"容量已满，请稍后重试","data":null,"timestamp":"2026-11-20T02:00:45Z"}
```

#### SSE 事件类型

- `discussion.status` 的 `payload` 包含 `status`、`startedAt`、`endedAt`、`errorCode`。这些字段由服务端根据确认、收束、故障或总结生成，和状态变更同事务提交。终态事件位于其事务事件序列的最后。前端应用有效迁移后，在终态关闭连接。
- `participant.status` 的 `payload` 包含 `participantId`（字符串）、`stageStatus`（`StageStatus`）和 `publicFocus`（`PublicFocus` 或 `null`）。R02 提供合法的 `focusCode`，服务端绑定房间内的专家，并按实际准备、获得调用槽、发言和清理阶段更新。前端用已知专家 ID 更新小窗。
- 同一事件可带 `publicSummary`（1–80 个 Unicode 码点或 `null`），仅用于显示应用审核后发布的公开说明。待机时为空。可带 `publicSpeech`（1–120 个 Unicode 码点或 `null`），仅在 `SPEAKING` 时传送已审核、已提交的当前公开口播前缀。连续更新用同一角色的 `SPEAKING` 事件追加前缀并推进公开事件序号。小窗以 `utterance.added` 的完整正文定格，只有该事件追加实录。两个字段可省略，语义等同于 `null`。禁止从模型隐藏推理、报名候选或未审核 Token 填充这两个字段。
- `utterance.added` 的 `payload` 为 `Utterance`。R03、R05、R06、R07 提供正文候选，R02、R06 提供应答目标候选。服务端校验本场引用、生成发言人、发言号、类型与时间，并与发言原子提交。前端核对发言人、连续序号及引用后追加纯文本实录。收到 `SUMMARY` 时在总结区显示主持对专家观点的归因整理。R07 的内部证据数组由服务端校验。
- `insight.updated` 的 `payload` 包含 `insightStatus`（`FRESH` 或 `STALE`）、`insightsThroughUtteranceSeq`（非负整数）和 `insights`（`Insight[]`）。R04 提供观点和证据候选。服务端校验归属、原文和版本后生成观点 ID 及来源游标。前端按事件替换完整投影。收到 `STALE` 时，继续展示旧条目、证据和来源游标，并提示暂缓更新。

服务端生成信封中的房间 ID、序号、时间和 SSE ID，且只发送已提交事件。回滚或未提交的写入没有公开帧。模型候选附加 `roomId`、`discussionId`、状态、时间或游标时，按运行时 Schema 拒绝。R08 如可重试，仍复用原任务契约。 [模型运行时契约](./runtime-contract.schema.json) · [事务规则](./schema.md#状态机与原子写入)

#### 回放与重连

1. 先读快照，再用 `after=<lastEventSeq>` 订阅。读快照后、建立连接前产生的事件也会补齐。服务端按房间查询 `seq > cursor`，以序号升序回放，并沿用同一路径接续实时事件。
2. `Last-Event-ID` 存在时优先使用该头，`after` 不参与游标解析。非法的头直接返回 `400`。头缺省时才读取 `after`。游标须为单个规范十进制安全整数，范围从 0 到当前房间的最新已提交序号。空串、负数、前导零、非数字、重复的有效入参或超过上界的数均返回 `400`。
3. 前端核对当前路由、监听令牌、房间和帧结构，再比较 SSE ID 与 `data.seq`。同一会话的重复事件丢弃。序号缺失、帧结构错误或 ID 不一致时关闭旧流、重新取快照并重订阅。成功应用事件后才推进本地游标。切房时取消旧快照请求、更换监听令牌并关闭旧连接。
4. 服务端校验 Host、有来源限制的请求头、路径和游标后，读取房间并检查游标上界、终态和容量。默认最多 16 条全局连接，每房最多 8 条，满时返回 `429`。断开、异常、超时和终态关闭都释放配额。浏览器的 EventSource 错误回调只提供连接反馈。短时断线优先使用原生重连，持续失败时重取快照并有界重订阅，超过重试上限后显示手动恢复入口。
5. 终态快照直接展示。终态房间的旧游标可继续回放，仍受订阅容量限制。游标已追平时返回空 `204`，不占槽。总结与完成同事务写入，流中先发总结，再发完成状态并关闭。失败快照保留已提交实录。

#### A/B 联调时序

```mermaid
sequenceDiagram
    participant C1 as 客户端 C1
    participant S as HTTP/SSE 服务
    participant C2 as 客户端 C2
    C1->>S: 读取 A 快照
    S-->>C1: A 游标 2
    C2->>S: 读取 B 快照并从 2 订阅
    Note over S: A 提交事件 3
    C1->>S: 订阅 A，after=2
    S-->>C1: 回放 A3，随后 A4
    Note over C1,S: C1 断线，A 提交 5 至 7
    S-->>C2: B3 至 B7
    C1->>S: 订阅 A，after=2，Last-Event-ID=4
    S-->>C1: A5 至 A7，再实时接收 A8 至 A17
    C2->>S: 切换到 A，读取游标 17
    Note over C2: 丢弃旧 B 回调
    Note over S: A19 总结与 A20 完成同事务提交
    S-->>C1: A18、A19、A20 后关闭
    C2->>S: 缺号恢复后读取 A20 完成快照
```

- C1 读取 A2 后，服务端提交 A3。C1 从游标 2 补到 A3，C2 独立收到 B3 至 B7。
- C1 应用 A4 后丢弃重复 A4，断线时以 `after=2`、`Last-Event-ID=4` 重连，从 A5 开始回放。游标 A999 返回 400，订阅配额已满返回 429。
- C2 切到 A17 后丢弃旧房间和旧监听器的回调。漏掉 A18 又收到 A19 时，重新获取一致快照。
- A19 和 A20 暂存后回滚时没有可见帧，成功提交后顺序发出。A18 游标可回放 A19、A20。A20 或 B9 游标得到 204，B9 失败快照仍保有实录。 [联调样例](./contracts/api.fixtures.json)

### 3.8 管理台接口

后台管理页使用，仅本机可访问（沿用 Host/Origin 白名单）。返回的不是大厅列表，而是**全部状态**的房间与记录量。

- **请求方法** `GET`
- **接口路径** `/admin/overview`、`/admin/discussions`、`/admin/discussions/{id}`
- **接口描述** 分别返回各状态房间数量与全库记录量、房间行（带发言/结论/事件计数）、房间详情（完整快照 + 事件流）。

#### 请求参数

- `/admin/discussions` 可选 Query：
  - `status`：`ALL`（默认）或 `DRAFT`、`RUNNING`、`SUMMARIZING`、`COMPLETED`、`FAILED`，大小写不敏感；其他取值返回 `400`。
  - `keyword`：议题子串，最多 60 个 Unicode 码点；按 NFKC 归一与小写比较，空串视为不过滤；超长返回 `400`。
  - `date`：`yyyy-MM-dd`，按服务器本地时区匹配房间创建日期，已开始的房间同时匹配开始日期；格式或日期非法返回 `400`。三个参数可任意组合。
- `/admin/discussions/{id}` 的路径参数为 UUID，非法返回 `400`，不存在返回 `404`。

#### 成功响应示例

`GET /api/v1/admin/overview`，HTTP `200`。

```json
{
  "code": 200,
  "message": "成功",
  "data": {
    "discussionCounts": {"DRAFT": 1, "RUNNING": 0, "SUMMARIZING": 0, "COMPLETED": 2, "FAILED": 0},
    "totalDiscussions": 3,
    "presetCount": 6,
    "utteranceCount": 16,
    "insightCount": 4,
    "eventCount": 68
  },
  "timestamp": "2026-11-20T13:24:05Z"
}
```

`GET /api/v1/admin/discussions?status=COMPLETED&keyword=客服&date=2026-11-20` 的 `data[]` 每项字段：

| 字段 | 说明 |
| --- | --- |
| `id`、`topic`、`discussionGoal`、`expertCount`、`status` | 房间基本信息 |
| `createdAt`、`startedAt`、`endedAt`、`errorCode` | 时间与失败码，未发生时为 `null` |
| `lastEventSeq` | 已提交事件的最新序号 |
| `participantCount`、`utteranceCount`、`insightCount`、`eventCount` | 各表记录数 |

`GET /api/v1/admin/discussions/{id}` 返回 `{snapshot, events}`：`snapshot` 与 3.6 的房间快照结构一致；`events[]` 每项为 `{seq, type, createdAt, payload}`，`payload` 是完整的公开事件信封（含 `discussionId`、`seq`、`occurredAt`、`payload`）。

#### 失败响应示例

`400` 状态取值非法或房间标识格式错误，`404` 房间不存在，`500` 本地存储异常。

```json
{"code":400,"message":"请求参数无效","data":null,"timestamp":"2026-11-20T13:24:05Z"}
```
### 3.9 删除中断讨论

`DELETE /api/v1/discussions/{id}` 接受房间 UUID，无请求体。只允许删除处于 `FAILED` 的房间。成功返回 `204`，响应体为空。服务端在一个事务内删除房间、嘉宾、实录、结论及引用、公开事件。

UUID 无效返回 `400`，来源不受允许返回 `403`，房间不存在或已删除返回 `404`，房间处于其他状态返回 `409`。

```shell
curl -i -X DELETE http://127.0.0.1:8080/api/v1/discussions/00000000-0000-4000-8000-00000000000a
```

## 四、附录

### 4.1 错误码字典

- `400`。路径参数、查询参数、请求体或有效游标不合法。要求零字节请求体的接口提交 `{}` 或 `null` 也按此处理。
- `403`。任意入口的 `Host` 不在允许范围内，或写请求携带了未允许的 `Origin`。
- `404`。格式合法的房间 ID 或预设标识找不到记录。
- `409`。确认开始时房间已经处于终态。
- `429`。讨论执行槽或 SSE 全局、单房连接配额耗尽。
- `500`。本地存储、任务派发或其他系统操作失败，可用于异步失败或重启恢复。
- `502`。新建阵容时上游调用失败，或其输出通过不了校验。
- `503`。新建讨论或首次确认所需的模型配置缺失。
- `504`。新建阵容时上游调用超时。
- 运行中的讨论通过 `FAILED` 快照和 `discussion.status.errorCode` 表示异步失败。`DiscussionErrorCode` 取 `500`、`502` 或 `504`。

### 4.2 枚举值说明

#### 讨论状态

- `DRAFT` · 草稿。`startedAt`、`endedAt`、`errorCode`、`summary` 均为 `null`。
- `RUNNING` · 讨论中。提供 `startedAt`，其余三项为 `null`。
- `SUMMARIZING` · 正在总结。提供 `startedAt`，其余三项为 `null`。
- `COMPLETED` · 已完成。提供 `startedAt`、`endedAt` 和两句 `summary`，`errorCode` 为 `null`。
- `FAILED` · 已失败。提供 `startedAt`、`endedAt` 和 `errorCode`，`summary` 为 `null`。
- 合法迁移为 `DRAFT → RUNNING → SUMMARIZING → COMPLETED`，`RUNNING` 和 `SUMMARIZING` 也可进入 `FAILED`。 [状态机](./schema.md#状态机与原子写入)

#### 讨论目标与嘉宾

- `DiscussionGoal` 支持 `EVALUATE`（评估）、`PROPOSE`（提案）、`SOLVE`（排查）、`EXPLORE`（探索）。
- `role` 支持 `HOST`（主持）和 `EXPERT`（专家）。主持界定讨论范围、厘清分歧、请各方举证及溯源并推进阶段。实质立场、假设、业务判断和建议由专家提出。
- 专家的 `Perspective` 支持 `EVIDENCE`（证据）、`DELIVERY`（交付）、`COST`（成本）、`RISK`（风险）、`USER_IMPACT`（用户影响）、`GOVERNANCE`（治理）。同场专家视角互异，主持为 `null`。
- `StageStatus` 支持 `IDLE`（待机）、`PREPARING`（准备发言）、`SPEAKING`（发言中）。主持始终为 `IDLE`，待机时 `publicFocus` 为 `null`。
- `PublicFocus` 取「关注关键取舍」「检验前提假设」「关注证据依据」「关注风险与边界条件」「关注影响范围」「关注实施路径」之一。可空字段使用 `null`。

#### 发言与观点

- `Utterance.kind` 支持 `OPENING`（开场）、`EXPERT`（专家发言）、`FOLLOW_UP`（追问）、`BRIDGE`（串联）、`SUMMARY`（总结）。
- `Insight.kind` 支持 `CONSENSUS`（共识）和 `DISAGREEMENT`（分歧）。
- `InsightStatus` 支持 `PENDING`（尚未提炼）、`FRESH`（已更新）、`STALE`（更新暂缓）。成功投影保留本次完整结果，证据不足时可为空。更新暂缓时保留旧观点、证据及来源游标，SSE 事件序号仍可递增。

### 4.3 响应对象字典

**PresetParticipant**

- `id`、`displayName`、`profession`、`title`、`colorKey` 均为字符串，依次表示成员 ID、姓名、职业、职称和色标。
- `role` 为角色枚举。`stance` 和 `perspective` 都是必填响应字段，主持均返回 `null`。专家的 `stance` 为 8–100 个 Unicode 码点的立场文本，`perspective` 为视角枚举。前端仅给专家展示立场和视角。

**Participant**

- 包含 `PresetParticipant` 的所有字段。
- `stageStatus` 为嘉宾状态枚举，`publicFocus` 为关注标签枚举或 `null`。
- 可选 `publicSummary` 与 `publicSpeech` 延续上方事件的取值，以便连接恢复时显示当前公开状态。

**Utterance**

- `seq`（正整数）为房间内发言序号。 `participantId`（字符串）为本场发言人 ID。 `kind` 为发言类别枚举。
- `content`（字符串）为公开正文，普通发言最多 120 个 Unicode 码点，总结最多 200 个。 `createdAt`（时间字符串）为入库时间。
- `replyToUtteranceSeq`（正整数或 `null`）指向本场较早的已提交发言。R02 的 `ADD` 和 `REBUT` 由服务端锁定目标并传给 R03。R06 的 `BRIDGE` 必须引用本次输入中的专家发言，`FOLLOW_UP` 可为空，其中有目标时执行同样的归属校验。开场、总结和无回应目标的专家发言填写 `null`。

**Insight**

- `id`（字符串）为本场观点 ID。 `kind` 为观点分类枚举。 `text`（字符串）为观点正文。
- `evidenceUtteranceSeqs`（正整数数组）包含 2–4 个互异发言序号。引用来自本场至少两位不同专家，且不得超过 `insightsThroughUtteranceSeq`。

### 4.4 联调资料

- 公开 JSON Schema 声明请求、响应和事件结构。第 3.1 至 3.6 节依次使用 `listDiscussions`、`createDiscussion`、`listPresets`、`copyPreset`、`startDiscussion`、`getDiscussion`，取对应名称加 `Request` 或 `Response` 的 `$defs`。请求按 `params`、`query`、`headers`、`body` 拆解。第 3.7 节使用 `streamDiscussionRequest` 和 `sseFrame`，SSE 帧先解析成 `{event,id,data}` 再校验。错误响应使用 `httpError`。 [公开 Schema](./contracts/public-api.schema.json)
- 联调样例包含成功、失败和 A/B 双房间回放，用于复现契约场景。校验脚本使用 Node.js、Ajv 和 `ajv-formats`，在项目根目录运行 `node docs/contracts/verify.cjs`。如模块安装在独立目录，可依次传入 Ajv、`ajv-formats` 的安装路径。 [联调样例](./contracts/api.fixtures.json) · [校验脚本](./contracts/verify.cjs)
- T06 覆盖请求、快照和公开事件结构、内部字段泄露、模型附加字段和事务回滚。E02 覆盖双房间隔离。E04 覆盖回放、缺号恢复、换房和终态。E05 覆盖容量、失败快照及错误码。上述场景可用样例机械校验，应用联调须连接实际后端与浏览器。
