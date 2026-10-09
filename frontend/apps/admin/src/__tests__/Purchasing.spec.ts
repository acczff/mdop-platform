import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PurchasingView from '../views/PurchasingView.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
})
const base = '/api/v1/purchasing/documents'
const doc = {
  id: 10,
  document_no: 'PR-QA',
  kind: 'REQUEST',
  warehouse_id: 1,
  warehouse_name: '采购仓',
  status: 'DRAFT',
  version: 3,
  purpose: '采购用途',
  needed_date: '2026-10-31',
  supplier_id: null,
  supplier_name: null,
  request_id: null,
  created_by: 'buyer',
  last_edited_by: 'buyer',
  submitted_by: null,
  lines: [
    {
      id: 8,
      material_id: 2,
      material_code: 'M01',
      material_name: '钢材',
      unit: '千克',
      quantity: '12.123456',
      source_line_id: null,
    },
  ],
  related: [],
  history: [],
}

it('submits closure with the original parent version and preserves an uncertain result', async () => {
  const order = {
    ...doc,
    kind: 'ORDER',
    status: 'FULFILLING',
    fulfillment: {
      lines: [],
      notices: [],
      blockers: [],
      canClose: true,
      outcome: 'WITH_RETURNS',
      factHash: 'checked',
    },
  }
  const view = setup()
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) throw new ApiError(503, '响应丢失')
    if (path === `${base}/10`) return order
    return data(path)
  })
  await flushPromises()
  await button(view, '查看').trigger('click')
  await flushPromises()
  await button(view, '确认履约结案').trigger('click')
  await view.get('textarea').setValue('已完成实物交接')
  await view.get('form').trigger('submit')
  await flushPromises()
  const saved = JSON.parse(
    sessionStorage.getItem('mdop-purchasing-pending:buyer')!,
  )
  expect(saved.url).toBe(`${base}/10/actions/close`)
  expect(JSON.parse(saved.body)).toMatchObject({
    version: 3,
    reason: '已完成实物交接',
  })
  expect(view.text()).toContain('结果待核对')
  expect(view.find('[aria-label="采购履约"]').exists()).toBe(false)
  expect(view.get('fieldset').attributes('disabled')).toBeDefined()
  view.unmount()
})
it('creates an independently approved replacement with a fixed original order and keeps uncertain payload', async () => {
  const order = {
    ...doc,
    kind: 'ORDER',
    status: 'CLOSED',
    document_no: 'PO-ORIGINAL',
    fulfillment: {
      lines: [
        {
          orderLineId: 8,
          code: 'M01',
          unit: '千克',
          ordered: '20',
          received: '20',
          reversed: '0',
          netReceived: '20',
          remaining: '0',
          pendingInspection: '0',
          pendingPutaway: '0',
          putaway: '0',
          rejectedPendingReturn: '0',
          returned: '20',
        },
      ],
      notices: [],
      blockers: [],
      canClose: true,
      outcome: 'WITH_RETURNS',
      factHash: 'fixed',
    },
  }
  const view = setup()
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) throw new ApiError(503, '响应丢失')
    if (path === `${base}/10`) return order
    return data(path)
  })
  await flushPromises()
  await button(view, '查看').trigger('click')
  await flushPromises()
  await button(view, '新建关联补货需求').trigger('click')
  expect(view.text()).toContain('补货原订单 #10')
  expect(
    (view.get('.draft-line input').element as HTMLInputElement).value,
  ).toBe('')
  await view.get('input[type="date"]').setValue('2026-11-01')
  await view.get('.draft-line select').setValue('2')
  await view.get('.draft-line input').setValue('20')
  await view.get('form').trigger('submit')
  await flushPromises()
  const saved = JSON.parse(
    sessionStorage.getItem('mdop-purchasing-pending:buyer')!,
  )
  expect(saved.url).toBe(base)
  expect(JSON.parse(saved.body)).toMatchObject({
    originalOrderId: 10,
    purpose: '原订单 PO-ORIGINAL 退供后补货',
  })
  expect(view.text()).toContain('结果待核对')
  view.unmount()
})

it('shows the original order link without offering replacement actions to readers', async () => {
  const view = setup([])
  vi.mocked(request).mockImplementation(async (path) =>
    path === `${base}/10`
      ? {
          ...doc,
          original_order_id: 4,
          related: [{ id: 4, document_no: 'PO-SOURCE', status: 'CLOSED' }],
        }
      : data(path),
  )
  await flushPromises()
  await button(view, '查看').trigger('click')
  await flushPromises()
  expect(button(view, '新建关联补货需求')).toBeUndefined()
  await button(view, 'PO-SOURCE').trigger('click')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(`${base}/4`)
  view.unmount()
})

