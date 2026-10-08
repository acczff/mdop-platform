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
function setup(authorities = ['iam:manage'], totalElements = 1) {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path === '/api/iam/options')
      return {
        roles: [
          { code: 'READER', name: '业务只读', permissions: ['warehouse:read'] },
        ],
        warehouses: [{ id: 4, code: 'W4', name: '原料仓' }],
      }
    if (path.startsWith('/api/iam/users?page='))
      return { items: [user], totalElements }
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
it.each([
  new ApiError(0, '请求超时'),
  new ApiError(409, '版本冲突'),
  new ApiError(502, '网关响应失败'),
  new SyntaxError('响应内容不完整'),
])('requires a successful refresh after write failure: %s', async (failure) => {
  const view = setup()
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '停用')!
    .trigger('click')
  await view.get('textarea').setValue('停用')
  vi.mocked(request).mockRejectedValueOnce(failure)
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.text()).toContain('核对最新状态')
  expect(
    view
      .findAll('button')
      .find((b) => b.text() === '保存')!
      .attributes('disabled'),
  ).toBeDefined()
  await view
    .findAll('button')
    .find((b) => b.text() === '取消')!
    .trigger('click')
  expect(view.text()).not.toContain('只读人员')
  const create = () =>
    view.findAll('button').find((b) => b.text() === '新增账号')!
  expect(create().attributes('disabled')).toBeDefined()
  await create().trigger('click')
  expect(view.find('[role="dialog"]').exists()).toBe(false)
  vi.mocked(request).mockRejectedValueOnce(new ApiError(500, '刷新失败'))
  await view
    .findAll('button')
    .find((b) => b.text() === '刷新列表')!
    .trigger('click')
  await flushPromises()
  expect(create().attributes('disabled')).toBeDefined()
  await view
    .findAll('button')
    .find((b) => b.text() === '刷新列表')!
    .trigger('click')
  await flushPromises()
  expect(create().attributes('disabled')).toBeUndefined()
  await view
    .findAll('button')
    .find((b) => b.text() === '停用')!
    .trigger('click')
  expect(view.find('[role="dialog"]').exists()).toBe(true)
  view.unmount()
})
it.each(['users', 'options'])(
  'clears stale rows when paginating fails in %s',
  async (source) => {
    const view = setup(['iam:manage'], 51)
    await flushPromises()
    if (source === 'options')
      vi.mocked(request).mockResolvedValueOnce({
        items: [{ ...user, username: 'other' }],
        totalElements: 51,
      })
    vi.mocked(request).mockRejectedValueOnce(new ApiError(500, '列表读取失败'))
    await view
      .findAll('button')
      .find((b) => b.text() === '下一页')!
      .trigger('click')
    await flushPromises()
    expect(view.text()).toContain('列表读取失败')
    expect(view.text()).not.toContain('只读人员')
    expect(view.text()).not.toContain('第 2 页')
    expect(
      view
        .findAll('button')
        .find((b) => b.text() === '下一页')!
        .attributes('disabled'),
    ).toBeDefined()
    view.unmount()
  },
)
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
