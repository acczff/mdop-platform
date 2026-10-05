import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MessagesView from '../views/MessagesView.vue'
import { request } from '../api'
vi.mock('../api', () => ({ request: vi.fn() }))
afterEach(() => vi.resetAllMocks())
it('requires a reason, replays the original message and refreshes its state', async () => {
  let replayed = false
  vi.mocked(request).mockImplementation(async (path, options) => {
    if (path.endsWith('/results') || path.endsWith('/rejections')) return []
    if (path.endsWith('/summary'))
      return { INBOX: [], OUTBOX: [], unconfirmedResults: 0 }
    if (path.endsWith('/history')) return []
    if (path.endsWith('/replay')) {
      replayed = true
      expect(JSON.parse(options!.body as string)).toEqual({
        reason: '基础资料已修复',
      })
      return undefined
    }
    return [
      {
        message_id: 'original-id',
        status: replayed ? 'PENDING' : 'DEAD',
        attempts: 5,
        last_error: 'BUSINESS_REJECTED',
      },
    ]
  })
  const wrapper = mount(MessagesView, {
    props: { authorities: ['ROLE_ADMIN'] },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '查看记录')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.find('form button').attributes('disabled')).toBeDefined()
  await wrapper.find('input').setValue('基础资料已修复')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledWith(
    '/api/integration/messages/INBOX/original-id/replay',
    expect.objectContaining({ method: 'POST' }),
  )
  expect(wrapper.text()).toContain('待处理')
  expect(wrapper.text()).toContain('已安排重放')
})
it('preserves the reason and shows a failure without claiming replay succeeded', async () => {
  vi.mocked(request).mockImplementation(async (path) => {
    if (path.endsWith('/results') || path.endsWith('/rejections')) return []
    if (path.endsWith('/summary'))
      return { INBOX: [], OUTBOX: [], unconfirmedResults: 0 }
    if (path.endsWith('/history')) return []
    if (path.endsWith('/replay')) throw new Error('消息正在处理，请刷新')
    return [{ message_id: 'id', status: 'FAILED', attempts: 1 }]
  })
  const wrapper = mount(MessagesView, {
    props: { authorities: ['ROLE_ADMIN'] },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '查看记录')!
    .trigger('click')
  await flushPromises()
  await wrapper.find('input').setValue('恢复后重试')
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('消息正在处理')
  expect(wrapper.find('input').element.value).toBe('恢复后重试')
  expect(wrapper.text()).not.toContain('已安排重放')
})
