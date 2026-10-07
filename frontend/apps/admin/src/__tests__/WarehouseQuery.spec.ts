import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InventoryView from '../views/InventoryView.vue'
import SalesView from '../views/SalesView.vue'
import FinishedGoodsView from '../views/FinishedGoodsView.vue'
import CountsView from '../views/CountsView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
afterEach(() => vi.resetAllMocks())
it('production reversal ledger must display its original transaction reference', async () => {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return { items: [{ id: 1, name: '原料仓' }], totalPages: 1 }
    if (path.includes('/transactions'))
      return {
        items: [
          {
            id: 2,
            transaction_type: 'PRC_RESTORE',
            issue_id: 1,
            production_reversal_id: 1,
            reversed_transaction_id: 17,
            before_qty: '8',
            change_qty: '2',
            after_qty: '10',
          },
        ],
        page: 0,
        totalPages: 1,
        totalElements: 1,
      }
    return {
      items: [
        {
          id: 8,
          material_name: 'M',
          quality_status: 'QUALIFIED',
          on_hand_qty: '10',
          available_qty: '0',
        },
      ],
      page: 0,
      totalPages: 1,
      totalElements: 1,
    }
  })
  const v = mount(InventoryView, {
    props: { authorities: ['ROLE_ADMIN'] },
    global: { stubs: { RouterLink: true } },
  })
  await flushPromises()
  await v
    .findAll('button')
    .find((b) => b.text() === '查看流水')!
    .trigger('click')
  await flushPromises()
  expect(v.text()).toContain('冲正原流水 #17')
  expect(v.text()).toContain('生产冲正 PCR-1')
  expect(v.text()).toContain('领料 MI-1')
  v.unmount()
})
it.each([
  ['sales', SalesView],
  ['finished', FinishedGoodsView],
  ['counts', CountsView],
] as const)(
  '%s warehouse query failure must clear previous warehouse documents',
  async (_kind, component) => {
    let failed = true
    vi.mocked(request).mockImplementation(async (path) => {
      if (path.includes('/warehouses'))
        return {
          items: [
            { id: 1, name: 'A仓', purpose: 'FINISHED_GOODS' },
            { id: 2, name: 'B仓', purpose: 'FINISHED_GOODS' },
          ],
          totalPages: 1,
        }
      if (path.includes('/capabilities')) return { enabled: false }
      if (path.includes('warehouseId=2') && failed)
        throw new Error('B仓查询失败')
      return {
        items: [
          {
            id: 1,
            demand_no: path.includes('warehouseId=2')
              ? 'B-WAREHOUSE-DOC'
              : 'A-WAREHOUSE-DOC',
            work_order_no: 'WO-A',
            sales_order_no: 'SO-A',
            material_name: path.includes('warehouseId=2')
              ? 'B-WAREHOUSE-DOC'
              : 'A-WAREHOUSE-DOC',
            amount: '1',
            status: _kind === 'counts' ? 'PENDING' : 'OPEN',
            created_by: 'another-user',
            snapshot_qty: '10',
            counted_qty: '9',
            difference: '-1',
          },
        ],
        page: 0,
        totalElements: 1,
        totalPages: 1,
      }
    })
    const v = mount(component, {
      props: { authorities: ['ROLE_ADMIN'], username: 'admin' },
      global: { stubs: { RouterLink: true } },
    })
    await flushPromises()
    expect(v.text()).toContain('A-WAREHOUSE-DOC')
    await v.get('select').setValue(2)
    await flushPromises()
    expect(v.text()).toContain('B仓查询失败')
    expect(v.text()).not.toContain('A-WAREHOUSE-DOC')
    expect(v.text()).not.toContain('取消单据')
    expect(v.text()).not.toContain('确认实物收货')
    expect(v.findAll('button').some((b) => b.text() === '审核过账')).toBe(false)
    failed = false
    await v
      .findAll('button')
      .find((b) => b.text() === (_kind === 'counts' ? '刷新记录' : '刷新'))!
      .trigger('click')
    await flushPromises()
    expect(v.text()).toContain('B-WAREHOUSE-DOC')
    expect(v.text()).not.toContain('A-WAREHOUSE-DOC')
    expect(v.text()).not.toContain('B仓查询失败')
    v.unmount()
  },
)
