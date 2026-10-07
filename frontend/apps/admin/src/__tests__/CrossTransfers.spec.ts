import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CrossTransfersView from '../views/CrossTransfersView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
const row = {
  id: 7,
  source_warehouse_id: 1,
  target_warehouse_id: 2,
  source_balance_id: 9,
  target_balance_id: null,
  source_warehouse: '原料一仓',
  target_warehouse: '原料二仓',
  target_location: '目标位',
  material_name: '调拨物料',
  material_code: 'M1',
  batch_no: 'B1',
  quantity: '1.123456',
  in_transit_qty: '0.000000',
  unit: '件',
  status: 'PENDING',
  created_by: 'creator',
  reason: '补货',
}
function setup(
  status = 'PENDING',
  username = 'creator',
  authorities = ['ROLE_ADMIN'],
) {
  const current = { ...row, status }
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method === 'POST') return { ...current, status: 'APPROVED' }
    if (path.includes('/warehouses'))
      return {
        items: [
          {
            id: 1,
            name: '原料一仓',
            purpose: 'RAW_MATERIAL',
            status: 'ENABLED',
          },
          {
            id: 2,
            name: '原料二仓',
            purpose: 'RAW_MATERIAL',
            status: 'ENABLED',
          },
          {
            id: 3,
            name: '成品仓',
            purpose: 'FINISHED_GOODS',
            status: 'ENABLED',
          },
        ],
        totalPages: 1,
      }
    if (path.includes('/locations'))
      return [
        { id: 11, warehouseId: 1, name: '源位', areaType: 'STORAGE' },
        { id: 22, warehouseId: 2, name: '目标位', areaType: 'STORAGE' },
      ]
    if (path.includes('/stock?'))
      return {
        items: [
          {
            id: 9,
            location_id: 11,
            material_name: '调拨物料',
            batch_no: 'B1',
            available_qty: '5.000000',
            production_qty: '0',
            owner_type: 'ENTERPRISE',
            owner_id: 0,
          },
        ],
        totalPages: 1,
      }
    if (path.endsWith('/history'))
      return [
        {
          id: 1,
          action_type: 'CREATE',
          reason: '补货',
          created_by: 'creator',
          created_at: '2026-10-07',
        },
      ]
    if (path.includes('?'))
      return { items: [current], page: 0, totalElements: 1, totalPages: 1 }
    return current
  })
  return mount(CrossTransfersView, {
    props: { authorities, username },
    global: { stubs: { RouterLink: true } },
  })
}
it('enforces separate reviewer and side-specific action visibility', async () => {
  const own = setup()
  await flushPromises()
  expect(own.text()).not.toContain('审批并预占')
  own.unmount()
  const reviewer = setup('PENDING', 'reviewer', [
    'wms:cross-transfer:review',
    'wms:warehouse:1',
  ])
  await flushPromises()
  expect(reviewer.text()).toContain('审批并预占')
  reviewer.unmount()
  const source = setup('IN_TRANSIT', 'source', [
    'wms:cross-transfer:receive',
    'wms:warehouse:1',
  ])
  await flushPromises()
  expect(source.text()).not.toContain('确认目标仓整批收货')
  source.unmount()
  const target = setup('IN_TRANSIT', 'target', [
    'wms:cross-transfer:receive',
    'wms:warehouse:2',
  ])
  await flushPromises()
  expect(target.text()).toContain('确认目标仓整批收货')
  expect(target.text()).not.toContain('确认源仓实际发出')
})
it('creates decimal transfer only after acknowledgement and freezes uncertain retries', async () => {
  const v = setup()
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '申请调拨')!
    .trigger('click')
  await flushPromises()
  const d = v.get('[role="dialog"]')
  const selects = d.findAll('select')
  expect(selects[1]!.text()).not.toContain('成品仓')
  await selects[1]!.setValue(2)
  await selects[2]!.setValue(22)
  await d.get('input[inputmode="decimal"]').setValue('1.123456')
  await d.get('textarea').setValue('补货')
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await d.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request).mockRejectedValueOnce(new Error('连接失败'))
  await d.get('form').trigger('submit')
  await flushPromises()
  const first = vi
    .mocked(request)
    .mock.calls.find(([, o]) => o?.method === 'POST')!
  expect(JSON.parse(first[1]!.body as string)).toMatchObject({
    sourceBalanceId: 9,
    targetLocationId: 22,
    quantity: '1.123456',
    reason: '补货',
  })
  expect(d.get('fieldset').attributes()).toHaveProperty('disabled')
  await d.get('form').trigger('submit')
  await flushPromises()
  const writes = vi
    .mocked(request)
    .mock.calls.filter(([, o]) => o?.method === 'POST')
  expect(writes[1]).toEqual(writes[0])
  expect(v.find('[role="dialog"]').exists()).toBe(false)
})
it('clears old warehouse records on load failure and allows recovery', async () => {
  const v = setup()
  await flushPromises()
  expect(v.text()).toContain('WT-7')
  vi.mocked(request).mockRejectedValueOnce(new Error('仓库加载失败'))
  await v.get('select').setValue(2)
  await flushPromises()
  expect(v.text()).not.toContain('WT-7')
  expect(v.text()).toContain('仓库加载失败')
  await v
    .findAll('button')
    .find((b) => b.text() === '刷新记录')!
    .trigger('click')
  await flushPromises()
  expect(v.text()).toContain('WT-7')
})
it('keeps successful mutation notice when list reload fails and removes stale actions', async () => {
  const v = setup('APPROVED')
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '确认源仓实际发出')!
    .trigger('click')
  await flushPromises()
  const d = v.get('[role="dialog"]')
  await d.get('textarea').setValue('实际发出')
  await d.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request)
    .mockResolvedValueOnce({ ...row, status: 'IN_TRANSIT' })
    .mockRejectedValueOnce(new Error('刷新失败'))
  await d.get('form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('操作成功：在途')
  expect(v.text()).toContain('刷新失败')
  expect(v.find('[role="dialog"]').exists()).toBe(false)
  expect(v.findAll('tbody button')).toHaveLength(0)
})
