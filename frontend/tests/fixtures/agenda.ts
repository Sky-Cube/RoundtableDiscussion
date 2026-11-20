/**
 * Demo-only speaking order. It intentionally does not follow the roster order:
 * the point is to exercise the frontend with free-form, non-round-robin turns.
 * A real backend can choose its own order; the frontend does not depend on this.
 */
const orderByCount: Record<number, number[]> = {
  2: [1, 0, 1, 0],
  3: [2, 0, 1, 2, 0],
  4: [2, 0, 3, 1, 2, 0],
  5: [2, 4, 0, 3, 1, 4, 2],
  6: [3, 0, 5, 1, 4, 2, 0, 3],
}

export function agendaFor(count: number): number[] {
  if (orderByCount[count]) return [...orderByCount[count]]
  return Array.from({ length: count * 2 }, (_, index) => count - 1 - (index % count))
}

export function isNonSequential(order: number[], count: number): boolean {
  if (order.length < 3) return false
  const covered = new Set(order)
  for (let index = 2; index < order.length; index += 1) {
    if (order[index] === order[index - 1] && order[index] === order[index - 2]) return false
  }
  return covered.size === count && order.some((value, index) => index > 0 && value !== (order[index - 1]! + 1) % count)
}
