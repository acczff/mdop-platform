import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import UsersView from '../views/UsersView.vue'
import AccountView from '../views/AccountView.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => vi.resetAllMocks())
const user = {
  id: 1,
  username: 'reader',
  displayName: '只读人员',
  enabled: true,
  version: 2,
  roles: ['READER'],
  warehouseIds: [4],
  authorities: ['warehouse:read'],
}
function setup(authorities = ['iam:manage']) {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path === '/api/iam/options')
      return {
        roles: [
          { code: 'READER', name: '业务只读', permissions: ['warehouse:read'] },
        ],
        warehouses: [{ id: 4, code: 'W4', name: '原料仓' }],
      }
    if (path.startsWith('/api/iam/users?page='))
      return { items: [user], totalElements: 1 }
    if (path.endsWith('/audit')) return []
    return undefined
  })
  return mount(UsersView, { props: { username: 'admin', authorities } })
}
it('does not request account data without management permission', async () => {
  const view = setup(['warehouse:read'])
  await flushPromises()
  expect(request).not.toHaveBeenCalled()
  expect(view.text()).toContain('无用户管理权限')
  view.unmount()
})
it('sends version and reason when disabling an account', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '停用')!
    .trigger('click')
  await view.get('textarea').setValue('员工离职')
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledWith('/api/iam/users/1/status', {
    method: 'POST',
    body: JSON.stringify({ version: 2, enabled: false, reason: '员工离职' }),
  })
  expect(view.find('[role="dialog"]').exists()).toBe(false)
  expect(view.text()).toContain('操作成功')
  view.unmount()
})
it('blocks resubmission after an uncertain write until refreshed', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '停用')!
    .trigger('click')
  await view.get('textarea').setValue('停用')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(0, '请求超时'))
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.text()).toContain('核对最新状态')
  expect(
    view
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .attributes('disabled'),
  ).toBeDefined()
  view.unmount()
})
it('distinguishes a saved mutation from failed refresh', async () => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '停用')!
    .trigger('click')
  await view.get('textarea').setValue('停用')
  vi.mocked(request)
    .mockResolvedValueOnce(undefined)
    .mockRejectedValueOnce(new ApiError(500, '列表失败'))
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.text()).toContain('操作已成功，但列表刷新失败')
  expect(view.find('[role="dialog"]').exists()).toBe(false)
  expect(
    view
      .findAll('button')
      .find((b) => b.text() === '新增账号')!
      .attributes('disabled'),
  ).toBeDefined()
  view.unmount()
})
it('keeps the audit dialog locked while awaiting a response', async () => {
  const view = setup()
  await flushPromises()
  let resolve!: (value: unknown) => void
  vi.mocked(request).mockReturnValueOnce(
    new Promise((r) => {
      resolve = r
    }),
  )
  await view
    .findAll('button')
    .find((b) => b.text() === '审计记录')!
    .trigger('click')
  expect(
    view
      .findAll('button')
      .find((b) => b.text() === '关闭')!
      .attributes('disabled'),
  ).toBeDefined()
  resolve([
    {
      id: 1,
      action: 'CREATE',
      actor: 'admin',
      reason: '新增',
      beforeState: null,
      afterState: 'READER',
      createdAt: '2026-10-08',
    },
  ])
  await flushPromises()
  expect(view.text()).toContain('新增账号')
  view.unmount()
})
it('checks password confirmation without sending a request', async () => {
  const view = mount(AccountView, {
    props: { username: 'reader', authorities: [] },
  })
  const inputs = view.findAll('input')
  await inputs[0]!.setValue('old-password-123')
  await inputs[1]!.setValue('new-password-123')
  await inputs[2]!.setValue('different-123')
  await view.get('form').trigger('submit')
  expect(request).not.toHaveBeenCalled()
  expect(view.text()).toContain('两次新密码不一致')
  view.unmount()
})
it('clears passwords and requires login after successful password change', async () => {
  const listener = vi.fn()
  window.addEventListener('session-expired', listener)
  vi.mocked(request).mockResolvedValue(undefined)
  const view = mount(AccountView, {
    props: { username: 'reader', authorities: [] },
  })
  const inputs = view.findAll('input')
  await inputs[0]!.setValue('old-password-123')
  await inputs[1]!.setValue('new-password-123')
  await inputs[2]!.setValue('new-password-123')
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(listener).toHaveBeenCalledOnce()
  expect(
    inputs.every((i) => (i.element as HTMLInputElement).value === ''),
  ).toBe(true)
  window.removeEventListener('session-expired', listener)
  view.unmount()
})
