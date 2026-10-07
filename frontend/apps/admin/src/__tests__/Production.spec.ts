import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProductionView from '../views/ProductionView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { issueId: '1' } }) }))
afterEach(() => vi.resetAllMocks())
function setup(authorities = ['ROLE_ADMIN']) {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/production-reversals'))
      return { items: [], page: 0, totalPages: 0, totalElements: 0 }
    if (path.includes('capabilities')) return { enabled: true }
    if (path.includes('/locations'))
      return [
        { id: 7, warehouseId: 1, areaType: 'STORAGE', name: '原料存储位' },
        { id: 8, warehouseId: 1, areaType: 'INSPECTION', name: '原料隔离位' },
        { id: 9, warehouseId: 2, areaType: 'STORAGE', name: '线边位' },
      ]
    if (path.includes('/feedback'))
      return [
        {
          message_id: 'M1',
          event_type: 'MaterialIssued',
          status: 'PUBLISHED',
          received_at: null,
        },
        {
          message_id: 'M2',
          event_type: 'ProductionConsumed',
          status: 'PENDING',
          received_at: null,
        },
      ]
    if (path.includes('/records'))
      return {
        items: path.includes('returns=true')
          ? [
              {
                id: 3,
                event_no: 'R1',
                quantity: '2',
                status: 'PENDING',
                quality_status: 'QUALIFIED',
                target_location_id: 7,
              },
            ]
          : [],
        page: 0,
        totalPages: 1,
        totalElements: 1,
      }
    return {
      id: 1,
      warehouse_id: 1,
      work_order_no: 'WO1',
      material_name: '物料',
      unit: '件',
      status: 'ISSUED',
      quantity: '10',
      batch_no: 'B1',
      consumed_qty: '3',
      returned_qty: '1',
      remaining_qty: '6',
      pending_return_qty: '2',
      consumable_qty: '4',
    }
  })
  return mount(ProductionView, {
    props: { authorities },
    global: { stubs: { RouterLink: true } },
  })
}
it('requires actual consumption acknowledgement and preserves uncertain request for retry', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '模拟 MES 消耗')!
    .trigger('click')
  await flushPromises()
  const dialog = view.get('[role="dialog"]')
  await dialog.get('input[maxlength="64"]').setValue('C1')
  await dialog.get('input[inputmode="decimal"]').setValue('1.123456')
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await dialog.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request).mockRejectedValueOnce(new Error('请求超时'))
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(dialog.get('fieldset').attributes()).toHaveProperty('disabled')
  const body = vi.mocked(request).mock.calls.slice(-1)[0]![1]!.body
  expect(JSON.parse(body as string)).toEqual({
    eventNo: 'C1',
    issueId: 1,
    workOrderNo: 'WO1',
    quantity: '1.123456',
  })
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([, o]) => o?.method === 'POST')
      .map(([, o]) => o!.body),
  ).toEqual([body, body])
})
it('filters rejected returns to original warehouse isolation locations', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '模拟 MES 退料需求')!
    .trigger('click')
  await flushPromises()
  const dialog = view.get('[role="dialog"]')
  expect(dialog.findAll('select')[1]!.text()).toBe('原料存储位')
  await dialog.findAll('select')[0]!.setValue('REJECTED')
  expect(dialog.findAll('select')[1]!.text()).toBe('原料隔离位')
  expect(dialog.text()).toContain('仓管确认实物后才移动库存')
})
it('requires physical acknowledgement for return posting', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '确认实物退回')!
    .trigger('click')
  await flushPromises()
  const dialog = view.get('[role="dialog"]')
  expect(dialog.text()).toContain('原料存储位')
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await dialog.get('input[type="checkbox"]').setValue(true)
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    '/api/v1/wms/production/returns/3/confirm',
    { method: 'POST', body: '{}' },
  )
})
it('read-only users see publication separately from MES receipt and cannot write', async () => {
  const view = setup(['wms:production:read'])
  await flushPromises()
  expect(view.text()).toContain('已发布')
  expect(view.text()).toContain('模拟 MES 尚未接收')
  expect(view.findAll('li')[1]!.text()).toContain('待发布')
  expect(view.findAll('li')[1]!.text()).not.toContain('待确认实物')
  expect(
    view
      .findAll('button')
      .some((b) =>
        [
          '确认实物退回',
          '取消退料',
          '模拟 MES 消耗',
          '模拟 MES 退料需求',
        ].includes(b.text()),
      ),
  ).toBe(false)
  expect(
    vi.mocked(request).mock.calls.some(([p]) => p.includes('capabilities')),
  ).toBe(false)
})
