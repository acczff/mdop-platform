import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CountsView from '../views/CountsView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }))
afterEach(() => vi.resetAllMocks())
function setup(username: string, status = 'PENDING') {
  vi.mocked(request).mockImplementation(async (path) =>
    path.includes('warehouses')
      ? { items: [{ id: 1, name: '仓库' }], totalPages: 1 }
      : {
          items: [
            {
              id: 1,
              status,
              created_by: 'counter',
              snapshot_qty: '80',
              counted_qty: '79',
              difference: '-1',
            },
          ],
          page: 0,
          totalPages: 1,
          totalElements: 1,
        },
  )
  return mount(CountsView, {
    props: { username, authorities: ['ROLE_ADMIN'] },
    global: { stubs: { RouterLink: true } },
  })
}
it('hides self approval even for administrators', async () => {
  const view = setup('counter')
  await flushPromises()
  expect(view.findAll('button').some((b) => b.text() === '审核过账')).toBe(
    false,
  )
  expect(view.text()).toContain('取消盘点')
})
it('requires reviewing the difference before posting approval', async () => {
  const view = setup('reviewer')
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '审核过账')!
    .trigger('click')
  expect(view.get('[aria-label="确认盘点处理"]').text()).toContain('差异 -1')
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await view.get('[aria-label="确认盘点处理"] form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledWith('/api/v1/wms/counts/1/approve', {
    method: 'POST',
    body: undefined,
  })
})
it('locks submitted count contents after an uncertain response', async () => {
  const view = setup('counter', 'DRAFT')
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '录入实盘')!
    .trigger('click')
  await view.get('input').setValue('79')
  await view.get('textarea').setValue('损耗')
  vi.mocked(request).mockRejectedValueOnce(new Error('请求超时'))
  await view.get('[aria-label="录入实盘"] form').trigger('submit')
  await flushPromises()
  expect(view.get('fieldset').attributes()).toHaveProperty('disabled')
  const initial = vi.mocked(request).mock.calls.slice(-1)[0]![1]!.body
  await view.get('[aria-label="录入实盘"] form').trigger('submit')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([p]) => p.endsWith('/submit'))
      .slice(-1)[0]![1]!.body,
  ).toBe(initial)
})
