import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import View from '../views/FinanceView.vue'
import { ApiError, request } from '../api'
import { lineAmount } from '../finance'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const base = '/api/v1/finance'
const basis = {
  id: 1,
  number: 'FB-00000001',
  direction: 'PAYABLE',
  warehouse_id: 1,
  order_number: 'PO-00000001',
  fact_number: 'RC-01',
  fact_id: 3,
  party_name: '供应商甲',
  material_code: 'M-01',
  material_name: '材料',
  unit: '件',
  quantity: '60.000000',
  unit_price: '0.333333',
  amount: '20.00',
  reference: '演示结算01',
  business_date: '2026-10-12',
  reason: '验收',
  status: 'PENDING',
  version: 1,
  created_by: 'maker',
  open_amount: null,
  sourceMatches: true,
  balanceMatches: true,
  history: [],
}
const initial = {
  status: 'ACTIVE',
  version: 2,
  organization: '演示组织',
  start_date: '2026-01-01',
  end_date: '2026-12-31',
  created_by: 'maker',
  history: [],
}
const fact = {
  orderId: 1,
  orderNumber: 'PO-00000001',
  factId: 3,
  factNumber: 'RC-01',
  warehouseId: 1,
  partyName: '供应商甲',
  materialCode: 'M-01',
  quantity: '60.000000',
  unit: '件',
}
const button = (v: VueWrapper, name: string) =>
  v.findAll('button').find((b) => b.text() === name)!
function setup(username = 'checker', role = 'review') {
  return mount(View, {
    props: {
      username,
      authorities: ['finance:read', `finance:${role}`, 'wms:warehouse:1'],
    },
  })
}
beforeEach(() => {
  sessionStorage.clear()
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.startsWith('/api/master-data/warehouses'))
      return { items: [{ id: 1, code: 'W1', name: '原料仓' }], totalPages: 1 }
    if (path === base + '/baseline') return structuredClone(initial)
    if (path.startsWith(base + '/sources')) return [structuredClone(fact)]
    if (path === base + '/bases/1') return structuredClone(basis)
    return { items: [structuredClone(basis)], total: 1 }
  })
})
it('uses exact decimal arithmetic and HALF_UP instead of binary floats', () => {
  expect(lineAmount('60', '0.333333')).toBe('20.00')
  expect(lineAmount('1', '0.005')).toBe('0.01')
  expect(lineAmount('999999999999', '0.01')).toBe('9999999999.99')
  expect(lineAmount('1', '0.0000001')).toBe('')
})
it('shows unconfirmed amounts distinctly and preserves source identities', async () => {
  const v = setup()
  await flushPromises()
  await button(v, 'FB-00000001').trigger('click')
  await flushPromises()
  expect(v.text()).toContain('尚未确认往来')
  expect(v.text()).toContain('PO-00000001')
  expect(v.text()).toContain('来源核对：一致')
  expect(v.text()).toContain('20.00 CNY')
  v.unmount()
})
it('prevents self-review and hides write operations for readers', async () => {
  let v = setup('maker')
  await flushPromises()
  await button(v, 'FB-00000001').trigger('click')
  await flushPromises()
  expect(button(v, '确认往来').attributes('disabled')).toBeDefined()
  v.unmount()
  v = setup('reader', 'read')
  await flushPromises()
  await button(v, 'FB-00000001').trigger('click')
  await flushPromises()
  expect(button(v, '确认往来')).toBeUndefined()
  expect(button(v, '取消依据')).toBeUndefined()
  v.unmount()
})
it('keeps the exact approval request after lost response and shows committed result on retry', async () => {
  const v = setup()
  await flushPromises()
  await button(v, 'FB-00000001').trigger('click')
  await flushPromises()
  await v.get('[aria-label="本次操作依据"]').setValue('核对一致')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(0, '响应丢失'))
  await button(v, '确认往来').trigger('click')
  await flushPromises()
  const saved = JSON.parse(
    sessionStorage.getItem('mdop-finance-pending:checker')!,
  )
  expect(saved.url).toBe(base + '/bases/1/approve')
  expect(v.text()).toContain('结果待核对')
  expect(button(v, '确认往来').attributes('disabled')).toBeDefined()
  vi.mocked(request).mockResolvedValueOnce({
    ...basis,
    status: 'CONFIRMED',
    open_amount: '20.00',
    version: 2,
  })
  await button(v, '原样重试').trigger('click')
  await flushPromises()
  expect(vi.mocked(request).mock.lastCall?.[1]?.body).toBe(saved.body)
  expect(sessionStorage.getItem('mdop-finance-pending:checker')).toBeNull()
  expect(v.text()).toContain('已确认')
  v.unmount()
})
it('clears previous detail on read failure and ignores late responses after unmount', async () => {
  const v = setup()
  await flushPromises()
  await button(v, 'FB-00000001').trigger('click')
  await flushPromises()
  await button(v, '← 返回列表').trigger('click')
  await flushPromises()
  vi.mocked(request).mockRejectedValueOnce(new ApiError(503, '读取失败'))
  await button(v, 'FB-00000001').trigger('click')
  await flushPromises()
  expect(v.text()).toContain('详情读取失败')
  expect(button(v, '确认往来')).toBeUndefined()
  let resolve!: (v: unknown) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  await button(v, 'FB-00000001').trigger('click')
  v.unmount()
  resolve(basis)
  await flushPromises()
  expect(sessionStorage.length).toBe(0)
})
it('requires a fresh source lookup after changing the order number', async () => {
  const v = setup('maker', 'write')
  await flushPromises()
  await v.get('[aria-label="业务订单号"]').setValue('PO-00000001')
  await button(v, '查询收发事实').trigger('submit')
  await flushPromises()
  expect(v.find('[aria-label="收发事实"]').exists()).toBe(true)
  await v.get('[aria-label="业务订单号"]').setValue('PO-00000002')
  expect(v.find('[aria-label="收发事实"]').exists()).toBe(false)
  v.unmount()
})
