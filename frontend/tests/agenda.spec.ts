import { describe, expect, it } from 'vitest'
import { agendaFor, isNonSequential } from '../dev/agenda'

describe('fixture speaking agenda', () => {
  it.each([2, 3, 4, 5, 6])('covers every expert for %i experts', count => {
    const agenda = agendaFor(count)
    expect(new Set(agenda).size).toBe(count)
    expect(agenda.length).toBeGreaterThanOrEqual(count)
  })

  it.each([4, 5, 6])('does not rotate mechanically through the roster for %i experts', count => {
    const agenda = agendaFor(count)
    expect(isNonSequential(agenda, count)).toBe(true)
    expect(agenda.slice(0, 3)).not.toEqual([0, 1, 2])
  })
})
