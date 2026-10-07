import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import IssuesView from '../views/IssuesView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
function setup(authorities = ['ROLE_ADMIN'], status = 'RESERVED') {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('warehouses'))
      return {
        items: [
          { id: 1, name: '原料仓', purpose: 'RAW_MATERIAL' },
          { id: 2, name: '线边仓', purpose: 'LINE_SIDE' },
        ],
        totalPages: 1,
      }
    if (path.includes('capabilities')) return { enabled: false }
    if (path.includes('/stock?'))
      return {
        items: [
          {
            id: 7,
            material_id: 5,
            location_name: '原料位',
            batch_no: 'B1',
            available_qty: '20',
          },
        ],
        totalPages: 1,
      }
    if (path.includes('/locations'))
      return [{ id: 9, warehouseId: 2, areaType: 'STORAGE', name: '线边位' }]
    return {
      items: [
        {
          id: 1,
          demand_no: 'MES-01',
          work_order_no: 'WO-01',
          status,
          material_id: 5,
          material_name: '物料',
          quantity: '10',
          unit: '件',
          batch_no: 'B1',
          source_location: '原料位',
          target_location: '线边位',
          target_warehouse_name: '线边仓',
        },
      ],
      page: 0,
      totalElements: 1,
      totalPages: 1,
    }
  })
  return mount(IssuesView, { props: { authorities } })
}
it('requires physical handover acknowledgement before issue posting', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '确认发料')!
    .trigger('click')
  await flushPromises()
  const dialog = view.get('[aria-label="处理领料"]')
  expect(dialog.text()).toContain('B1')
  expect(dialog.text()).toContain('WO-01')
  expect(dialog.text()).toContain('不计生产消耗')
  const confirm = dialog.findAll('button').find((b) => b.text() === '确认记账')!
  expect(confirm.attributes()).toHaveProperty('disabled')
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await dialog.get('input[type="checkbox"]').setValue(true)
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledWith('/api/v1/wms/issues/1/confirm', {
    method: 'POST',
    body: '{}',
  })
})
it('read-only users cannot reserve cancel or issue', async () => {
  const view = setup(['wms:issue:read', 'wms:warehouse:1', 'wms:warehouse:2'])
  await flushPromises()
  expect(view.text()).toContain('已预占')
  expect(
    view
      .findAll('button')
      .some((b) =>
        ['确认发料', '预占库存', '取消领料', '模拟 MES 需求'].includes(
          b.text(),
        ),
      ),
  ).toBe(false)
})
it('keeps reservation contents for a safe retry after an uncertain response', async () => {
  const view = setup(['ROLE_ADMIN'], 'OPEN')
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '预占库存')!
    .trigger('click')
  await flushPromises()
  const dialog = view.get('[aria-label="处理领料"]')
  vi.mocked(request).mockRejectedValueOnce(new Error('请求超时'))
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(dialog.text()).toContain('请求超时')
  expect(dialog.get('fieldset').attributes()).toHaveProperty('disabled')
  const body = vi.mocked(request).mock.calls.slice(-1)[0]![1]!.body
  expect(JSON.parse(body as string)).toEqual({
    balanceId: 7,
    targetLocationId: 9,
  })
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([p]) => p.endsWith('/reserve'))
      .slice(-1)[0]![1]!.body,
  ).toBe(body)
})
