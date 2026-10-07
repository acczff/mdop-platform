import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FreezesView from '../views/FreezesView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
afterEach(() => vi.resetAllMocks())
const row = {
  id: 7,
  balance_id: 9,
  warehouse_id: 1,
  status: 'FROZEN',
  created_by: 'creator',
  material_name: '批次物料',
  batch_no: 'B1',
  frozen_on_hand: '12.123456',
  frozen_available: '10.123456',
  frozen_reserved: '2.000000',
  reason: '风险排查',
}
function setup(username = 'reviewer', authorities = ['ROLE_ADMIN']) {
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method === 'POST') return { ...row, status: 'RELEASED' }
    if (path.includes('/warehouses'))
      return {
        items: [
          { id: 1, name: '原料仓', purpose: 'RAW_MATERIAL' },
          { id: 2, name: '线边仓', purpose: 'LINE_SIDE' },
        ],
        totalPages: 1,
      }
    if (path.includes('/stock?'))
      return {
        items: [
          {
            id: 9,
            material_name: '可冻结物料',
            on_hand_qty: '12',
            production_qty: '0',
            active_freeze_id: null,
          },
          {
            id: 10,
            material_name: '已经冻结',
            on_hand_qty: '12',
            production_qty: '0',
            active_freeze_id: 2,
          },
        ],
        totalPages: 1,
      }
    if (path.includes('?'))
      return { items: [row], page: 0, totalElements: 1, totalPages: 1 }
    return row
  })
  return mount(FreezesView, {
    props: { username, authorities },
    global: { stubs: { RouterLink: true } },
  })
}
it('shows audit snapshots and prevents self approval or missing review permission', async () => {
  const own = setup('creator')
  await flushPromises()
  expect(own.findAll('button').some((b) => b.text() === '审批解冻')).toBe(false)
  own.unmount()
  const reader = setup('reader', ['wms:freeze:read', 'wms:warehouse:1'])
  await flushPromises()
  expect(reader.findAll('button').some((b) => b.text() === '审批解冻')).toBe(
    false,
  )
  expect(reader.findAll('button').some((b) => b.text() === '冻结库存')).toBe(
    false,
  )
  await reader
    .findAll('button')
    .find((b) => b.text() === '审计详情')!
    .trigger('click')
  await flushPromises()
  expect(reader.text()).toContain('12.123456')
  expect(reader.text()).toContain('风险排查')
  expect(reader.find('form').exists()).toBe(false)
})
it('filters line side warehouses and already frozen stock', async () => {
  const w = setup()
  await flushPromises()
  expect(w.find('select').text()).not.toContain('线边仓')
  await w
    .findAll('button')
    .find((b) => b.text() === '冻结库存')!
    .trigger('click')
  await flushPromises()
  expect(w.findAll('select')[1]!.text()).toContain('可冻结物料')
  expect(w.findAll('select')[1]!.text()).not.toContain('已经冻结')
})
it('retains the original write after timeout and prevents context switching', async () => {
  const w = setup()
  await flushPromises()
  await w
    .findAll('button')
    .find((b) => b.text() === '审批解冻')!
    .trigger('click')
  await flushPromises()
  await w.find('textarea').setValue('已复核')
  await w.find('input[type=checkbox]').setValue(true)
  const original = vi.mocked(request).getMockImplementation()!
  let fail = true
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method === 'POST' && fail) {
      fail = false
      throw new Error('超时')
    }
    return original(path, options)
  })
  await w.find('form').trigger('submit')
  await flushPromises()
  expect(w.text()).toContain('超时')
  expect(w.find('fieldset').attributes('disabled')).toBeDefined()
  expect(w.find('select').attributes('disabled')).toBeDefined()
  expect(
    w
      .findAll('button')
      .find((b) => b.text() === '刷新记录')!
      .attributes('disabled'),
  ).toBeDefined()
  await w.find('form').trigger('submit')
  await flushPromises()
  const writes = vi
    .mocked(request)
    .mock.calls.filter((c) => c[1]?.method === 'POST')
  expect(writes).toHaveLength(2)
  expect(writes[0]).toEqual(writes[1])
  expect(w.text()).toContain('FR-7 已解冻')
})
it('retries initial warehouse failure without publishing a partial list', async () => {
  const w = setup()
  const original = vi.mocked(request).getMockImplementation()!
  let fail = true
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path.includes('/warehouses') && fail) {
      fail = false
      throw new Error('仓库失败')
    }
    return original(path, options)
  })
  // The initial mounted request has already started; force a fresh mount with the failing mock.
  w.unmount()
  const retry = mount(FreezesView, {
    props: { username: 'reviewer', authorities: ['ROLE_ADMIN'] },
    global: { stubs: { RouterLink: true } },
  })
  await flushPromises()
  expect(retry.text()).toContain('仓库失败')
  await retry
    .findAll('button')
    .find((b) => b.text() === '刷新记录')!
    .trigger('click')
  await flushPromises()
  expect(retry.text()).toContain('FR-7')
  expect(retry.find('[role=alert]').exists()).toBe(false)
})
it('does not offer approval when a refreshed record has already been released', async () => {
  const w = setup()
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce({ ...row, status: 'RELEASED' })
  await w
    .findAll('button')
    .find((b) => b.text() === '审批解冻')!
    .trigger('click')
  await flushPromises()
  expect(w.text()).toContain('当前状态或身份不允许')
  expect(w.find('form').exists()).toBe(false)
})
it('keeps a completed write successful when refreshing its list fails', async () => {
  const w = setup()
  await flushPromises()
  await w
    .findAll('button')
    .find((b) => b.text() === '审批解冻')!
    .trigger('click')
  await flushPromises()
  await w.find('textarea').setValue('已复核')
  await w.find('input[type=checkbox]').setValue(true)
  vi.mocked(request)
    .mockResolvedValueOnce({ ...row, status: 'RELEASED' })
    .mockRejectedValueOnce(new Error('刷新失败'))
  await w.find('form').trigger('submit')
  await flushPromises()
  expect(w.text()).toContain('FR-7 已解冻')
  expect(w.text()).toContain('刷新失败')
  expect(w.find('form').exists()).toBe(false)
})
