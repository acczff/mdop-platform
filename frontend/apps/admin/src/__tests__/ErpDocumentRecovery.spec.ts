import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import PurchasingView from '../views/PurchasingView.vue'
import SalesOrdersView from '../views/SalesOrdersView.vue'
import { ApiError, request } from '../api'

vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => {
  vi.restoreAllMocks()
  vi.resetAllMocks()
  sessionStorage.clear()
})
const button = (view: VueWrapper, label: string) =>
  view.findAll('button').find((item) => item.text() === label)!

describe.each([
  ['purchasing', PurchasingView],
  ['sales', SalesOrdersView],
] as const)('%s document recovery', (module, component) => {
  const base = `/api/v1/${module}/documents`
  const storageKey = `mdop-${module}-pending:operator`
  const document = {
    id: 10,
    document_no: 'DOC-10',
    kind: 'REQUEST',
    warehouse_id: 1,
    warehouse_name: '业务仓',
    status: 'DRAFT',
    version: 0,
    purpose: '核对数量',
    needed_date: '2026-12-01',
    customer_id: 3,
    customer_name: '客户',
    created_by: 'operator',
    last_edited_by: 'operator',
    submitted_by: null,
    lines: [
      {
        id: 1,
        material_id: 2,
        material_code: 'M2',
        material_name: '物料',
        unit: '件',
        quantity: '10',
      },
    ],
    history: [],
    related: [],
    arrangements: [],
    closure: null,
    closureMatches: true,
    fulfillment: { lines: [], blockers: [], arrangements: [], canClose: false },
  }
  const pending = {
    url: base,
    method: 'POST',
    warehouse: 1,
    body: JSON.stringify({ idempotencyKey: 'original-key' }),
  }
  function data(path: string, unit = '件') {
    if (path.includes('/warehouses?'))
      return {
        items: [
          {
            id: 1,
            name: '业务仓',
            purpose: module === 'sales' ? 'FINISHED_GOODS' : 'RAW_MATERIAL',
            status: 'ENABLED',
          },
        ],
        totalPages: 1,
      }
    if (path.endsWith('/materials'))
      return [{ id: 2, code: 'M2', name: '物料', unit, status: 'ENABLED' }]
    if (path.endsWith('/customers') || path.endsWith('/suppliers'))
      return [{ id: 3, code: 'C3', name: '往来单位', status: 'ENABLED' }]
    if (path.includes('?')) return { items: [document], total: 1 }
    return document
  }
  function setup(permissions = [`${module}:write`]) {
    vi.mocked(request).mockImplementation(async (path) => data(path))
    return mount(component, {
      props: {
        username: 'operator',
        authorities: [
          `${module}:read`,
          'warehouse:read',
          'wms:warehouse:1',
          ...permissions,
        ],
      },
    })
  }

  it('reloads material units when refreshing before editing a draft', async () => {
    const view = setup()
    await flushPromises()
    vi.mocked(request).mockImplementation(async (path) => data(path, '箱'))
    await button(view, '刷新').trigger('click')
    await flushPromises()
    await button(view, '查看').trigger('click')
    await flushPromises()
    await button(view, '编辑').trigger('click')
    expect(view.get('.draft-line').text()).toContain('箱')
    view.unmount()
  })

  it('shows the failure and applies retry permission inside an open edit dialog', async () => {
    const view = setup()
    await flushPromises()
    await button(view, '查看').trigger('click')
    await flushPromises()
    await button(view, '编辑').trigger('click')
    vi.mocked(request).mockRejectedValueOnce(new ApiError(0, '网络中断'))
    await view.get('[role="dialog"] form').trigger('submit')
    await flushPromises()
    expect(view.get('[role="dialog"]').text()).toContain('网络中断')
    await view.setProps({ authorities: [`${module}:read`, 'wms:warehouse:1'] })
    expect(
      button(view, '结果待核对，原样重试').attributes('disabled'),
    ).toBeDefined()
    expect(view.get('[role="dialog"]').text()).toContain('无原操作权限')
    expect(sessionStorage.getItem(storageKey)).not.toBeNull()
    view.unmount()
  })

  it('retains pending operations after the original write permission is removed', async () => {
    sessionStorage.setItem(storageKey, JSON.stringify(pending))
    const view = setup([])
    await flushPromises()
    expect(button(view, '原样重试').attributes('disabled')).toBeDefined()
    expect(view.text()).toContain('无原操作权限')
    expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(pending))
    expect(
      vi.mocked(request).mock.calls.some(([, options]) => options?.method),
    ).toBe(false)
    view.unmount()
  })

  it.each([401, 403])(
    'does not discard an uncertain operation after HTTP %s on retry',
    async (status) => {
      sessionStorage.setItem(storageKey, JSON.stringify(pending))
      const view = setup()
      await flushPromises()
      vi.mocked(request).mockRejectedValueOnce(
        new ApiError(status, '权限已失效'),
      )
      await button(view, '原样重试').trigger('click')
      await flushPromises()
      expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(pending))
      expect(view.text()).toContain('结果待核对')
      view.unmount()
    },
  )

  it('uses the original review permission when recovering an approval', async () => {
    const approval = { ...pending, url: `${base}/10/actions/approve` }
    sessionStorage.setItem(storageKey, JSON.stringify(approval))
    const view = setup()
    await flushPromises()
    expect(button(view, '原样重试').attributes('disabled')).toBeDefined()
    await view.setProps({
      authorities: [`${module}:read`, `${module}:review`, 'wms:warehouse:1'],
    })
    expect(button(view, '原样重试').attributes('disabled')).toBeUndefined()
    await view.setProps({ authorities: [`${module}:read`, `${module}:review`] })
    expect(button(view, '原样重试').attributes('disabled')).toBeDefined()
    expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(approval))
    view.unmount()
  })

  it('locks recovery if a rejected request cannot be removed from storage', async () => {
    sessionStorage.setItem(storageKey, JSON.stringify(pending))
    const view = setup()
    await flushPromises()
    const remove = vi
      .spyOn(Storage.prototype, 'removeItem')
      .mockImplementation(() => {
        throw new Error('denied')
      })
    vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '版本冲突'))
    await button(view, '原样重试').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('无法清理重试凭据')
    expect(button(view, '原样重试').attributes('disabled')).toBeDefined()
    expect(button(view, '刷新').attributes('disabled')).toBeDefined()
    expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(pending))
    view.unmount()
    remove.mockRestore()
  })

  it('ignores an older list response after a retried write is rejected', async () => {
    sessionStorage.setItem(storageKey, JSON.stringify(pending))
    const view = setup()
    await flushPromises()
    let resolve!: (value: unknown) => void
    vi.mocked(request).mockImplementation(async (path, options) => {
      if (options?.method) throw new ApiError(409, '版本冲突')
      if (path.startsWith(base + '?'))
        return new Promise((done) => {
          resolve = done
        })
      return data(path)
    })
    await button(view, '刷新').trigger('click')
    await flushPromises()
    await button(view, '原样重试').trigger('click')
    await flushPromises()
    resolve({ items: [{ ...document, purpose: '迟到列表' }], total: 1 })
    await flushPromises()
    expect(view.text()).not.toContain('迟到列表')
    expect(view.text()).toContain('操作被拒绝')
    view.unmount()
  })

  it('reports unavailable session storage and blocks new requests', async () => {
    const read = vi
      .spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => {
        throw new Error('denied')
      })
    const errors: unknown[] = []
    const view = mount(component, {
      props: {
        username: 'operator',
        authorities: [`${module}:write`, `${module}:read`, 'wms:warehouse:1'],
      },
      global: { config: { errorHandler: (error) => errors.push(error) } },
    })
    await flushPromises()
    expect(errors).toEqual([])
    expect(view.text()).toContain('无法读取重试凭据')
    expect(request).not.toHaveBeenCalled()
    view.unmount()
    read.mockRestore()
  })
})
