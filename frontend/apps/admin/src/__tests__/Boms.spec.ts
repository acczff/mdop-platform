import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import BomsView from '../views/BomsView.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
})
const base = '/api/v1/manufacturing/boms'
const doc = {
  id: 1,
  product_id: 1,
  product_code: 'FG',
  product_name: '产品',
  product_unit: '件',
  version_label: 'V1',
  base_quantity: '1.000000',
  description: '测试',
  status: 'DRAFT',
  version: 0,
  copied_from_id: null,
  published_at: null,
  disabled_at: null,
  components: [
    {
      material_id: 2,
      material_code: 'RAW',
      material_name: '材料',
      quantity: '2.000000',
      unit: '件',
    },
  ],
  versions: [{ id: 1, version_label: 'V1', status: 'DRAFT' }],
  history: [],
}
function data(path: string, d = doc) {
  if (path.endsWith('/materials'))
    return [
      { id: 1, code: 'FG', name: '产品', unit: '件', status: 'ENABLED' },
      { id: 2, code: 'RAW', name: '材料', unit: '件', status: 'ENABLED' },
    ]
  if (path.includes('?')) return { items: [d], total: 1 }
  return d
}
function setup(
  d = doc,
  permissions = ['bom:read', 'bom:write'],
  username = 'bomuser',
) {
  vi.mocked(request).mockImplementation(async (path) => data(path, d))
  return mount(BomsView, { props: { username, authorities: permissions } })
}
function button(v: VueWrapper, text: string) {
  return v.findAll('button').find((b) => b.text() === text)!
}
async function detail(v: VueWrapper) {
  await flushPromises()
  await button(v, '查看').trigger('click')
  await flushPromises()
}
async function newForm(v: VueWrapper) {
  await flushPromises()
  await button(v, '新建 BOM').trigger('click')
  const dialog = v.get('[role="dialog"]')
  await dialog.findAll('select')[0]!.setValue('1')
  await dialog.findAll('select')[1]!.setValue('2')
  await dialog.get('.component-row input').setValue('2')
  await dialog.findAll('textarea')[0]!.setValue('装配')
  await dialog.findAll('textarea')[1]!.setValue('建立版本')
}
it('restricts writes and keeps published versions immutable', async () => {
  let v = setup(doc, ['bom:read'])
  await detail(v)
  expect(button(v, '新建 BOM')).toBeUndefined()
  expect(button(v, '发布版本')).toBeUndefined()
  v.unmount()
  v = setup({ ...doc, status: 'PUBLISHED' })
  await detail(v)
  expect(button(v, '编辑草稿')).toBeUndefined()
  expect(button(v, '复制新版本')).toBeDefined()
  expect(button(v, '停用版本')).toBeDefined()
  v.unmount()
})
it('creates exact decimal quantities and publishes the selected version', async () => {
  const v = setup()
  await newForm(v)
  await v.get('.component-row input').setValue('2.123456')
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  const sent = vi
    .mocked(request)
    .mock.calls.find(([, o]) => o?.method === 'POST')!
  expect(JSON.parse(sent[1]!.body as string)).toMatchObject({
    productId: 1,
    baseQuantity: '1',
    components: [{ materialId: 2, quantity: '2.123456' }],
  })
  await button(v, '发布版本').trigger('click')
  await v.get('[role="dialog"] textarea').setValue('核对用量')
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.some(
        ([path, o]) =>
          path === `${base}/1/actions/publish` &&
          JSON.parse(o!.body as string).version === 0,
      ),
  ).toBe(true)
  v.unmount()
})
it('copies into a new explicit version without modifying the source', async () => {
  const v = setup({ ...doc, status: 'PUBLISHED' })
  await detail(v)
  await button(v, '复制新版本').trigger('click')
  await v.get('[role="dialog"] input').setValue('V2')
  await v.get('[role="dialog"] textarea').setValue('新版本')
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  const sent = vi
    .mocked(request)
    .mock.calls.find(([p]) => p === `${base}/1/copy`)!
  expect(sent[1]?.method).toBe('POST')
  expect(JSON.parse(sent[1]!.body as string)).toMatchObject({
    version: 0,
    versionLabel: 'V2',
  })
  v.unmount()
})
it('retains the original uncertain request across remount and retries it unchanged', async () => {
  let v = setup()
  await newForm(v)
  vi.mocked(request).mockImplementation(async (path, o) => {
    if (o?.method === 'POST') throw new ApiError(0, '连接中断')
    return data(path)
  })
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  const first = vi
    .mocked(request)
    .mock.calls.find(([, o]) => o?.method === 'POST')!
  expect(v.text()).toContain('原样重试')
  expect(button(v, '新建 BOM').attributes('disabled')).toBeDefined()
  v.unmount()
  v = setup()
  await flushPromises()
  await button(v, '原样重试').trigger('click')
  await flushPromises()
  const calls = vi
    .mocked(request)
    .mock.calls.filter(([, o]) => o?.method === 'POST')
  expect(calls[calls.length - 1]![0]).toBe(first[0])
  expect(calls[calls.length - 1]![1]?.body).toBe(first[1]?.body)
  expect(sessionStorage.getItem('mdop-bom-pending:bomuser')).toBeNull()
  v.unmount()
})
it('clears stale details after rejected writes and never leaves old actions', async () => {
  const v = setup()
  await detail(v)
  await button(v, '发布版本').trigger('click')
  await v.get('[role="dialog"] textarea').setValue('发布')
  vi.mocked(request).mockRejectedValue(new ApiError(409, '循环引用'))
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('循环引用')
  expect(v.find('[aria-label="BOM 详情"]').exists()).toBe(false)
  expect(sessionStorage.getItem('mdop-bom-pending:bomuser')).toBeNull()
  v.unmount()
})
it('failed or late reads cannot restore stale actionable detail', async () => {
  const v = setup()
  await detail(v)
  let resolve!: (value: typeof doc) => void
  vi.mocked(request).mockImplementation(async (path) =>
    path === `${base}/1`
      ? new Promise<typeof doc>((r) => {
          resolve = r
        })
      : data(path),
  )
  await button(v, '查看').trigger('click')
  await v.get('form.toolbar').trigger('submit')
  await flushPromises()
  resolve(doc)
  await flushPromises()
  expect(v.find('[aria-label="BOM 详情"]').exists()).toBe(false)
  vi.mocked(request).mockRejectedValue(new ApiError(500, '不可读取'))
  await button(v, '查看').trigger('click')
  await flushPromises()
  expect(v.text()).toContain('详情读取失败')
  expect(button(v, '发布版本')).toBeUndefined()
  v.unmount()
})
it('stops sending if retry storage is unavailable or corrupt', async () => {
  const v = setup()
  await newForm(v)
  const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('denied')
  })
  await v.get('[role="dialog"] form').trigger('submit')
  await flushPromises()
  expect(v.text()).toContain('本次请求未发送')
  expect(
    vi.mocked(request).mock.calls.filter(([, o]) => o?.method === 'POST'),
  ).toHaveLength(0)
  spy.mockRestore()
  v.unmount()
  sessionStorage.setItem('mdop-bom-pending:bomuser', '{')
  const broken = setup()
  await flushPromises()
  expect(broken.text()).toContain('本页已停止新操作')
  broken.unmount()
})
