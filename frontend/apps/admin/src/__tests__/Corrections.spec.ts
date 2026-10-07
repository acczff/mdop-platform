import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CorrectionsView from '../views/CorrectionsView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
const item = {
  id: 7,
  kind: 'REVERSAL',
  arrivalId: 1,
  receiptId: 2,
  reason: '误收',
  status: 'PENDING',
  version: 3,
  requestedBy: 'alice',
}
function mock() {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.startsWith('/api/master-data/warehouses'))
      return { items: [{ id: 1, name: '验收仓' }] }
    if (path.includes('/corrections?')) return [item]
    if (path.endsWith('/history')) return []
    if (path.includes('/arrival-notices?')) return []
    return undefined
  })
}
it('prevents self approval even for an administrator', async () => {
  mock()
  const wrapper = mount(CorrectionsView, {
    props: { authorities: ['ROLE_ADMIN'], username: 'alice' },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '查看 / 审批')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('不能审批自己的申请')
  expect(wrapper.findAll('button').some((b) => b.text() === '确认审批')).toBe(
    false,
  )
})
it('reviewer can approve with version and retains the idempotency key after a failure', async () => {
  mock()
  let originalKey = ''
  let attempts = 0
  const original = vi.mocked(request).getMockImplementation()!
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path.endsWith('/decision')) {
      const body = JSON.parse(options!.body as string)
      expect(body.version).toBe(3)
      if (!originalKey) originalKey = body.idempotencyKey
      expect(body.idempotencyKey).toBe(originalKey)
      attempts++
      throw new Error('处理结果未确认，请刷新核对')
    }
    return original(path, options)
  })
  const wrapper = mount(CorrectionsView, {
    props: {
      authorities: [
        'wms:correction:read',
        'wms:correction:approve',
        'wms:warehouse:1',
      ],
      username: 'bob',
    },
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('登记申请')
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '查看 / 审批')!
    .trigger('click')
  await flushPromises()
  await wrapper.get('textarea').setValue('核对后同意')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('处理结果未确认')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(attempts).toBe(2)
})
it('hides approval actions from a request-only user', async () => {
  mock()
  const wrapper = mount(CorrectionsView, {
    props: {
      authorities: [
        'wms:correction:read',
        'wms:correction:create',
        'wms:warehouse:1',
      ],
      username: 'receiver',
    },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '查看 / 审批')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.findAll('button').some((b) => b.text() === '确认审批')).toBe(
    false,
  )
})

it('clears old applications after a failed refresh and restores them on retry', async () => {
  mock()
  const view = mount(CorrectionsView, {
    props: { authorities: ['ROLE_ADMIN'], username: 'bob' },
  })
  await flushPromises()
  vi.mocked(request).mockRejectedValueOnce(new Error('查询失败'))
  await view
    .findAll('button')
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(view.text()).toContain('查询失败')
  expect(view.text()).not.toContain('查看 / 审批')
  await view
    .findAll('button')
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(view.text()).toContain('查看 / 审批')
})

it('does not offer approval when its audit request fails', async () => {
  mock()
  const view = mount(CorrectionsView, {
    props: { authorities: ['ROLE_ADMIN'], username: 'bob' },
  })
  await flushPromises()
  vi.mocked(request).mockRejectedValueOnce(new Error('审计加载失败'))
  await view
    .findAll('button')
    .find((b) => b.text() === '查看 / 审批')!
    .trigger('click')
  await flushPromises()
  expect(view.text()).toContain('审计加载失败')
  expect(view.text()).not.toContain('确认审批')
})