async function data(path: string) {
  if (path.includes('/warehouses?'))
    return {
      items: [
        { id: 1, name: '采购仓', status: 'ENABLED' },
        { id: 2, name: '其他仓', status: 'ENABLED' },
      ],
      totalPages: 1,
    }
  if (path.endsWith('/materials'))
    return [
      { id: 2, code: 'M01', name: '钢材', unit: '千克', status: 'ENABLED' },
    ]
  if (path.endsWith('/suppliers'))
    return [{ id: 3, code: 'S01', name: '供方', status: 'ENABLED' }]
  if (path.includes('?')) return { items: [doc], total: 1 }
  return doc
}
function setup(permissions = ['purchasing:write'], username = 'buyer') {
  vi.mocked(request).mockImplementation((path) => data(path))
  return mount(PurchasingView, {
    props: {
      username,
      authorities: [
        'warehouse:read',
        'purchasing:read',
        'wms:warehouse:1',
        ...permissions,
      ],
    },
  })
}
function button(view: VueWrapper, name: string) {
  return view.findAll('button').find((b) => b.text() === name)!
}
async function form(view: VueWrapper) {
  await flushPromises()
  await button(view, '新建需求').trigger('click')
  await view.get('input[maxlength="500"]').setValue('材料采购')
  await view.get('input[type="date"]').setValue('2026-11-01')
  await view.get('.draft-line select').setValue('2')
  await view.get('.draft-line input').setValue('12.123456')
}
it('filters warehouse scope and gives readers neither editing nor approval actions', async () => {
  const view = setup([])
  await flushPromises()
  expect(view.text()).not.toContain('其他仓')
  expect(button(view, '新建需求')).toBeUndefined()
  await button(view, '查看').trigger('click')
  await flushPromises()
  expect(button(view, '提交审核')).toBeUndefined()
  expect(button(view, '编辑')).toBeUndefined()
  view.unmount()
})
it('keeps the exact uncertain payload through remount and retries it without duplication', async () => {
  let view = setup()
  await form(view)
  let original = ''
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) {
      original = options.body as string
      throw new ApiError(0, '响应丢失')
    }
    return data(path)
  })
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.text()).toContain('结果待核对')
  expect(JSON.parse(original).lines[0].quantity).toBe('12.123456')
  expect(sessionStorage.getItem('mdop-purchasing-pending:buyer')).toContain(
    'idempotencyKey',
  )
  view.unmount()
  view = setup()
  await flushPromises()
  expect(button(view, '新建需求').attributes('disabled')).toBeDefined()
  let replay = ''
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) {
      replay = options.body as string
      return doc
    }
    return data(path)
  })
  await button(view, '原样重试').trigger('click')
  await flushPromises()
  expect(replay).toBe(original)
  expect(sessionStorage.getItem('mdop-purchasing-pending:buyer')).toBeNull()
  expect(view.text()).toContain('操作已保存')
  view.unmount()
})
it('distinguishes committed writes from list refresh failure and disables new operations', async () => {
  const view = setup()
  await form(view)
  let writes = 0
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (options?.method) {
      writes++
      return doc
    }
    if (path.includes('?')) throw new ApiError(503, '查询失败')
    return data(path)
  })
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(writes).toBe(1)
  expect(view.text()).toContain('操作已保存')
  expect(view.text()).toContain('列表刷新失败')
  expect(button(view, '新建需求').attributes('disabled')).toBeDefined()
  expect(button(view, '查看')).toBeUndefined()
  view.unmount()
})
it('ignores a late detail response after switching document categories', async () => {
  const view = setup()
  await flushPromises()
  let resolve!: (value: unknown) => void
  vi.mocked(request).mockImplementation((path) =>
    path === base + '/10'
      ? new Promise((r) => {
          resolve = r
        })
      : data(path),
  )
  await button(view, '查看').trigger('click')
  await button(view, '采购订单').trigger('click')
  await flushPromises()
  resolve({ ...doc, purpose: '迟到的旧详情' })
  await flushPromises()
  expect(view.text()).not.toContain('迟到的旧详情')
  expect(view.find('.detail').exists()).toBe(false)
  view.unmount()
})
it('rejects local self-review and does not keep an operable detail after read failure', async () => {
  const view = setup(['purchasing:review'])
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce({
    ...doc,
    status: 'SUBMITTED',
    created_by: 'another',
    last_edited_by: 'buyer',
  })
  await button(view, '查看').trigger('click')
  await flushPromises()
  expect(button(view, '批准')).toBeUndefined()
  expect(view.text()).toContain('等待其他采购审批员')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(503, '详情错误'))
  await button(view, '查看').trigger('click')
  await flushPromises()
  expect(view.find('.detail').exists()).toBe(false)
  expect(view.text()).toContain('详情读取失败')
  view.unmount()
})
it('sends the reviewed version and reason and blocks stale-version conflicts until refresh', async () => {
  const view = setup(['purchasing:review'], 'reviewer')
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce({
    ...doc,
    status: 'SUBMITTED',
    submitted_by: 'buyer',
  })
  await button(view, '查看').trigger('click')
  await flushPromises()
  await button(view, '批准').trigger('click')
  await view.get('textarea').setValue('核对通过')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '版本冲突'))
  await view.get('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find(([path]) => path.endsWith('/actions/approve'))!
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({
    version: 3,
    reason: '核对通过',
  })
  expect(view.text()).toContain('操作被拒绝')
  expect(view.find('.detail').exists()).toBe(false)
  view.unmount()
})
it('keeps approved request quantities fixed while editing an order', async () => {
  const view = setup()
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce({
    ...doc,
    kind: 'ORDER',
    supplier_id: 3,
    supplier_name: '供方',
  })
  await button(view, '查看').trigger('click')
  await flushPromises()
  await button(view, '编辑').trigger('click')
  expect(view.get('.draft-line input').attributes('disabled')).toBeDefined()
  expect(view.get('.draft-line select').attributes('disabled')).toBeDefined()
  expect(button(view, '添加物料')).toBeUndefined()
  view.unmount()
})

