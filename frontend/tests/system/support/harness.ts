/**
 * E2E 系统级测试的通用常量与 HTTP 工具。
 *
 * 服务进程由 Playwright 的 webServer 托管，生命周期覆盖全部测试；
 * 临时 SQLite 由 tests/system/run.mjs 在运行前创建、运行后删除。
 */
import { join, resolve } from 'node:path'

/** 运行标识决定证据目录，允许外部注入以便报告追溯。 */
export const RUN_ID = process.env.SYSTEM_RUN_ID ?? 'local'
export const REPO_ROOT = resolve(import.meta.dirname, '../../../..')
export const OUTPUT_DIR = join(REPO_ROOT, 'test-results', 'prompt', 'P10', RUN_ID)

/** 固定端口便于 Vite 代理与 Origin 白名单配置。 */
export const SYSTEM = {
  frontendPort: 5299,
  backendPort: 18081,
  upstreamPort: 18082,
  apiKey: 'system-e2e-synthetic-key',
  get frontendUrl() {
    return 'http://127.0.0.1:' + this.frontendPort
  },
  get backendUrl() {
    return 'http://127.0.0.1:' + this.backendPort
  },
  get upstreamUrl() {
    return 'http://127.0.0.1:' + this.upstreamPort
  },
}

export interface ApiEnvelope<T> {
  code: number | string
  message: string
  data: T
  timestamp: string
}

/** 读取统一响应体，测试中同时断言 HTTP 状态与业务码。 */
export async function apiJson<T>(path: string, init?: RequestInit): Promise<{ status: number; body: ApiEnvelope<T> }> {
  const response = await fetch(SYSTEM.backendUrl + path, init)
  return { status: response.status, body: (await response.json()) as ApiEnvelope<T> }
}

/** 调用模型替身控制面，注入故障或调整节奏。 */
export async function controlUpstream(patch: Record<string, unknown>): Promise<void> {
  const response = await fetch(SYSTEM.upstreamUrl + '/control', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(patch),
  })
  if (!response.ok) throw new Error('模型替身控制失败：' + response.status)
}
