import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SalesView from '../views/SalesView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
function setup(
  status = 'OPEN',
  username = 'picker',
  authorities = ['ROLE_ADMIN'],
) {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return {
        items: [{ id: 1, name: '成品仓', purpose: 'FINISHED_GOODS' }],
        totalPages: 1,
      }
    if (path.includes('/capabilities')) return { enabled: true }
    if (path.includes('/materials')) return []
    if (path.includes('/stock?'))
      return {
        items: [
          {
            id: 3,
            material_id: 1,
            origin_type: 'PRODUCTION',
            batch_no: 'B1',
            location_name: '成品位',
            available_qty: '9',
          },
          {
            id: 4,
            material_id: 2,
            origin_type: 'PRODUCTION',
            batch_no: 'OTHER',
            available_qty: '9',
          },
          {
            id: 5,
            material_id: 1,
            origin_type: 'PURCHASE',
            batch_no: 'PURCHASE',
            available_qty: '9',
          },
        ],
        totalPages: 1,
      }
    return {
      items: [
        {
          id: 1,
          demand_no: 'S1',
          sales_order_no: 'ERP1',
          customer_reference: '客户A',
          material_id: 1,
          material_name: '成品',
          amount: '3.123456',
          status,
          pick_id: 9,
          picked_by: 'picker',
          batch_no: 'B1',
        },
      ],
      page: 0,
      totalPages: 1,
      totalElements: 1,
    }
  })
  return mount(SalesView, {
    props: { authorities, username },
    global: { stubs: { RouterLink: true } },
  })
}
it('only offers matching self-produced stock and requires reservation acknowledgement', async () => {
  const v = setup()
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '预占库存')!
    .trigger('click')
  await flushPromises()
  const d = v.get('[role="dialog"]')
  expect(d.text()).toContain('B1')
  expect(d.text()).not.toContain('OTHER')
  expect(d.text()).not.toContain('PURCHASE')
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await d.get('input[type="checkbox"]').setValue(true)
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.find(([, o]) => o?.method === 'POST')?.[1]
      ?.body,
  ).toBe(JSON.stringify({ balanceId: 3 }))
})
it('hides self review and freezes review identity across an uncertain retry', async () => {
  const own = setup('PICKED')
  await flushPromises()
  expect(own.text()).toContain('请由另一人复核')
  expect(own.findAll('button').some((b) => b.text() === '复核拣货')).toBe(false)
  own.unmount()
  const v = setup('PICKED', 'reviewer', [
    'wms:sales:read',
    'wms:sales:review',
    'wms:warehouse:1',
  ])
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '复核拣货')!
    .trigger('click')
  await flushPromises()
  const d = v.get('[role="dialog"]')
  await d.get('textarea').setValue('实物一致')
  await d.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request).mockRejectedValueOnce(new Error('超时'))
  await d.get('form').trigger('submit')
  await flushPromises()
  const body = vi.mocked(request).mock.calls.slice(-1)[0]![1]!.body
  expect(JSON.parse(body as string)).toMatchObject({
    pickId: 9,
    approved: true,
    reason: '实物一致',
  })
  expect(d.get('fieldset').attributes()).toHaveProperty('disabled')
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([, o]) => o?.method === 'POST')
      .map(([, o]) => o?.body),
  ).toEqual([body, body])
})
it('only permits physical shipment after review and hides cancellation after shipment', async () => {
  const waiting = setup('PICKED')
  await flushPromises()
  expect(waiting.text()).not.toContain('确认实际出库')
  waiting.unmount()
  const v = setup('VERIFIED')
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '确认实际出库')!
    .trigger('click')
  await flushPromises()
  const d = v.get('[role="dialog"]')
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  v.unmount()
  const done = setup('SHIPPED')
  await flushPromises()
  expect(done.text()).not.toContain('取消单据')
  expect(done.text()).not.toContain('确认实际出库')
})
