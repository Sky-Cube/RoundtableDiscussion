# AI Panel Studio · 前端

Vue 3 + TypeScript + Three.js 的 AI 圆桌演播厅前端。前端开发服务器默认监听 `http://127.0.0.1:5173`，并把 `/api/v1` 代理到后端的 `8080` 端口。

## 运行

```bash
npm install

# 对接真实后端：Vite 把 /api/v1 代理到 127.0.0.1:8080
npm run dev
# 与 dev 相同，语义更明确的后端联调入口
npm run dev:backend
# http://127.0.0.1:5173

# 仅用于浏览器自动化测试：启用内存 fixture API，不代表真实产品行为
npm run dev:test-fixture
```

`dev:test-fixture` 使用 `tests/fixtures/` 下的 fixture 插件模拟 HTTP/SSE，只服务于 Playwright 用例；它不是产品演示入口，也不应作为日常开发方式。

后端使用自定义端口时，在启动 Vite 的进程环境中设置 `PANEL_BACKEND_URL`，例如 `http://127.0.0.1:18081`。该变量供 Vite 代理读取。

```bash
npm run typecheck
npm test
npm run build
npm run test:browser   # 需要 Playwright 浏览器与 test-fixture 服务；也可 PLAYWRIGHT_CHANNEL=chrome
```

## 页面

- `/`：讨论大厅。输入议题、选择专家人数、从预设议题创建草稿，并旁观进行中的讨论。
- `/discussions/:id/roster`：阵容预览与开场确认。
- `/discussions/:id`：演播厅现场、成员状态、即时结论、完整实录与最终结果。
- `/admin`：后台管理台。读取真实后端的进行中讨论、预设议题，支持新建草稿、复制预设为草稿，并展开查看房间快照。

后台管理台的数据全部来自后端接口，前端不内置任何房间、预设或成员样例。

## 目录

```text
src/
  views/LobbyView.vue            大厅：议题、人数、进行中房间、示例
  views/DiscussionView.vue       阵容预览与开场确认
  views/StudioView.vue           演播厅、成员状态、结论、实录、最终弹窗
  views/AdminView.vue            后台管理台（/admin）
  views/admin.css                管理台专属样式，仅由 AdminView 引入
  components/studio/             Three.js 场景、人物、头像、最终弹窗
  api/                           后端 HTTP/SSE 契约
tests/
  fixtures/fixture-api.ts        仅测试用的 SSE/HTTP 模拟服务
  fixtures/agenda.ts             仅测试用的非轮询发言顺序样例
  discussion-flow.spec.ts        大厅与阵容
  studio-session.spec.ts         会话、状态、结论、最终弹窗关闭
  agenda.spec.ts                 发言调度样例
  browser/                       Playwright 三视口流程
```

## 与后端的边界

`src/api/`、SSE 事件名和公开 DTO 保持不变。真实后端只需要按现有契约推送 `participant.status`、`utterance.added`、`insight.updated`、`discussion.status`；发言顺序由服务端决定，前端不会把座位顺序当作发言顺序。最终结果弹窗只读取公开的 `summary` 与 `insights`。

## 近期前端改造

- **大厅与阵容视觉统一**：冷灰蓝演播厅底色、玻璃面板、深色主按钮、Manrope + Instrument Serif 的编辑部式排版；大厅、阵容预览、演播厅使用同一套设计系统。
- **自由发言节奏**：演示/测试用 fixture 的发言议程不按座位轮询，专家可连续接话、被主持人串联后跳回；前端完全按 SSE 事件顺序渲染，不假设轮次。演播厅会显示「自由发言 · 非轮询」。
- **最终结果弹窗**：讨论到达 `COMPLETED` 后自动弹出本轮结论；支持关闭、Esc、点击遮罩、查看完整实录、证据定位，以及关闭后从页头重新打开。
- **更有辨识度的虚拟形象**：六种形象，发型、眼镜、领带、水手领、耳机/耳饰、肤色和坐姿动作由职业与座位色标决定。演播厅中人物会呼吸、眨眼、看向发言人，并在发言时做轻微手势。
- **响应式**：1920 / 1366 使用左侧常驻成员状态栏；≤959px 改为「现场 / 嘉宾 / 结论」标签页，完整实录变为底部抽屉，最终结果弹窗在窄屏可滚动。
- **后台管理台**：真实接口驱动的运行状态、草稿创建与预设复制，状态与错误使用 `role="status"` / `role="alert"` 播报。
- **可用性**：保留原有键盘导航与焦点管理；最终结果使用原生 `dialog`，关闭后焦点回到「查看最终结果」；降低动效偏好会停止 3D 呼吸/手势循环。
