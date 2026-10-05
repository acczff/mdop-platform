import { expect, it } from 'vitest'
import { remaining, scaled } from '../quantity'
it('preserves six decimal places near the maximum inventory quantity', () => {
  expect(remaining('999999999999.999999', '999999999999.999998')).toBe(
    '0.000001',
  )
  expect(remaining('100.000000', '60.000000')).toBe('40')
  expect(scaled('999999999999.999999')).toBe(999999999999999999n)
})
