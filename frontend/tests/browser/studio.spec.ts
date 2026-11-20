import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function measureScroll(page: Page, selector: string) {
  return page.locator(selector).evaluate(element => ({
    x: element.scrollWidth - element.clientWidth,
    y: element.scrollHeight - element.clientHeight,
    current: element.scrollTop,
    overflow: getComputedStyle(element).overflowY,
  }))
}

test('圆桌现场随 SSE 更新，支持键盘切换、证据定位和独立滚动', async ({ page, request }, testInfo) => {
  const narrow = testInfo.project.name === '390x844'
  const expertCount = narrow ? 6 : testInfo.project.name === '1366x768' ? 4 : 2
  const specialTopic = 'AI客服在首轮试点应采用哪一种上线策略，以及如何区分人工复核、规则维护、稳定性检查和用户影响？'
  const topic = (specialTopic + specialTopic + specialTopic).slice(0, 120)
  const created = await request.post('/api/v1/discussions', { data: { topic, expertCount } })
  expect(created.status()).toBe(201)
  const { data: room } = await created.json()
  const accepted = await request.post(`/api/v1/discussions/${room.id}/start`)
  expect(accepted.status()).toBe(202)

  await page.goto(`/discussions/${room.id}`)
  const stage = page.locator('.room-scene')
  await expect(stage.locator('.virtual-seat')).toHaveCount(expertCount + 1)
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(topic)
  await expect(page.getByText('实时连接')).toBeVisible()
  await expect(stage).toHaveAttribute('data-renderer', 'webgl')

  const seatBounds = await stage.locator('.virtual-seat').evaluateAll(elements => elements.map(element => {
    const rect = element.querySelector('.seat-name')!.getBoundingClientRect()
    return { x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom }
  }))
  expect(seatBounds[0].y).toBe(Math.min(...seatBounds.map(seat => seat.y)))
  for (const [index, seat] of seatBounds.entries()) {
    for (const other of seatBounds.slice(index + 1)) {
      expect(seat.right <= other.x || other.right <= seat.x || seat.bottom <= other.y || other.bottom <= seat.y).toBe(true)
    }
  }
  expect((await measureScroll(page, '#main-content')).x).toBe(0)
  await expect(page.getByRole('button', { name: '查看完整实录' })).toHaveAttribute('aria-expanded', 'false')
  await expect(stage.locator('.scene-caption')).toContainText('正在发言', { timeout: 7500 })
  await expect(stage.locator('.seat-bubble--text')).toHaveCount(1)
  await page.screenshot({ path: testInfo.outputPath('live.png') })

  if (narrow) {
    const liveTab = page.getByRole('tab', { name: '现场' })
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
    await page.keyboard.press('Enter')
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: '返回大厅' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(liveTab).toBeFocused()
    await page.keyboard.press('ArrowRight')
    await expect(page.getByRole('tab', { name: '嘉宾' })).toBeFocused()
    await expect(page.getByRole('heading', { name: '圆桌成员状态' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('members.png') })
    await page.keyboard.press('End')
    await expect(page.getByRole('tab', { name: '结论' })).toBeFocused()
  }

  const evidence = page.getByRole('button', { name: '证据 #3' }).first()
  await expect(evidence).toBeVisible({ timeout: 15000 })
  await evidence.focus()
  await page.keyboard.press('Enter')
  if (narrow) await expect(page.getByRole('tab', { name: '现场' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('[data-speech="3"]')).toBeFocused()
  const reply = page.getByRole('button', { name: '回应 #2' })
  await reply.focus()
  await page.keyboard.press('Enter')
  await expect(page.locator('[data-speech="2"]')).toBeFocused()
  await expect(page.locator('[data-speech="2"] p')).not.toBeEmpty()
  await expect.poll(async () => (await measureScroll(page, '.transcript-scroll')).y, { timeout: 12000 }).toBeGreaterThan(0)
  const scroll = await measureScroll(page, '.transcript-scroll')
  expect(scroll.x).toBe(0)
  expect(scroll.overflow).toBe('auto')
  const latest = page.getByRole('button', { name: '回到最新' })
  await latest.focus()
  await page.keyboard.press('Enter')
  await expect.poll(async () => {
    const box = await measureScroll(page, '.transcript-scroll')
    return Math.abs(box.current - box.y)
  }).toBeLessThanOrEqual(1)
  await expect(page.getByRole('banner').first()).toBeInViewport()
  await page.screenshot({ path: testInfo.outputPath('transcript.png') })
})
