import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import View from '../views/ProductionOrdersView.vue'
import { ApiError, request } from '../api'

vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const base = '/api/v1/manufacturing'
it.each([401, 403])(
  'retains unknown production writes after HTTP %s on retry',
  async (status) => {
    const key = 'mdop-production-order-pending:planner'
    const saved = JSON.stringify({
      url: base + '/demands',
      method: 'POST',
      warehouse: 1,
      body: '{"idempotencyKey":"original"}',
    })
    sessionStorage.setItem(key, saved)
    const v = setup()
    await flushPromises()
    vi.mocked(request).mockRejectedValueOnce(new ApiError(status, '权限失效'))
    await btn(v, '原样重试')!.trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBe(saved)
    expect(v.text()).toContain('结果待核对')
    v.unmount()
  },
)
const order = {
  id: 2,
  order_no: 'MFG-00000002',
  status: 'SUBMITTED',
  version: 1,
  site_id: 1,
  site_code: 'A',
  site_name: '装配线',
  warehouse_name: '成品仓',
  bom_id: 1,
  planned_date: '2026-11-01',
  created_by: 'planner',
  last_edited_by: 'planner',
  submitted_by: 'planner',
  approved_by: null,
  bom_snapshot: JSON.stringify({
    id: 1,
    version_label: 'V1',
    base_quantity: '1',
    product_unit: '件',
    components: [
      {
        material_code: 'C',
        material_name: '组件',
        quantity: '2.000000',
        unit: '件',
      },
    ],
  }),
}
const demand = {
  id: 1,
  warehouse_id: 1,
  source_type: 'MANUAL',
  source_reference: 'PLAN-1',
  product_id: 1,
  product_code: 'FG',
  product_name: '产品',
  quantity: '10.000000',
  unit: '件',
  needed_date: '2026-12-01',
  purpose: '备库',
  status: 'OPEN',
  version: 1,
  created_by: 'planner',
  orders: [order],
  history: [],
}
let current = structuredClone(demand)
const response = async (path: string): Promise<unknown> => {
  if (path.startsWith('/api/master-data/warehouses?page=0'))
    throw new ApiError(400, '仓库分页从1开始')
  if (path.startsWith('/api/master-data/warehouses'))
    return {
      items: [
        {
          id: 1,
          code: 'FG',
          name: '成品仓',
          purpose: 'FINISHED_GOODS',
          status: 'ENABLED',
        },
      ],
      totalPages: 1,
    }
  if (path === '/api/master-data/materials')
    return [{ id: 1, code: 'FG', name: '产品', unit: '件', status: 'ENABLED' }]
  if (path === '/api/master-data/production-sites')
    return [{ id: 1, code: 'A', name: '装配线', status: 'ENABLED' }]
  if (path.startsWith(base + '/sales-sources'))
    return [
      {
        id: 3,
        document_no: 'SO-1',
        material_code: 'FG',
        quantity: '10.000000',
        unit: '件',
      },
    ]
  if (path.startsWith(base + '/boms?'))
    return {
      items: [
        {
          id: 1,
          product_id: 1,
          product_code: 'FG',
          version_label: 'V1',
          base_quantity: '1',
          product_unit: '件',
        },
      ],
      total: 1,
    }
  if (path.startsWith(base + '/demands?')) return { items: [current], total: 1 }
  return current
}
const btn = (v: VueWrapper, name: string) =>
  v.findAll('button').find((b) => b.text() === name)
