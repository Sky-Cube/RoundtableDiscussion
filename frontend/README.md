# AI Panel Studio · 前端

Vue 3 + TypeScript + Three.js 的 AI 圆桌演播厅前端。后端尚未接入时，可以用内置的 **fixture 模式** 完整演示大厅、阵容、自由发言与最终结论。

## 运行

```bash
npm install

# 无后端演示：Vite 内置 fixture API
npm run dev:fixture
# http://127.0.0.1:5173

# 对接真实后端（默认代理到 127.0.0.1:8080）
npm run dev
```

```bash
npm run typecheck
npm test
npm run build
npm run test:browser   # 需要 Playwright 浏览器；也可 PLAYWRIGHT_CHANNEL=chrome
```

## 本次前端改造

- **大厅与阵容视觉统一**：冷灰蓝演播厅底色、玻璃面板、深色主按钮、Manrope + Instrument Serif 的编辑部式排版；大厅、阵容预览、演播厅使用同一套设计系统。
- **自由发言节奏**：fixture 的发言议程不再按座位轮询，专家可连续接话、被主持人串联后跳回；前端完全按 SSE 事件顺序渲染，不假设轮次。演播厅会显示「自由发言 · 非轮询」。
- **最终结果弹窗**：讨论到达 `COMPLETED` 后自动弹出本轮结论；支持关闭、Esc、点击遮罩、查看完整实录、证据定位，以及关闭后从页头重新打开。
- **更有辨识度的虚拟形象**：在原有低多边形人物上增加了主持人、职场、二次元、创意、学院、休闲六种形象；不同发型、眼镜、领带、水手领、耳机/耳饰、肤色和坐姿动作由职业与座位色标决定。演播厅中人物会呼吸、眨眼、看向发言人，并在发言时做轻微手势。
- **响应式**：1920 / 1366 使用左侧常驻成员状态栏；≤959px 改为「现场 / 嘉宾 / 结论」标签页，完整实录变为底部抽屉，最终结果弹窗在窄屏可滚动。
- **可用性**：保留原有键盘导航与焦点管理；最终结果使用原生 `dialog`，关闭后焦点回到「查看最终结果」；降低动效偏好会停止 3D 呼吸/手势循环。

## 目录

```text
src/
  views/LobbyView.vue            大厅：议题、人数、进行中房间、示例
  views/DiscussionView.vue       阵容预览与开场确认
  views/StudioView.vue           演播厅、成员状态、结论、实录、最终弹窗
  components/studio/             Three.js 场景、人物、头像、最终弹窗
  api/                           后端 HTTP/SSE 契约（未改动）
dev/
  fixture-api.ts                 仅开发用的 SSE/HTTP 模拟服务
  agenda.ts                      非轮询发言顺序样例
tests/
  discussion-flow.spec.ts        大厅与阵容
  studio-session.spec.ts         会话、状态、结论、最终弹窗关闭
  agenda.spec.ts                 发言调度样例
  browser/                       Playwright 三视口流程
```

## 与后端的边界

`src/api/`、SSE 事件名和公开 DTO 保持不变。真实后端只需要按现有契约推送 `participant.status`、`utterance.added`、`insight.updated`、`discussion.status`；发言顺序由服务端决定，前端不会把座位顺序当作发言顺序。最终结果弹窗只读取公开的 `summary` 与 `insights`。
