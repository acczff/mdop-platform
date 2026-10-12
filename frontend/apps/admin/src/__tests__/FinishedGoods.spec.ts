import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import FinishedGoodsView from '../views/FinishedGoodsView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => {
  vi.resetAllMocks()
  window.history.replaceState({}, '', '/')
})
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
  expect(v.text()).toContain('模拟 MES 来源')
  expect(
    v.findAll('button').some((b) => b.text() === '模拟 MES 完工需求'),
  ).toBe(false)
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

it('requires an explicit production date instead of silently defaulting to a UTC date', async () => {
  const v = setup()
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '模拟 MES 完工需求')!
    .trigger('click')
  await flushPromises()
  const input = v.get('[role="dialog"] input[type="date"]')
  expect((input.element as HTMLInputElement).value).toBe('')
  expect(input.attributes()).toHaveProperty('required')
  v.unmount()
})

it.each(['OPEN', 'REJECTED'])(
  'shows distinct pending and rejected execution stages for %s',
  async (status) => {
    const v = setup(status, ['wms:finished:read', 'wms:warehouse:1'])
    await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({
      ...receiptDetail,
      status,
      created_by: 'completionreviewer',
      created_at: '2026-10-11T18:30:24',
      received_by: status === 'REJECTED' ? 'warehouse01' : null,
      received_at: status === 'REJECTED' ? '2026-10-11T18:31:15' : null,
      quality_by: status === 'REJECTED' ? 'qms01' : null,
      quality_at: status === 'REJECTED' ? '2026-10-11T18:31:37' : null,
      quality_reason: status === 'REJECTED' ? '抽检不合格\n保持隔离' : null,
      stored_by: null,
      stored_at: null,
      stored_location: null,
      stored_balance_id: null,
    })
    await v
      .findAll('button')
      .find((b) => b.text() === '查看详情')!
      .trigger('click')
    await flushPromises()
    const progress = v.get('[aria-label="成品入库执行进度"]')
    expect(progress.findAll('article')).toHaveLength(4)
    expect(progress.findAll('.operation-time-note')).toHaveLength(1)
    expect(progress.get('[aria-label="入库需求登记"]').text()).toContain(
      '操作账号completionreviewer',
    )
    expect(progress.get('[aria-label="上架"]').text()).toContain(
      status === 'REJECTED' ? '不合格，禁止上架' : '未上架',
    )
    expect(progress.get('[aria-label="质检判定"]').text()).toContain(
      status === 'REJECTED' ? '判定备注抽检不合格' : '未判定',
    )
    expect(v.text()).not.toContain('确认上架')
    v.unmount()
  },
)

const receiptDetail = {
  id: 77,
  warehouse_id: 1,
  completion_report_id: 6,
  demand_no: 'MFG-C-6',
  completion_report_no: 'BG-001',
  work_order_no: 'MFG-00000001',
  material_name: '成品 A',
  material_code: 'FG-A',
  amount: '6.000000',
  unit: '件',
  batch_no: 'B1',
  status: 'STORED',
  production_date: '2026-10-11',
  expiry_date: null,
  created_by: '登记员',
  created_at: '2026-10-11T01:00:00Z',
  stored_by: '仓管',
  stored_at: '2026-10-11T03:00:00Z',
  stored_location: '成品一区',
  stored_balance_id: 9,
}
it('opens an exact receipt outside the loaded list page for a reader without write actions', async () => {
  window.history.replaceState(
    {},
    '',
    '/finished-goods?warehouseId=1&receiptId=77',
  )
  const v = setup('STORED', ['wms:finished:read', 'wms:warehouse:1'])
  const fallback = vi.mocked(request).getMockImplementation()!
  vi.mocked(request).mockImplementation(async (path, options) =>
    path === '/api/v1/wms/finished-receipts/77'
      ? receiptDetail
      : fallback(path, options),
  )
  await flushPromises()
  const detail = v.get('[aria-label="成品入库详情"]')
  expect(detail.text()).toContain('成品 A（FG-A）')
  expect(detail.text()).toContain('BG-001')
  expect(detail.text()).toContain('6 件')
  expect(detail.text()).toContain('2026-10-11 11:00:00（北京时间）')
  expect(detail.text()).toContain('已上架数量不等于当前库存')
  expect(v.text()).not.toContain('确认上架')
  expect(v.text()).not.toContain('确认实物收货')
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  v.unmount()
})

it('rejects detail from another warehouse and does not retain old detail after a failed read', async () => {
  const v = setup()
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce({
    ...receiptDetail,
    warehouse_id: 2,
  })
  await v
    .findAll('button')
    .find((b) => b.text() === '查看详情')!
    .trigger('click')
  await flushPromises()
  expect(v.text()).toContain('不属于当前仓库')
  expect(v.find('[aria-label="成品入库详情"]').exists()).toBe(false)
  vi.mocked(request).mockResolvedValueOnce(receiptDetail)
  await v
    .findAll('button')
    .find((b) => b.text() === '查看详情')!
    .trigger('click')
  await flushPromises()
  expect(v.find('[aria-label="成品入库详情"]').exists()).toBe(true)
  vi.mocked(request).mockRejectedValueOnce(new Error('断开'))
  await v
    .findAll('button')
    .find((b) => b.text() === '查看详情')!
    .trigger('click')
  await flushPromises()
  expect(v.find('[aria-label="成品入库详情"]').exists()).toBe(false)
  expect(v.text()).toContain('详情读取失败：断开')
  v.unmount()
})

it('returns from focused receipt detail without reloading the list', async () => {
  const v = setup()
  await flushPromises()
  vi.mocked(request).mockResolvedValueOnce(receiptDetail)
  await v
    .findAll('button')
    .find((b) => b.text() === '查看详情')!
    .trigger('click')
  await flushPromises()
  expect(v.get('.business-list').attributes('style')).toContain('display: none')
  const reads = vi.mocked(request).mock.calls.length
  await v
    .findAll('button')
    .find((b) => b.text() === '← 返回列表')!
    .trigger('click')
  await flushPromises()
  expect(v.get('.business-list').attributes('style') || '').not.toContain(
    'display: none',
  )
  expect(vi.mocked(request).mock.calls).toHaveLength(reads)
  v.unmount()
})

it('discards a late detail response after the warehouse is refreshed', async () => {
  const v = setup()
  await flushPromises()
  let resolve!: (value: typeof receiptDetail) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  await v
    .findAll('button')
    .find((b) => b.text() === '查看详情')!
    .trigger('click')
  await v
    .findAll('button')
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  resolve(receiptDetail)
  await flushPromises()
  expect(v.find('[aria-label="成品入库详情"]').exists()).toBe(false)
  v.unmount()
})
