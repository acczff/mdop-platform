import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InventoryView from '../views/InventoryView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())

function mock() {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return {
        items: [
          { id: 1, name: '一号仓' },
          { id: 2, name: '二号仓' },
        ],
        totalPages: 1,
      }
    if (path.includes('/transactions'))
      return {
        items: [
          {
            id: 9,
            transaction_type: 'RETURN_OUT',
            receipt_no: 'R-1',
            external_notice_no: 'ERP-1',
            purchase_order_no: 'PO-1',
            before_qty: '20.000000',
            change_qty: '-12.000000',
            after_qty: '8.000000',
            purchase_return_id: 3,
            created_by: '仓管',
            created_at: '2026-10-06',
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
          warehouse_name: '一号仓',
          material_name: '物料',
          material_code: 'M1',
          unit: '件',
          quality_status: 'REJECTED',
          batch_no: 'B1',
          on_hand_qty: '8.000001',
          available_qty: '0.000000',
        },
      ],
      page:
        new URL(path, 'http://localhost').searchParams.get('page') === '1'
          ? 1
          : 0,
      totalElements: 21,
      totalPages: 2,
    }
  })
}
it('keeps applied filters when paging and excludes unscoped warehouses', async () => {
  mock()
  const view = mount(InventoryView, {
    props: { authorities: ['wms:inventory:read', 'wms:warehouse:1'] },
  })
  await flushPromises()
  expect(view.get('select').text()).not.toContain('二号仓')
  await view.findAll('input')[0]!.setValue('已查询条件')
  await view.get('form').trigger('submit')
  await flushPromises()
  await view.findAll('input')[0]!.setValue('尚未提交条件')
  await view
    .findAll('button')
    .find((b) => b.text() === '下一页')!
    .trigger('click')
  await flushPromises()
  const query = new URL(
    vi.mocked(request).mock.calls[vi.mocked(request).mock.calls.length - 1]![0],
    'http://localhost',
  ).searchParams
  expect(query.get('keyword')).toBe('已查询条件')
  expect(query.get('page')).toBe('1')
  expect(view.text()).toContain('8.000001')
})
it('shows return provenance and clears stale results when a search fails', async () => {
  mock()
  const view = mount(InventoryView, { props: { authorities: ['ROLE_ADMIN'] } })
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '查看流水')!
    .trigger('click')
  await flushPromises()
  expect(view.text()).toContain('退货 RT-3')
  expect(view.text()).toContain('PO-1')
  expect(view.text()).toContain('-12.000000')
  vi.mocked(request).mockRejectedValueOnce(new Error('连接失败'))
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.get('[role="alert"]').text()).toContain('连接失败')
  expect(view.text()).not.toContain('退货 RT-3')
  expect(view.text()).not.toContain('8.000001')
})
