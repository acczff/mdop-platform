import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProductionReversals from '../components/ProductionReversals.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
function setup(username = 'applicant') {
  vi.mocked(request).mockResolvedValue({
    items: [
      {
        id: 1,
        consumption_id: 2,
        amount: '10',
        status: 'PENDING',
        created_by: 'applicant',
      },
    ],
    page: 0,
    totalPages: 1,
    totalElements: 1,
  })
  return mount(ProductionReversals, {
    props: {
      issueId: 1,
      username,
      authorities: ['ROLE_ADMIN'],
      consumptions: [{ id: 2, quantity: '10' }],
      returns: [],
    },
  })
}
it('hides self approval while allowing own cancellation', async () => {
  const view = setup()
  await flushPromises()
  expect(view.text()).toContain('需另一人审批')
  expect(view.text()).toContain('撤销申请')
  expect(view.findAll('button').some((b) => b.text() === '审批冲正')).toBe(
    false,
  )
})
it('requires reviewer acknowledgement and preserves failed request', async () => {
  const view = setup('reviewer')
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '审批冲正')!
    .trigger('click')
  const dialog = view.get('[role="dialog"]')
  await dialog.get('textarea').setValue('已核对实际物料和纠错报告')
  await dialog.get('form').trigger('submit')
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  await dialog.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request).mockRejectedValueOnce(new Error('请求超时'))
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(dialog.get('fieldset').attributes()).toHaveProperty('disabled')
  expect(view.emitted('changed')).toBeUndefined()
  const body = vi.mocked(request).mock.calls.slice(-1)[0]![1]!.body
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([, o]) => o?.method === 'POST')
      .map(([, o]) => o!.body),
  ).toEqual([body, body])
})

it('notifies the parent after successful approval even when reloading records fails', async () => {
  const view = setup('reviewer')
  await flushPromises()
  await view
    .findAll('button')
    .find((b) => b.text() === '审批冲正')!
    .trigger('click')
  const dialog = view.get('[role="dialog"]')
  await dialog.get('textarea').setValue('实物与原流水已核对')
  await dialog.get('input[type="checkbox"]').setValue(true)
  vi.mocked(request)
    .mockResolvedValueOnce({ status: 'APPROVED' })
    .mockRejectedValueOnce(new Error('刷新冲正记录失败'))
  await dialog.get('form').trigger('submit')
  await flushPromises()
  expect(view.emitted('changed')).toHaveLength(1)
  expect(view.text()).toContain('刷新冲正记录失败')
  expect(view.text()).toContain('冲正处理已保存。')
  expect(view.text()).not.toContain('待审批')
  expect(view.findAll('button').some((b) => b.text() === '审批冲正')).toBe(
    false,
  )
  expect(view.find('[role="dialog"]').exists()).toBe(false)
  expect(
    vi
      .mocked(request)
      .mock.calls.filter(([, options]) => options?.method === 'POST'),
  ).toHaveLength(1)
  view.unmount()
})
