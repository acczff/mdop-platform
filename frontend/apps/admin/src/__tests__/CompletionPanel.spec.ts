import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import View from '../components/CompletionPanel.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const base = '/api/v1/manufacturing/orders/2/completions'
const storageKey = 'mdop-completion:recorder:2'
const report = {
  id: 8,
  report_no: 'DONE-6',
  quantity: '6',
  batch_no: 'B1',
  production_date: '2026-01-01',
  reason: '实际完工',
  status: 'SUBMITTED',
  version: 0,
  created_by: 'recorder',
  receipt: undefined as object | undefined,
}
const initial = {
  orderStatus: 'APPROVED',
  authorized: '10',
  pending: '6',
  approved: '0',
  remaining: '4',
  unit: '件',
  reports: [report],
}
const btn = (v: VueWrapper, text: string) =>
  v.findAll('button').find((b) => b.text() === text)
function setup(name = 'recorder', permission = 'report') {
  return mount(View, {
    props: {
      orderId: 2,
      username: name,
      authorities: [
        'manufacturing:read',
        `manufacturing:completion:${permission}`,
      ],
    },
    global: { stubs: { RouterLink: true } },
  })
}
beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  sessionStorage.clear()
  vi.mocked(request).mockResolvedValue(structuredClone(initial))
})
async function acknowledge(v: VueWrapper) {
  await v
    .find('input[placeholder="填写实际完工依据或复核意见"]')
    .setValue('核对实际产量')
  await v.find('input[type=checkbox]').setValue(true)
}
it('records explicit report batch and date without inferring output from materials', async () => {
  const v = setup()
  await flushPromises()
  const inputs = v.find('fieldset').findAll('input')
  for (const [index, value] of [
    'DONE-4',
    '4',
    'B2',
    '2026-01-02',
    '',
    '',
  ].entries())
    await inputs[index]!.setValue(value)
  await acknowledge(v)
  await v.find('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find(([, opts]) => opts?.method === 'POST')!
  expect(call[0]).toBe(base)
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({
    reportNo: 'DONE-4',
    quantity: '4',
    batchNo: 'B2',
    productionDate: '2026-01-02',
    reason: '核对实际产量',
  })
  expect(sessionStorage.getItem(storageKey)).toBeNull()
  v.unmount()
})
it.each([401, 403, 503, 0])(
  'retains original report across remount and retry after %s',
  async (code) => {
    const saved = {
      path: '',
      body: JSON.stringify({
        idempotencyKey: 'stable',
        reportNo: 'DONE-4',
        quantity: '4',
      }),
    }
    sessionStorage.setItem(storageKey, JSON.stringify(saved))
    let v = setup()
    await flushPromises()
    vi.mocked(request).mockRejectedValueOnce(
      code ? new ApiError(code, 'failed') : new Error('timeout'),
    )
    await btn(v, '原样重试完工请求')!.trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(storageKey)).toBe(JSON.stringify(saved))
    v.unmount()
    v = setup()
    await flushPromises()
    await btn(v, '原样重试完工请求')!.trigger('click')
    await flushPromises()
    const calls = vi
      .mocked(request)
      .mock.calls.filter(([, o]) => o?.method === 'POST')
    expect(calls).toHaveLength(2)
    expect(calls[0]![1]!.body).toBe(calls[1]![1]!.body)
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    v.unmount()
  },
)
it('limits independent review and hides actions for readers', async () => {
  let v = setup('recorder', 'review')
  await flushPromises()
  expect(btn(v, '批准完工')).toBeUndefined()
  v.unmount()
  v = setup('checker', 'review')
  await flushPromises()
  await acknowledge(v)
  await btn(v, '批准完工')!.trigger('click')
  await flushPromises()
  expect(
    vi
      .mocked(request)
      .mock.calls.some(
        ([url, o]) => url === base + '/8/approve' && o?.method === 'POST',
      ),
  ).toBe(true)
  v.unmount()
  v = setup('reader', 'read')
  await flushPromises()
  expect(v.find('form').exists()).toBe(false)
  expect(btn(v, '批准完工')).toBeUndefined()
  expect(btn(v, '撤销登记')).toBeUndefined()
  v.unmount()
})
it('clears rejected version command and reloads current facts', async () => {
  const v = setup()
  await flushPromises()
  await acknowledge(v)
  vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '版本变化'))
  await btn(v, '撤销登记')!.trigger('click')
  await flushPromises()
  expect(sessionStorage.getItem(storageKey)).toBeNull()
  expect(v.text()).toContain('版本变化')
  expect(request).toHaveBeenLastCalledWith(base)
  v.unmount()
})
it('stops writes when retry storage is unavailable', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw Error('disabled')
  })
  const v = setup()
  await flushPromises()
  expect(v.text()).toContain('重试凭据无法读取')
  expect(v.find('fieldset').attributes('disabled')).toBeDefined()
  expect(
    vi.mocked(request).mock.calls.some(([, o]) => o?.method === 'POST'),
  ).toBe(false)
  v.unmount()
})
it('invalidates late reads when the original write is retried', async () => {
  let resolve!: (value: typeof initial) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  sessionStorage.setItem(
    storageKey,
    JSON.stringify({ path: '', body: '{"idempotencyKey":"stable"}' }),
  )
  const v = setup()
  await flushPromises()
  const done = structuredClone(initial)
  done.approved = '6'
  done.pending = '0'
  vi.mocked(request).mockResolvedValueOnce(done)
  await btn(v, '原样重试完工请求')!.trigger('click')
  await flushPromises()
  resolve(initial)
  await flushPromises()
  expect(v.text()).toContain('已批准 6')
  v.unmount()
})

it('does not erase retry evidence when a response arrives after leaving the order', async () => {
  const saved = JSON.stringify({
    path: '',
    body: '{"idempotencyKey":"old-report"}',
  })
  sessionStorage.setItem(storageKey, saved)
  const v = setup()
  await flushPromises()
  let finish!: (value: typeof initial) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await btn(v, '原样重试完工请求')!.trigger('click')
  v.unmount()
  const newer = JSON.stringify({
    path: '',
    body: '{"idempotencyKey":"new-report"}',
  })
  sessionStorage.setItem(storageKey, newer)
  finish(initial)
  await flushPromises()
  expect(sessionStorage.getItem(storageKey)).toBe(newer)
})
