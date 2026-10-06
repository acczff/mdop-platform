import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PurchaseReturnsView from '../views/PurchaseReturnsView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
function mock(status = 'PENDING') {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return { items: [{ id: 1, name: '仓库' }] }
    if (path.includes('/candidates'))
      return [
        {
          receipt_item_id: 2,
          receiptId: 3,
          receipt_no: 'R3',
          material_name: '物料',
          material_code: 'M1',
          remaining_qty: '20',
          rejected_qty: '20',
          committed_qty: '0',
        },
      ]
    if (path.endsWith('/history')) return []
    if (path.includes('/purchase-returns?'))
      return [
        {
          id: 4,
          receiptId: 3,
          receiptItemId: 2,
          quantity: '10',
          status,
          version: status === 'PENDING' ? 0 : 1,
          requestedBy: 'alice',
          reason: '不合格退货',
        },
      ]
    return {}
  })
}
async function open(authorities: string[], username = 'bob') {
  const view = mount(PurchaseReturnsView, { props: { authorities, username } })
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '查看 / 处理')!
    .trigger('click')
  await flushPromises()
  return view
}
it('prevents self approval even for administrators', async () => {
  mock()
  const view = await open(['ROLE_ADMIN'], 'alice')
  expect(view.text()).toContain('申请人不能审批自己的申请')
  expect(view.findAll('button').some((b) => b.text() === '确认审批')).toBe(
    false,
  )
})
it('approval does not offer stock deduction and a reviewer cannot create', async () => {
  mock()
  const view = await open([
    'wms:return:read',
    'wms:return:approve',
    'wms:warehouse:1',
  ])
  expect(view.text()).not.toContain('提交退货申请')
  expect(view.text()).not.toContain('确认实际退货并扣库存')
  await view.get('textarea').setValue('同意退货')
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.text()).toContain('库存尚未扣减')
})
it('requires handover acknowledgement and retains request identity on failures', async () => {
  mock('APPROVED')
  const original = vi.mocked(request).getMockImplementation()!
  const keys: string[] = []
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path.endsWith('/confirm')) {
      const body = JSON.parse(options!.body as string)
      keys.push(body.idempotencyKey)
      expect(body.version).toBe(1)
      expect(body.handoverNo).toBe('SHIP-1')
      throw new Error('处理结果未确认')
    }
    return original(path, options)
  })
  const view = await open([
    'wms:return:read',
    'wms:return:confirm',
    'wms:warehouse:1',
  ])
  const button = () =>
    view.findAll('button').find((b) => b.text() === '确认实际退货并扣库存')!
  expect(button().attributes('disabled')).toBeDefined()
  await view.get('input:not([type="checkbox"])').setValue('SHIP-1')
  await view.get('input[type="checkbox"]').setValue(true)
  await view.get('form').trigger('submit')
  await flushPromises()
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBe(keys[1])
  expect(view.get('[role="alert"]').text()).toContain('处理结果未确认')
})
it('completed returns only show history and cannot be cancelled or reconfirmed', async () => {
  mock('RETURNED')
  const view = await open(['ROLE_ADMIN'], 'alice')
  expect(
    view
      .findAll('button')
      .some((b) =>
        ['确认审批', '撤销申请', '确认实际退货并扣库存'].includes(b.text()),
      ),
  ).toBe(false)
})
