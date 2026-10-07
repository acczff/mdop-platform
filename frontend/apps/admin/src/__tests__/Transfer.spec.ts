import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import InventoryView from '../views/InventoryView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
function mock() {
  const stock = {
    id: 1,
    warehouse_id: 1,
    location_id: 1,
    material_name: '物料',
    quality_status: 'QUALIFIED',
    on_hand_qty: '80.000000',
    available_qty: '80.000000',
  }
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return { items: [{ id: 1, name: '仓库' }], totalPages: 1 }
    if (path.includes('/locations'))
      return [
        { id: 1, warehouseId: 1, areaType: 'STORAGE', code: 'A', name: '原位' },
        {
          id: 2,
          warehouseId: 1,
          areaType: 'STORAGE',
          code: 'B',
          name: '目标位',
        },
        { id: 3, warehouseId: 2, areaType: 'STORAGE', name: '其他仓' },
      ]
    if (path === '/api/v1/wms/stock/1') return stock
    return { items: [stock], page: 0, totalPages: 1, totalElements: 1 }
  })
}
it('does not expose movement to inventory-only readers', async () => {
  mock()
  const view = mount(InventoryView, {
    props: { authorities: ['wms:inventory:read', 'wms:warehouse:1'] },
    global: { stubs: { RouterLink: true } },
  })
  await flushPromises()
  expect(view.findAll('button').some((b) => b.text() === '移库')).toBe(false)
})
it('requires physical confirmation and keeps the original payload on uncertain retry', async () => {
  mock()
  const original = vi.mocked(request).getMockImplementation()!
  const bodies: string[] = []
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path === '/api/v1/wms/transfers') {
      bodies.push(options!.body as string)
      if (bodies.length === 1) throw new Error('请求超时')
      return { id: 7 }
    }
    return original(path, options)
  })
  const view = mount(InventoryView, {
    props: { authorities: ['ROLE_ADMIN'] },
    global: { stubs: { RouterLink: true } },
  })
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '移库')!
    .trigger('click')
  await flushPromises()
  const form = view.get('[aria-label="确认仓内移库"] form')
  expect(form.get('select').text()).not.toContain('其他仓')
  await form.get('select').setValue(2)
  await form.get('input[inputmode="decimal"]').setValue('30.123456')
  await form.get('textarea').setValue('调整存放位置')
  expect(form.get('button').attributes('disabled')).toBeDefined()
  await form.get('input[type="checkbox"]').setValue(true)
  await form.trigger('submit')
  await flushPromises()
  expect(form.get('fieldset').attributes('disabled')).toBeDefined()
  await form.trigger('submit')
  await flushPromises()
  expect(bodies[0]).toBe(bodies[1])
  expect(JSON.parse(bodies[0]!).quantity).toBe('30.123456')
  expect(view.text()).toContain('移库 TR-7 已完成')
})
