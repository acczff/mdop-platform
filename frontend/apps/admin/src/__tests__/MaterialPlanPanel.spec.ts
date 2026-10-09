import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import View from '../components/MaterialPlanPanel.vue'
import { ApiError, request } from '../api'

vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const base = '/api/v1/manufacturing/orders/2/materials'
const line = {
  materialId: 3,
  code: 'A',
  name: '组件',
  unit: '件',
  required: '20.000000',
  available: '4.000000',
  outstanding: '0',
  gap: '16',
  suggested: '16',
  activePurchase: false,
  output: '10',
  base: '1',
  componentQuantity: '2',
  roundingNumerator: '0',
  roundingDenominator: '1',
}
const plan = {
  orderStatus: 'APPROVED',
  orderVersion: 2,
  planVersion: 1,
  plan: {
    warehouse_id: 1,
    snapshot: JSON.stringify({ lines: [line] }),
    created_at: '2026-10-09',
    created_by: 'planner',
  },
  lines: [
    { id: 8, material_id: 3, purchase_request_id: null as number | null },
  ],
  purchases: [] as unknown[],
  history: [
    { id: 1, version: 1, created_at: '2026-10-09', created_by: 'planner' },
  ],
}
let current = structuredClone(plan)
const warehouses = {
  items: [
    {
      id: 1,
      code: 'RAW',
      name: '原料仓',
      purpose: 'RAW_MATERIAL',
      status: 'ENABLED',
    },
    {
      id: 9,
      code: 'FOREIGN',
      name: '无权仓',
      purpose: 'RAW_MATERIAL',
      status: 'ENABLED',
    },
  ],
  totalPages: 1,
}
const response = async (url: string): Promise<unknown> =>
  url.startsWith('/api/master-data/warehouses')
    ? warehouses
    : structuredClone(current)
const btn = (v: VueWrapper, name: string) =>
  v.findAll('button').find((b) => b.text() === name)
function setup(write = true) {
  return mount(View, {
    props: {
      orderId: 2,
      username: 'planner',
      authorities: [
        'manufacturing:read',
        'wms:warehouse:1',
        ...(write ? ['manufacturing:write'] : []),
      ],
    },
  })
}
async function open(v: VueWrapper) {
  await btn(v, '材料需求与采购建议')!.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  current = structuredClone(plan)
  vi.mocked(request).mockImplementation(response as typeof request)
})

it('shows a precise gap and only creates a draft after explicit review', async () => {
  const v = setup()
  await open(v)
  expect(v.text()).toContain('20')
  expect(v.text()).toContain('16')
  expect(v.text()).not.toContain('无权仓')
  await v.find('input').setValue('已核对')
  await btn(v, '核对建议')!.trigger('click')
  expect(
    vi.mocked(request).mock.calls.filter((c) => c[1]?.method === 'POST'),
  ).toHaveLength(0)
  current.lines[0]!.purchase_request_id = 7
  current.purchases = [
    {
      id: 7,
      document_no: 'PR-7',
      status: 'DRAFT',
      quantity: '16',
      putaway: '0',
      returned: '0',
      outstanding: '16',
    },
  ]
  await btn(v, '确认生成采购需求')!.trigger('click')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find((c) => c[0] === base + '/confirm')!
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({
    planVersion: 1,
    lineId: 8,
    reason: '已核对',
  })
  expect(v.text()).toContain('PR-7')
  expect(v.text()).toContain('已生成草稿')
  expect(sessionStorage.length).toBe(0)
})
it('locks uncertain writes and replays the exact payload after remount', async () => {
  vi.mocked(request).mockImplementation((async (
    url: string,
    options?: RequestInit,
  ) => {
    if (options?.method === 'POST') throw new ApiError(0, '超时')
    return response(url)
  }) as typeof request)
  let v = setup()
  await open(v)
  await v.find('input').setValue('核对')
  await btn(v, '核对建议')!.trigger('click')
  await btn(v, '确认生成采购需求')!.trigger('click')
  await flushPromises()
  const original = vi
    .mocked(request)
    .mock.calls.find((c) => c[0] === base + '/confirm')!
  expect(v.text()).toContain('结果待核对')
  expect(btn(v, '计算材料需求')).toBeUndefined()
  v.unmount()
  vi.mocked(request).mockImplementation(response as typeof request)
  v = setup()
  await flushPromises()
  await btn(v, '原样重试材料请求')!.trigger('click')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter((c) => c[0] === base + '/confirm')
      .slice(-1)[0]![1]!.body,
  ).toBe(original[1]!.body)
  expect(sessionStorage.length).toBe(0)
})
it('removes stale suggestions on 409 and requires refresh before another write', async () => {
  const v = setup()
  await open(v)
  await v.find('input').setValue('核对')
  await btn(v, '核对建议')!.trigger('click')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '库存已变化'))
  await btn(v, '确认生成采购需求')!.trigger('click')
  await flushPromises()
  expect(v.text()).toContain('库存已变化')
  expect(btn(v, '核对建议')).toBeUndefined()
  expect(sessionStorage.length).toBe(0)
  await btn(v, '刷新材料需求')!.trigger('click')
  await flushPromises()
  expect(btn(v, '重新计算')).toBeDefined()
})
it('keeps read-only and cancelled orders free from write controls', async () => {
  let v = setup(false)
  await open(v)
  expect(btn(v, '重新计算')).toBeUndefined()
  expect(btn(v, '核对建议')).toBeUndefined()
  v.unmount()
  current.orderStatus = 'CANCELLED'
  v = setup()
  await open(v)
  expect(btn(v, '核对建议')).toBeUndefined()
  expect(v.find('input').exists()).toBe(false)
})
it('shows residual shortage without offering a second active purchase', async () => {
  current.plan.snapshot = JSON.stringify({
    lines: [
      {
        ...line,
        activePurchase: true,
        gap: '4',
        suggested: '0',
        outstanding: '8',
      },
    ],
  })
  const v = setup()
  await open(v)
  expect(v.text()).toContain('待核对原采购')
  expect(btn(v, '核对建议')).toBeUndefined()
})
it('never sends a write if retry credentials cannot be saved', async () => {
  const v = setup()
  await open(v)
  await v.find('input').setValue('核对')
  const storage = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw Error('quota')
    })
  await btn(v, '重新计算')!.trigger('click')
  await flushPromises()
  expect(v.text()).toContain('本次请求未发送')
  expect(
    vi.mocked(request).mock.calls.some((c) => c[1]?.method === 'POST'),
  ).toBe(false)
  storage.mockRestore()
})
it('discards late reads after unmount and rejects malformed saved requests', async () => {
  let resolve!: (v: unknown) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  const v = setup()
  await btn(v, '材料需求与采购建议')!.trigger('click')
  v.unmount()
  resolve(current)
  await flushPromises()
  sessionStorage.setItem(
    'mdop-material-plan-pending:planner:2',
    JSON.stringify({ action: 'delete', body: '{}' }),
  )
  const fresh = setup()
  await flushPromises()
  expect(fresh.text()).toContain('重试凭据无法读取')
  expect(btn(fresh, '刷新材料需求')!.attributes('disabled')).toBeDefined()
})
