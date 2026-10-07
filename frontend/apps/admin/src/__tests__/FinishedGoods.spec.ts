import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FinishedGoodsView from '../views/FinishedGoodsView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
function setup(status = 'OPEN', authorities = ['ROLE_ADMIN']) {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return {
        items: [
          { id: 1, name: '成品仓', purpose: 'FINISHED_GOODS' },
          { id: 2, name: '原料仓', purpose: 'RAW_MATERIAL' },
        ],
        totalPages: 1,
      }
    if (path.includes('/locations'))
      return [
        { id: 3, warehouseId: 1, areaType: 'INSPECTION', name: '待检位' },
        { id: 4, warehouseId: 1, areaType: 'STORAGE', name: '存储位' },
        { id: 5, warehouseId: 2, areaType: 'INSPECTION', name: '外仓位' },
      ]
    if (path.includes('/capabilities')) return { enabled: true }
    if (path.includes('/materials')) return []
    if (path.includes('/feedback'))
      return [
        {
          message_id: 'm',
          event_type: 'FinishedGoodsReceived',
          status: 'PUBLISHED',
          received_at: null,
        },
      ]
    return {
      items: [
        {
          id: 1,
          work_order_no: 'WO1',
          demand_no: 'FG1',
          material_name: '成品',
          batch_no: 'B1',
          amount: '10.123456',
          status,
        },
      ],
      page: 0,
      totalElements: 1,
      totalPages: 1,
    }
  })
  return mount(FinishedGoodsView, {
    props: { authorities },
    global: { stubs: { RouterLink: true } },
  })
}
it('requires physical receipt acknowledgement and retains identical retry payload', async () => {
  const v = setup()
  await flushPromises()
  expect(v.text()).not.toContain('原料仓')
  await v
    .findAll('button')
    .find((b) => b.text() === '确认实物收货')!
    .trigger('click')
  await flushPromises()
  const d = v.get('[role="dialog"]')
  expect(d.text()).toContain('待检位')
  expect(d.text()).not.toContain('存储位')
  expect(d.text()).not.toContain('外仓位')
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await d.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request).mockRejectedValueOnce(new Error('超时'))
  await d.get('form').trigger('submit')
  await flushPromises()
  const body = vi.mocked(request).mock.calls.slice(-1)[0]![1]!.body
  expect(JSON.parse(body as string)).toEqual({ locationId: 3 })
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
it('keeps rejected goods isolated and hides simulator for readers', async () => {
  const v = setup('REJECTED', ['wms:finished:read', 'wms:warehouse:1'])
  await flushPromises()
  expect(v.text()).toContain('不合格隔离')
  expect(v.text()).not.toContain('确认上架')
  expect(v.text()).not.toContain('模拟 MES')
})
it('does not label broker publication as downstream receipt', async () => {
  const v = setup()
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '反馈记录')!
    .trigger('click')
  await flushPromises()
  expect(v.get('[role="dialog"]').text()).toContain('已发布 · 尚未接收')
})
