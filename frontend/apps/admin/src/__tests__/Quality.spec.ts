import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import QualityView from '../views/QualityView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
const button = (view: ReturnType<typeof mount>, text: string) =>
  view.findAll('button').find((b) => b.text() === text)!
function mock(inspected = false) {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.includes('/warehouses'))
      return { items: [{ id: 1, name: '仓库' }] }
    if (path.includes('/locations'))
      return [
        {
          id: 2,
          warehouseId: 1,
          name: '正式库位',
          code: 'ST',
          areaType: 'STORAGE',
        },
      ]
    if (path.includes('/quality?'))
      return [
        {
          id: 3,
          receipt_no: 'R3',
          version: 1,
          reference_no: inspected ? 'Q3' : null,
          downstream_stage: 'INSPECTION',
        },
      ]
    if (path.endsWith('/items'))
      return [
        {
          id: 4,
          material_code: 'M1',
          material_name: '物料',
          quantity: '100',
          qualified_qty: inspected ? '80' : null,
          rejected_qty: inspected ? '20' : null,
        },
      ]
    return []
  })
}
it('keeps QMS simulation hidden from putaway operators', async () => {
  mock()
  const view = mount(QualityView, {
    props: {
      authorities: [
        'wms:quality:read',
        'wms:putaway:confirm',
        'wms:warehouse:1',
      ],
    },
  })
  await flushPromises()
  await view.get('button').trigger('click')
  await flushPromises()
  expect(view.text()).not.toContain('接收模拟质检结果')
  expect(view.text()).not.toContain('确认整行上架')
})
it('requires a destination and preserves retry identity on uncertain results', async () => {
  mock(true)
  const original = vi.mocked(request).getMockImplementation()!
  const keys: string[] = []
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path.endsWith('/putaway')) {
      const body = JSON.parse(options!.body as string)
      keys.push(body.idempotencyKey)
      expect(body.locationId).toBe(2)
      throw new Error('请核对后重试')
    }
    return original(path, options)
  })
  const view = mount(QualityView, { props: { authorities: ['ROLE_ADMIN'] } })
  await flushPromises()
  await view.get('button').trigger('click')
  await flushPromises()
  const action = () =>
    view.findAll('button').find((b) => b.text() === '确认整行上架')!
  expect(action().attributes('disabled')).toBeDefined()
  await view.findAll('select')[1]!.setValue(2)
  await action().trigger('click')
  await flushPromises()
  await action().trigger('click')
  await flushPromises()
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBe(keys[1])
  expect(view.get('[role="alert"]').text()).toContain('请核对后重试')
})

it('removes old processing details when the next detail request fails', async () => {
  mock(true)
  const view = mount(QualityView, { props: { authorities: ['ROLE_ADMIN'] } })
  await flushPromises()
  await button(view, '查看 / 处理').trigger('click')
  await flushPromises()
  expect(view.text()).toContain('确认整行上架')
  vi.mocked(request).mockRejectedValueOnce(new Error('明细加载失败'))
  await button(view, '查看 / 处理').trigger('click')
  await flushPromises()
  expect(view.text()).toContain('明细加载失败')
  expect(view.text()).not.toContain('确认整行上架')
  await button(view, '查看 / 处理').trigger('click')
  await flushPromises()
  expect(view.text()).toContain('确认整行上架')
})

it('locks the full putaway request after failure and retries its original contents', async () => {
  mock(true)
  const original = vi.mocked(request).getMockImplementation()!
  const bodies: string[] = []
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path.endsWith('/putaway')) {
      bodies.push(options!.body as string)
      throw new Error('结果未知')
    }
    return original(path, options)
  })
  const view = mount(QualityView, { props: { authorities: ['ROLE_ADMIN'] } })
  await flushPromises()
  await button(view, '查看 / 处理').trigger('click')
  await flushPromises()
  await view.findAll('select')[1]!.setValue(2)
  await button(view, '确认整行上架').trigger('click')
  await flushPromises()
  expect(view.findAll('select')[1]!.attributes()).toHaveProperty('disabled')
  expect(button(view, '查看 / 处理').attributes()).toHaveProperty('disabled')
  await button(view, '确认整行上架').trigger('click')
  await flushPromises()
  expect(bodies).toHaveLength(2)
  expect(bodies[1]).toBe(bodies[0])
})