it('retains failed arrangement quota and posts stable order line ids with decimal strings', async () => {
  const view = setup()
  await flushPromises()
  const order = {
    ...doc,
    kind: 'ORDER',
    status: 'APPROVED',
    arrangements: [
      {
        id: 4,
        expected_date: '2026-11-01',
        status: 'PENDING',
        last_error: '物料停用',
        wms_arrival_id: null,
        lines: [
          {
            id: 21,
            order_line_id: 8,
            material_code: 'M01',
            quantity: '10.000000',
          },
        ],
      },
    ],
  }
  vi.mocked(request).mockResolvedValueOnce(order)
  await button(view, '查看').trigger('click')
  await flushPromises()
  expect(view.text()).toContain('10.000000 / 2.123456')
  expect(button(view, '取消订单').attributes('disabled')).toBeDefined()
  expect(button(view, '重试送达')).toBeDefined()
  await button(view, '安排到货').trigger('click')
  await view.get('[aria-label="安排数量 1"]').setValue('2.123456')
  await view.get('textarea').setValue('剩余分批')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(0, '响应丢失'))
  await view.get('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find(([path]) => path.endsWith('/10/arrangements'))!
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({
    version: 3,
    lines: [{ orderLineId: 8, quantity: '2.123456' }],
  })
  expect(button(view, '安排到货')).toBeUndefined()
  expect(sessionStorage.getItem('mdop-purchasing-pending:buyer')).toContain(
    '2.123456',
  )
  view.unmount()
})

it('blocks withdrawal once WMS has even a draft and exposes receiving links only with permission', async () => {
  const view = setup(['purchasing:write', 'wms:arrival:read'])
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce({
    ...doc,
    kind: 'ORDER',
    status: 'FULFILLING',
    arrangements: [
      {
        id: 5,
        status: 'DELIVERED',
        expected_date: '2026-11-01',
        wms_arrival_id: 9,
        lines: [
          {
            id: 21,
            order_line_id: 8,
            material_code: 'M01',
            quantity: '12.123456',
          },
        ],
        wms: {
          lines: [{ arrangementLineId: 21, receivedQty: '0.000000' }],
          receipts: [{ id: 11, number: 'REC-11', status: 'DRAFT' }],
        },
      },
    ],
  })
  await button(view, '查看').trigger('click')
  await flushPromises()
  expect(button(view, '撤回安排').attributes('disabled')).toBeDefined()
  expect(view.get('a[href*="arrivalId=9"]').attributes('href')).toBe(
    '/receiving?warehouseId=1&arrivalId=9',
  )
  expect(view.text()).toContain('REC-11（草稿）')
  view.unmount()
})
