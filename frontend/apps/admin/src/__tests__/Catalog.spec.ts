import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import CatalogView from '../views/CatalogView.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => vi.resetAllMocks())
const supplier = {
  id: 1,
  code: 'SUP-01',
  name: '原供应商',
  status: 'ENABLED',
  version: 3,
}
const material = {
  id: 2,
  code: 'MAT-01',
  name: '批次物料',
  status: 'ENABLED',
  version: 4,
  unitId: 3,
  unit: '件',
  trackingMode: 'BATCH',
  identityLocked: true,
  requireDateCode: true,
  requireExpiry: false,
}
async function data(path: string) {
  if (path.endsWith('/suppliers')) return [supplier]
  if (path.endsWith('/materials')) return [material]
  if (path.endsWith('/units'))
    return [
      { id: 3, code: 'UNIT-PIECE', name: '件', status: 'ENABLED', version: 0 },
    ]
  if (path.endsWith('/organizations'))
    return [{ id: 1, code: 'ORG-01', name: '演示工厂', version: 0 }]
  if (path.includes('/warehouses?')) return { items: [] }
  return []
}
function setup(authorities = ['masterdata:write']) {
  vi.mocked(request).mockImplementation(data)
  return mount(CatalogView, { props: { authorities } })
}
function button(view: VueWrapper, text: string) {
  return view.findAll('button').find((b) => b.text() === text)!
}
it('loads every directory but exposes no maintenance actions to readers', async () => {
  const view = setup(['warehouse:read'])
  await flushPromises()
  expect(view.text()).toContain('原供应商')
  expect(view.text()).not.toContain('＋ 新增')
  expect(button(view, '编辑资料')).toBeUndefined()
  expect(button(view, '变更历史')).toBeUndefined()
  await button(view, '客户').trigger('click')
  expect(view.text()).toContain('暂无匹配资料')
  view.unmount()
})
it('sends version and reason and keeps referenced identity fields locked', async () => {
  const view = setup()
  await flushPromises()
  await button(view, '物料').trigger('click')
  await button(view, '编辑资料').trigger('click')
  for (const name of [
    'code',
    'unitId',
    'trackingMode',
    'requireDateCode',
    'requireExpiry',
  ])
    expect(
      view.get('[name="' + name + '"]').attributes('disabled'),
    ).toBeDefined()
  await view.get('[name="name"]').setValue('名称修订')
  await view.get('[name="status"]').setValue('DISABLED')
  await view.get('textarea').setValue('停产')
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledWith('/api/master-data/materials/2', {
    method: 'PUT',
    body: JSON.stringify({
      version: 4,
      name: '名称修订',
      status: 'DISABLED',
      unitId: 3,
      trackingMode: 'BATCH',
      requireDateCode: true,
      requireExpiry: false,
      reason: '停产',
    }),
  })
  expect(view.text()).toContain('物料已保存')
  view.unmount()
})
it.each([
  new ApiError(0, '超时'),
  new ApiError(409, '版本冲突'),
  new ApiError(502, '网关失败'),
  new SyntaxError('响应无效'),
])(
  'blocks resubmission and dialog bypass until successful refresh: %s',
  async (failure) => {
    const view = setup()
    await flushPromises()
    await button(view, '编辑资料').trigger('click')
    await view.get('textarea').setValue('变更')
    vi.mocked(request).mockRejectedValueOnce(failure)
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(button(view, '保存').attributes('disabled')).toBeDefined()
    await button(view, '取消').trigger('click')
    expect(view.text()).not.toContain('原供应商')
    expect(button(view, '＋ 新增供应商').attributes('disabled')).toBeDefined()
    vi.mocked(request).mockRejectedValueOnce(new ApiError(500, '刷新失败'))
    await button(view, '刷新').trigger('click')
    await flushPromises()
    expect(button(view, '＋ 新增供应商').attributes('disabled')).toBeDefined()
    await button(view, '刷新').trigger('click')
    await flushPromises()
    expect(button(view, '＋ 新增供应商').attributes('disabled')).toBeUndefined()
    view.unmount()
  },
)
it('distinguishes a committed write from a failed reload', async () => {
  const view = setup()
  await flushPromises()
  await button(view, '编辑资料').trigger('click')
  await view.get('textarea').setValue('核对')
  vi.mocked(request)
    .mockResolvedValueOnce(supplier)
    .mockRejectedValueOnce(new ApiError(500, '列表失败'))
  await view.get('form').trigger('submit')
  await flushPromises()
  expect(view.find('form').exists()).toBe(false)
  expect(view.text()).toContain('供应商已保存；列表刷新未成功')
  expect(button(view, '＋ 新增供应商').attributes('disabled')).toBeDefined()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([, options]) => options?.method === 'PUT'),
  ).toHaveLength(1)
  view.unmount()
})
it('does not publish a late directory when another directory has failed', async () => {
  let complete!: (v: unknown) => void
  const view = setup()
  await flushPromises()
  vi.mocked(request).mockImplementation((path) =>
    path.endsWith('/suppliers')
      ? new Promise((resolve) => {
          complete = resolve
        })
      : path.endsWith('/units')
        ? Promise.reject(new ApiError(500, '单位不可用'))
        : data(path),
  )
  await button(view, '刷新').trigger('click')
  await flushPromises()
  complete([supplier])
  await flushPromises()
  expect(view.text()).not.toContain('原供应商')
  expect(view.text()).toContain('单位不可用')
  expect(button(view, '＋ 新增供应商').attributes('disabled')).toBeDefined()
  view.unmount()
})
it('ignores history responses from a closed dialog and shows the current history error', async () => {
  const view = setup()
  await flushPromises()
  let complete!: (v: unknown) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        complete = resolve
      }),
  )
  await button(view, '变更历史').trigger('click')
  await button(view, '关闭').trigger('click')
  vi.mocked(request).mockRejectedValueOnce(new ApiError(500, '历史查询失败'))
  await button(view, '变更历史').trigger('click')
  await flushPromises()
  complete([
    {
      id: 1,
      action_type: 'UPDATE',
      reason: '过时原因',
      created_by: 'old',
      before_state: '{}',
      after_state: '{}',
    },
  ])
  await flushPromises()
  expect(view.text()).toContain('历史查询失败')
  expect(view.text()).not.toContain('过时原因')
  view.unmount()
})
it('prevents a second organization and creates a material only using a registered unit id', async () => {
  const view = setup()
  await flushPromises()
  await button(view, '组织').trigger('click')
  expect(button(view, '＋ 新增组织').attributes('disabled')).toBeDefined()
  await button(view, '物料').trigger('click')
  await button(view, '＋ 新增物料').trigger('click')
  await view.get('[name="code"]').setValue('MAT-NEW')
  await view.get('[name="name"]').setValue('新料')
  await view.get('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find(([, options]) => options?.method === 'POST')!
  expect(JSON.parse(call[1]!.body as string)).toEqual({
    code: 'MAT-NEW',
    name: '新料',
    unitId: 3,
    trackingMode: 'BATCH',
    requireDateCode: false,
    requireExpiry: false,
  })
  view.unmount()
})

it('combines status and keyword filters with honest loaded counts and resets on category change', async () => {
  const view = setup(['warehouse:read'])
  vi.mocked(request).mockImplementation(async (path) =>
    path.endsWith('/suppliers')
      ? [
          supplier,
          {
            ...supplier,
            id: 9,
            code: 'STOP-01',
            name: '停用供应商',
            status: 'DISABLED',
          },
        ]
      : data(path),
  )
  await flushPromises()
  await button(view, '刷新').trigger('click')
  await flushPromises()
  expect(view.text()).toContain('只读')
  expect(view.text()).toContain('已加载 2 条 · 匹配 2 条')
  await view.get('.catalog-filters select').setValue('DISABLED')
  expect(view.text()).toContain('已加载 2 条 · 匹配 1 条')
  expect(view.text()).not.toContain('原供应商')
  await view.get('.catalog-filters input').setValue('not-found')
  expect(view.text()).toContain('已加载 2 条 · 匹配 0 条')
  await button(view, '清空筛选').trigger('click')
  expect(view.text()).toContain('已加载 2 条 · 匹配 2 条')
  await view.get('.catalog-filters select').setValue('DISABLED')
  await button(view, '物料').trigger('click')
  expect(view.text()).toContain('批次物料')
  await button(view, '组织').trigger('click')
  expect(view.find('.catalog-filters select').exists()).toBe(false)
  view.unmount()
})

it('supports arrow and boundary keys between category tabs', async () => {
  const view = setup(['warehouse:read'])
  await flushPromises()
  await view.get('[role="tablist"]').trigger('keydown', { key: 'ArrowRight' })
  expect(view.get('#catalog-tab-customers').attributes('aria-selected')).toBe(
    'true',
  )
  expect(view.get('#catalog-tab-suppliers').attributes('tabindex')).toBe('-1')
  await view.get('[role="tablist"]').trigger('keydown', { key: 'End' })
  expect(view.get('[role="tabpanel"]').attributes('aria-labelledby')).toBe(
    'catalog-tab-locations',
  )
  await view.get('[role="tablist"]').trigger('keydown', { key: 'Home' })
  expect(view.get('#catalog-tab-suppliers').attributes('aria-selected')).toBe(
    'true',
  )
  view.unmount()
})

it('discloses the load cap rather than calling loaded rows the database total', async () => {
  const view = setup()
  vi.mocked(request).mockImplementation(async (path) =>
    path.endsWith('/suppliers')
      ? Array.from({ length: 1000 }, (_, id) => ({ ...supplier, id }))
      : data(path),
  )
  await flushPromises()
  await button(view, '刷新').trigger('click')
  await flushPromises()
  expect(view.text()).toContain('已达到 1000 条加载上限')
  expect(view.text()).toContain('可能未包含全部记录')
  view.unmount()
})
