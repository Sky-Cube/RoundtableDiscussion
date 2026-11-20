import { expect, test } from '@playwright/test'
import type { Locator, Page, Request } from '@playwright/test'
import fixture from '../../../docs/contracts/api.fixtures.json' with { type: 'json' }

async function tabTo(page: Page, element: Locator) {
  for (let step = 0; step < 24; step++) {
    await page.keyboard.press('Tab')
    if (await element.evaluate(node => node === document.activeElement)) return
  }
  await expect(element).toBeFocused()
}

async function checkLayout(page: Page) {
  const dimensions = await page.locator('main').evaluate(main => ({
    clientWidth: main.clientWidth,
    scrollWidth: main.scrollWidth,
    overflowY: getComputedStyle(main).overflowY,
    documentScroll: document.documentElement.scrollHeight - window.innerHeight,
  }))
  expect(dimensions.scrollWidth).toBe(dimensions.clientWidth)
  expect(dimensions.overflowY).toBe('auto')
  expect(dimensions.documentScroll).toBe(0)
}

test('用键盘生成阵容、确认开场和旁观同一个已有房间', async ({ page }, testInfo) => {
  const requests: Request[] = []
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/')) requests.push(request)
  })

  await page.goto('/')
  await expect(page.getByRole('link', { name: /旁观/ }).filter({ hasText: fixture.snapshots.B2.topic })).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
  await checkLayout(page)
  await page.screenshot({ path: testInfo.outputPath('lobby.png') })

  await page.keyboard.press('Tab')
  await expect(page.getByLabel('讨论议题')).toBeFocused()
  const topic = '中小团队如何评估新客服流程？'
  await page.keyboard.insertText(topic)
  await page.keyboard.press('Tab')
  await expect(page.getByLabel('专家人数')).toBeFocused()
  await expect(page.getByLabel('专家人数')).toHaveValue('4')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: '生成专家阵容' })).toBeFocused()
  await page.keyboard.press('Enter')

  await expect(page).toHaveURL(/\/discussions\/[0-9a-f-]+\/roster$/)
  const roomPath = new URL(page.url()).pathname.replace(/\/roster$/, '')
  await expect(page.getByRole('heading', { name: topic })).toBeVisible()
  await expect(page.locator('.expert-grid article')).toHaveCount(4)
  expect(requests.filter(request => request.method() === 'POST')
    .map(request => new URL(request.url()).pathname)).toEqual(['/api/v1/discussions'])
  await expect(page.getByRole('main')).toBeFocused()
  await checkLayout(page)
  await page.screenshot({ path: testInfo.outputPath('roster.png') })

  if (testInfo.project.name === '390x844') {
    const main = page.getByRole('main')
    await page.keyboard.press('PageDown')
    await expect.poll(() => main.evaluate(node => node.scrollTop)).toBeGreaterThan(0)
    await expect(page.getByRole('banner')).toBeInViewport()
    await page.keyboard.press('PageUp')
    await expect.poll(() => main.evaluate(node => node.scrollTop)).toBe(0)
  }

  await tabTo(page, page.getByRole('button', { name: '开始讨论' }))
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(roomPath + '$'))
  await expect(page.getByText('讨论中', { exact: true })).toBeVisible()
  const start = requests.filter(request => request.method() === 'POST' && request.url().endsWith('/start'))
  expect(start).toHaveLength(1)
  expect(new URL(start[0].url()).pathname).toBe('/api/v1' + roomPath + '/start')
  expect(start[0].postData()).toBeNull()
  await expect(page.getByRole('button', { name: '开始讨论' })).toHaveCount(0)

  await tabTo(page, page.getByRole('link', { name: '返回大厅' }))
  await page.keyboard.press('Enter')
  const existingRoom = page.getByRole('link', { name: /旁观/ }).filter({ hasText: fixture.snapshots.B2.topic })
  await expect(existingRoom).toBeVisible()
  const observationStart = requests.length
  await tabTo(page, existingRoom)
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp('/discussions/' + fixture.rooms.B + '$'))
  await expect(page.getByRole('heading', { name: fixture.snapshots.B2.topic })).toBeVisible()
  await expect(page.getByText('实时连接')).toBeVisible()
  expect(requests.slice(observationStart).map(request => [request.method(), new URL(request.url()).pathname]))
    .toEqual([['GET', '/api/v1/discussions/' + fixture.rooms.B], ['GET', '/api/v1/discussions/' + fixture.rooms.B + '/events']])
})
