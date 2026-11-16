import { describe, expect, it } from 'vitest'
import { remaining, scaled } from '@/shared/quantity'

it('preserves six decimal places near the maximum inventory quantity', () => {
  expect(remaining('999999999999.999999', '999999999999.999998')).toBe(
    '0.000001',
  )
  expect(remaining('100.000000', '60.000000')).toBe('40')
  expect(scaled('999999999999.999999')).toBe(999999999999999999n)
})

describe('scaled', () => {
  it('将整数与小数转成六位定点整数', () => {
    expect(scaled('1')).toBe(1000000n)
    expect(scaled('1.5')).toBe(1500000n)
    expect(scaled('0.000001')).toBe(1n)
    expect(scaled(2)).toBe(2000000n)
  })

  it('保留负号（BigInt("-0") 会丢符号）', () => {
    expect(scaled('-0.5')).toBe(-500000n)
    expect(scaled('-2.25')).toBe(-2250000n)
  })

  it('小数超过六位时截断而不溢出', () => {
    expect(scaled('0.1234567')).toBe(123456n)
  })
})

describe('remaining', () => {
  it('正常相减并去掉多余的零', () => {
    expect(remaining('10', '3.5')).toBe('6.5')
    expect(remaining('1', '1')).toBe('0')
    expect(remaining('0.000002', '0.000001')).toBe('0.000001')
  })

  it('负余数输出合法格式而不是 0.-5', () => {
    expect(remaining('1', '3')).toBe('-2')
    expect(remaining('1', '3.5')).toBe('-2.5')
    expect(remaining('0.1', '0.2')).toBe('-0.1')
  })
})