function setup(username = 'planner', permission = 'write') {
  vi.mocked(request).mockImplementation(response as typeof request)
  return mount(View, {
    props: {
      username,
      authorities: [
        'manufacturing:read',
        `manufacturing:${permission}`,
        'bom:read',
        'wms:warehouse:1',
      ],
    },
  })
}
async function open(v: VueWrapper) {
  await flushPromises()
  await btn(v, '查看')!.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  current = structuredClone(demand)
})
it('separates creator review, read-only and immutable approved orders', async () => {
  let v = setup('planner', 'review')
  await open(v)
  expect(btn(v, '批准')).toBeUndefined()
  v.unmount()
  v = setup('reviewer', 'review')
  await open(v)
  expect(btn(v, '批准')).toBeDefined()
  expect(btn(v, '新建需求')).toBeUndefined()
  v.unmount()
  current.orders[0]!.status = 'APPROVED'
  v = setup()
  await open(v)
  expect(btn(v, '编辑工单')).toBeUndefined()
  expect(btn(v, '取消工单')).toBeDefined()
  expect(v.text()).toContain('V1')
  expect(v.text()).toContain('2.000000')
  v.unmount()
})
it('creates a sales-backed demand without trusting editable quantity or product', async () => {
  const v = setup()
  await flushPromises()
  await btn(v, '新建需求')!.trigger('click')
  const dialog = v.get('[role="dialog"]')
  await dialog.findAll('select')[0]!.setValue('SALES')
  await dialog.findAll('select')[1]!.setValue('3')
  await dialog.get('textarea').setValue('销售转生产')
  await dialog.get('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find(
      ([p, o]) => p === base + '/demands' && o?.method === 'POST',
    )!
  const body = JSON.parse(call[1]!.body as string)
  expect(body).toMatchObject({ sourceType: 'SALES', salesLineId: 3 })
  expect(body.productId).toBeUndefined()
  expect(body.quantity).toBeUndefined()
  v.unmount()
})
it('converts a demand using an explicitly selected published BOM', async () => {
  current.orders = []
  const v = setup()
  await open(v)
  await btn(v, '转生产订单')!.trigger('click')
  await flushPromises()
  const dialog = v.get('[role="dialog"]')
  await dialog.findAll('select')[0]!.setValue('1')
  await dialog.findAll('select')[1]!.setValue('1')
  await dialog.get('textarea').setValue('明确版本')
  await dialog.get('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find(
      ([p, o]) => p === base + '/demands/1/orders' && o?.method === 'POST',
    )!
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({
    bomId: 1,
    siteId: 1,
    version: 1,
    plannedDate: '2026-12-01',
  })
  v.unmount()
})
it('retains the exact unknown write for remount recovery and prevents another authorization', async () => {
  let v = setup('reviewer', 'review')
  await open(v)
  await btn(v, '批准')!.trigger('click')
  await v.get('textarea').setValue('已核对')
  vi.mocked(request).mockImplementation(async (p, o) => {
    if (o?.method === 'POST') throw new ApiError(503, '结果未知')
    return response(p) as never
  })
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  const original = vi
    .mocked(request)
    .mock.calls.find(([, o]) => o?.method === 'POST')!
  expect(v.text()).toContain('原样重试')
  expect(btn(v, '查看')).toBeUndefined()
  v.unmount()
  v = setup('reviewer', 'review')
  await flushPromises()
  await btn(v, '原样重试')!.trigger('click')
  await flushPromises()
  const writes = vi
    .mocked(request)
    .mock.calls.filter(([, o]) => o?.method === 'POST')
  expect(writes[writes.length - 1]).toEqual(original)
  expect(
    sessionStorage.getItem('mdop-production-order-pending:reviewer'),
  ).toBeNull()
  v.unmount()
})
it('clears actionable detail after a rejected write', async () => {
  const v = setup('reviewer', 'review')
  await open(v)
  await btn(v, '批准')!.trigger('click')
  await v.get('textarea').setValue('审核')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '地点停用'))
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('地点停用')
  expect(btn(v, '批准')).toBeUndefined()
  expect(btn(v, '原样重试')).toBeUndefined()
  v.unmount()
})
it('does not restore late detail after a failed refresh', async () => {
  const v = setup()
  await flushPromises()
  let finish!: (x: unknown) => void
  vi.mocked(request).mockImplementation((p) =>
    p === base + '/demands/1'
      ? new Promise((resolve) => {
          finish = resolve
        })
      : (response(p) as never),
  )
  await btn(v, '查看')!.trigger('click')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(503, '查询失败'))
  await v.get('form.toolbar').trigger('submit')
  await flushPromises()
  finish(current)
  await flushPromises()
  expect(v.find('[aria-label="生产需求详情"]').exists()).toBe(false)
  expect(v.text()).toContain('查询失败')
  v.unmount()
})
it('blocks corrupt or unavailable recovery storage before sending writes', async () => {
  sessionStorage.setItem('mdop-production-order-pending:planner', 'bad')
  let v = setup()
  await flushPromises()
  expect(v.text()).toContain('重试凭据无法读取')
  expect(request).not.toHaveBeenCalled()
  v.unmount()
  sessionStorage.clear()
  v = setup('reviewer', 'review')
  await open(v)
  await btn(v, '批准')!.trigger('click')
  await v.get('textarea').setValue('审核')
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('denied')
  })
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('本次请求未发送')
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  spy.mockRestore()
  v.unmount()
})
