import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import {
  quantity,
  businessDate,
  timestamp,
  identity,
  documentRef,
} from '../presentation'
import ResultPager from '../components/ResultPager.vue'

describe('human-readable values without changing business meaning', () => {
  it('preserves decimal precision, negative differences, zero and missing values', () => {
    expect(quantity('999999999999.123456')).toBe('999999999999.123456')
    expect(quantity('-0.000001')).toBe('-0.000001')
    expect(quantity('12.340000')).toBe('12.34')
    expect(quantity('0.000000')).toBe('0')
    expect(quantity(null)).toBe('—')
    expect(quantity(undefined)).toBe('—')
  })
  it('keeps business dates intact and audit times independent of browser timezone', () => {
    expect(businessDate('2026-10-11')).toBe('2026-10-11')
    expect(businessDate('2026-10-10T16:00:00Z')).toBe('日期待核对')
    expect(timestamp('2026-10-10T16:01:02Z')).toBe(
      '2026-10-11 00:01:02（北京时间）',
    )
    expect(timestamp('2026-10-11 00:01:02')).toContain('时区未提供')
    expect(timestamp(null)).toBe('—')
  })
  it('uses both name and code and qualifies otherwise ambiguous references', () => {
    expect(identity('原料 A', 'M01')).toBe('原料 A（M01）')
    expect(identity(null, 'M01')).toBe('M01')
    expect(identity()).toBe('名称未提供')
    expect(documentRef('销售订单', 'SO-00000001')).toBe('销售订单 SO-00000001')
    expect(documentRef('销售出库', 'SO-1')).toBe('销售出库 SO-1')
  })
  it('uses server result total and prevents page requests outside the range', async () => {
    const v = mount(ResultPager, { props: { total: 21, page: 0, size: 20 } })
    expect(v.text()).toContain('共 21 条 · 第 1 / 2 页')
    await v.findAll('button')[0]!.trigger('click')
    expect(v.emitted('change')).toBeUndefined()
    await v.findAll('button')[1]!.trigger('click')
    expect(v.emitted('change')).toEqual([[1]])
    await v.setProps({ page: 1 })
    expect(v.findAll('button')[1]!.attributes('disabled')).toBeDefined()
  })
})
