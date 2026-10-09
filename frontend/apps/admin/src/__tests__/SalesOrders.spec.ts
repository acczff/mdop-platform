import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import SalesOrdersView from '../views/SalesOrdersView.vue'
import SalesView from '../views/SalesView.vue'
import { ApiError, request } from '../api'
import type { SalesDocument } from '../sales'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/')
})
const base = '/api/v1/sales/documents'
const doc: SalesDocument = {
  id: 10,
  document_no: 'SO-00000010',
  warehouse_id: 1,
  warehouse_name: '成品仓',
  customer_id: 3,
  customer_name: '客户甲',
  customer_reference: 'CUSTOMER-1',
  status: 'DRAFT',
  version: 3,
  purpose: '交付成品',
  needed_date: '2026-11-01',
  created_by: 'seller',
  last_edited_by: 'seller',
  submitted_by: null,
  lines: [
    {
      id: 8,
      material_id: 2,
      material_code: 'FG1',
      material_name: '成品甲',
      unit: '件',
      quantity: '100',
    },
  ],
  arrangements: [],
  history: [],
  closure: null,
  closureMatches: true,
  fulfillment: {
    canClose: false,
    factHash: 'facts',
    blockers: ['实际已发未达到授权'],
    lines: [
      {
        orderLineId: 8,
        code: 'FG1',
        unit: '件',
        ordered: '100',
        allocated: '0',
        shipped: '0',
        remaining: '100',
      },
    ],
  },
}
function data(path: string, d = doc) {
  if (path.includes('/warehouses?'))
    return {
      items: [
        { id: 1, name: '成品仓', purpose: 'FINISHED_GOODS', status: 'ENABLED' },
        {
          id: 2,
          name: '未授权仓',
          purpose: 'FINISHED_GOODS',
          status: 'ENABLED',
        },
        { id: 3, name: '原料仓', purpose: 'RAW_MATERIAL', status: 'ENABLED' },
      ],
      totalPages: 1,
    }
  if (path.endsWith('/materials'))
    return [
      { id: 2, code: 'FG1', name: '成品甲', unit: '件', status: 'ENABLED' },
    ]
  if (path.endsWith('/customers'))
    return [{ id: 3, code: 'C1', name: '客户甲', status: 'ENABLED' }]
  if (path.includes('?')) return { items: [d], total: 1 }
  return d
}
function setup(d = doc, permissions = ['sales:write'], username = 'seller') {
  vi.mocked(request).mockImplementation(async (path) => data(path, d))
  return mount(SalesOrdersView, {
    props: {
      username,
      authorities: [
        'warehouse:read',
        'sales:read',
        'wms:warehouse:1',
        'wms:warehouse:3',
        ...permissions,
      ],
    },
  })
}
function button(v: VueWrapper, text: string) {
  return v.findAll('button').find((b) => b.text() === text)!
}
async function detail(v: VueWrapper) {
  await flushPromises()
  await button(v, '查看').trigger('click')
  await flushPromises()
}
async function form(v: VueWrapper) {
  await flushPromises()
  await button(v, '新建销售订单').trigger('click')
  await v.get('input[maxlength="500"]').setValue('交付成品')
  await v.get('input[type="date"]').setValue('2026-11-01')
  await v.get('.form-grid select').setValue('3')
  await v.get('.draft-line select').setValue('2')
  await v.get('.draft-line input').setValue('100')
}
it('limits warehouse and actions to the granted sales role', async () => {
  const v = setup(doc, [])
  await detail(v)
  expect(v.text()).not.toContain('未授权仓')
  expect(v.text()).not.toContain('原料仓')
  expect(button(v, '新建销售订单')).toBeUndefined()
  expect(button(v, '编辑')).toBeUndefined()
  v.unmount()
})
it('blocks self approval but allows a separate reviewer', async () => {
  const submitted = { ...doc, status: 'SUBMITTED', submitted_by: 'seller' }
  let v = setup(submitted, ['sales:review'])
  await detail(v)
  expect(button(v, '批准')).toBeUndefined()
  v.unmount()
  v = setup(submitted, ['sales:review'], 'reviewer')
  await detail(v)
  expect(button(v, '批准')).toBeDefined()
  v.unmount()
})
it('keeps uncertain creation payload across remount and retries the same key', async () => {
  let v = setup()
  await form(v)
  let payload = ''
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) {
      payload = options.body as string
      throw new ApiError(0, '响应丢失')
    }
    return data(path)
  })
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('结果待核对')
  expect(JSON.parse(payload)).toMatchObject({
    customerId: 3,
    lines: [{ materialId: 2, quantity: '100' }],
  })
  const original = payload
  v.unmount()
  v = setup()
  await flushPromises()
  expect(button(v, '新建销售订单').attributes('disabled')).toBeDefined()
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) {
      expect(options.body).toBe(original)
      return doc
    }
    return data(path)
  })
  await button(v, '原样重试').trigger('click')
  await flushPromises()
  expect(sessionStorage.getItem('mdop-sales-pending:seller')).toBeNull()
  expect(v.text()).toContain('操作已保存')
  v.unmount()
})
it('preserves the chosen order line and decimal quantity on partial dispatch failure', async () => {
  const approved = { ...doc, status: 'APPROVED' }
  const v = setup(approved)
  await detail(v)
  await button(v, '安排发货').trigger('click')
  await v.get('input[inputmode="decimal"]').setValue('60.123456')
  await v.get('textarea').setValue('第一批')
  vi.mocked(request).mockRejectedValue(new ApiError(503, '结果未知'))
  await v.get('form').trigger('submit')
  await flushPromises()
  const saved = JSON.parse(sessionStorage.getItem('mdop-sales-pending:seller')!)
  expect(saved.url).toBe(`${base}/10/arrangements`)
  expect(JSON.parse(saved.body)).toMatchObject({
    orderLineId: 8,
    quantity: '60.123456',
    version: 3,
  })
  expect(v.get('fieldset').attributes('disabled')).toBeDefined()
  v.unmount()
})
it('never offers closure while actual shipped quantity is incomplete', async () => {
  const v = setup({ ...doc, status: 'FULFILLING' })
  await detail(v)
  expect(button(v, '履约结案').attributes('disabled')).toBeDefined()
  expect(v.text()).toContain('实际已发未达到授权')
  v.unmount()
})
it('keeps closure version and displays immutable snapshot discrepancies', async () => {
  const complete = {
    ...doc,
    status: 'FULFILLING',
    fulfillment: { ...doc.fulfillment, canClose: true, blockers: [] },
  }
  let v = setup(complete)
  await detail(v)
  await button(v, '履约结案').trigger('click')
  await v.get('textarea').setValue('全部实际出库')
  vi.mocked(request).mockRejectedValue(new ApiError(503, '超时'))
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(
    JSON.parse(
      JSON.parse(sessionStorage.getItem('mdop-sales-pending:seller')!).body,
    ),
  ).toMatchObject({ version: 3, reason: '全部实际出库' })
  v.unmount()
  sessionStorage.clear()
  v = setup({
    ...complete,
    status: 'CLOSED',
    closureMatches: false,
    closure: {
      snapshot: JSON.stringify(complete.fulfillment),
      fact_hash: 'original',
      reason: '结案',
      closed_by: 'seller',
      closed_at: '2026-10-09',
    },
  })
  await detail(v)
  expect(v.text()).toContain('当前事实与结案快照不一致')
  expect(button(v, '履约结案')).toBeUndefined()
  v.unmount()
})
it('does not revive a stale detail after list refresh or expose rejected actions', async () => {
  const v = setup()
  await flushPromises()
  let resolve!: (value: unknown) => void
  vi.mocked(request).mockImplementation((path) =>
    path === `${base}/10`
      ? new Promise((r) => (resolve = r))
      : Promise.resolve(data(path)),
  )
  await button(v, '查看').trigger('click')
  await button(v, '刷新').trigger('click')
  await flushPromises()
  resolve(doc)
  await flushPromises()
  expect(v.find('.detail').exists()).toBe(false)
  v.unmount()
})
it('clears stale data after an explicit conflict and distinguishes saved refresh failure', async () => {
  let v = setup()
  await form(v)
  vi.mocked(request).mockRejectedValue(new ApiError(409, '版本冲突'))
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('操作被拒绝')
  expect(sessionStorage.getItem('mdop-sales-pending:seller')).toBeNull()
  expect(v.find('.detail').exists()).toBe(false)
  v.unmount()
  v = setup()
  await form(v)
  vi.mocked(request).mockImplementation(async (_p, o) => {
    if (o?.method) return doc
    throw new Error('列表失败')
  })
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('操作已保存')
  expect(v.text()).toContain('列表刷新失败')
  v.unmount()
})
it('shows formal WMS navigation without confusing reservation with actual shipment', async () => {
  const v = setup(
    {
      ...doc,
      status: 'FULFILLING',
      arrangements: [
        {
          id: 11,
          order_line_id: 8,
          quantity: '60',
          expected_date: '2026-11-01',
          status: 'DELIVERED',
          wms_sales_id: 91,
          last_error: null,
          wms: {
            id: 91,
            status: 'RESERVED',
            shippedQuantity: '0',
            batchNo: 'B1',
            ledgerCount: 0,
          },
        },
      ],
    },
    ['sales:write', 'wms:sales:read'],
  )
  await detail(v)
  expect(v.get('a').attributes('href')).toBe(
    '/sales?warehouseId=1&salesOrderId=91',
  )
  expect(v.text()).toContain('待拣货')
  expect(v.text()).toContain('实际已发 0')
  expect(button(v, '取消订单').attributes('disabled')).toBeDefined()
  v.unmount()
})
it('loads an exact WMS execution from an authorized source link and skips simulated feedback', async () => {
  window.history.replaceState({}, '', '/sales?warehouseId=1&salesOrderId=91')
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses?')) return data(path)
    if (path.endsWith('/91'))
      return {
        id: 91,
        warehouse_id: 1,
        source_system: 'MDOP_SALES',
        status: 'SHIPPED',
        sales_order_no: 'SO-00000010',
      }
    return []
  })
  const v = mount(SalesView, {
    global: { stubs: { RouterLink: true } },
    props: {
      username: 'reader',
      authorities: ['wms:sales:read', 'wms:warehouse:1'],
    },
  })
  await flushPromises()
  expect(v.text()).toContain('正式销售订单')
  expect(v.text()).toContain('SO-00000010')
  await button(v, '操作与反馈').trigger('click')
  await flushPromises()
  expect(
    vi.mocked(request).mock.calls.some(([p]) => p.includes('/feedback')),
  ).toBe(false)
  v.unmount()
})
