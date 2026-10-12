import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import View from '../components/ProductionClosurePanel.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const base = '/api/v1/manufacturing/orders/2/closure'
const key = 'mdop-production-closure:checker:2'
const initial = {
  orderNo: 'MFG-00000002',
  orderStatus: 'APPROVED',
  orderVersion: 3,
  factHash: 'a'.repeat(64),
  canClose: true,
  selfReview: false,
  closureMatches: true,
  costStatus: '未建立成本结算依据',
  closure: null,
  facts: {
    authorized: '10',
    approved: '10',
    stored: '10',
    unit: '件',
    hasVariance: true,
    blockers: [],
    materials: [
      {
        materialId: 1,
        code: 'RAW-01',
        name: '材料',
        unit: '件',
        theoretical: '20',
        issued: '20',
        consumed: '19',
        returned: '1',
        variance: '-1',
      },
    ],
  },
}
function setup(review = true) {
  return mount(View, {
    props: {
      orderId: 2,
      username: 'checker',
      authorities: review
        ? ['manufacturing:read', 'manufacturing:review']
        : ['manufacturing:read'],
    },
  })
}
const btn = (v: VueWrapper, name: string) =>
  v.findAll('button').find((b) => b.text() === name)!
beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  sessionStorage.clear()
  vi.mocked(request).mockResolvedValue(structuredClone(initial))
})
async function fill(v: VueWrapper) {
  await v.get('[aria-label="结束依据"]').setValue('真实材料及成品已核对')
  await v.get('[aria-label="材料差异说明"]').setValue('节约 1 件，余料已退')
  await v.get('input[type="checkbox"]').setValue(true)
}
it('presents material variance and closes with the exact reviewed facts', async () => {
  const v = setup()
  await flushPromises()
  await fill(v)
  expect(v.text()).toContain('净消耗')
  expect(v.text()).toContain('未建立成本结算依据')
  vi.mocked(request).mockResolvedValueOnce({
    ...initial,
    orderStatus: 'COMPLETED',
    canClose: false,
    closure: {
      closed_by: 'checker',
      closed_at: '2026-10-12 10:00:00',
      reason: '核对完成',
      variance_reason: '节约 1 件',
    },
  })
  await v.get('form').trigger('submit')
  await flushPromises()
  const body = JSON.parse(
    vi.mocked(request).mock.calls[vi.mocked(request).mock.calls.length - 1]![1]!
      .body as string,
  )
  expect(body).toMatchObject({
    version: 3,
    factHash: initial.factHash,
    varianceReason: '节约 1 件，余料已退',
  })
  expect(v.text()).toContain('工单已完成')
  expect(v.emitted('closed')).toHaveLength(1)
  expect(sessionStorage.getItem(key)).toBeNull()
  v.unmount()
})
it.each(['reader', 'self', 'blocked'])(
  'withholds closure for %s',
  async (mode) => {
    vi.mocked(request).mockResolvedValueOnce({
      ...initial,
      selfReview: mode === 'self',
      canClose: mode !== 'blocked',
      facts: {
        ...initial.facts,
        blockers: mode === 'blocked' ? ['成品仍待检验'] : [],
      },
    })
    const v = setup(mode !== 'reader')
    await flushPromises()
    expect(v.find('form').exists()).toBe(false)
    if (mode === 'blocked') expect(v.text()).toContain('成品仍待检验')
    v.unmount()
  },
)
it('does not send a material variance without an explanation', async () => {
  const v = setup()
  await flushPromises()
  await v.get('[aria-label="结束依据"]').setValue('核对')
  await v.get('input').setValue(true)
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledTimes(1)
  expect(v.text()).toContain('还需填写差异说明')
  v.unmount()
})
it.each([401, 403, 503])(
  'retains the original unknown closure after %s',
  async (status) => {
    const saved = JSON.stringify({
      idempotencyKey: 'stable',
      version: 3,
      factHash: initial.factHash,
      reason: '核对',
      varianceReason: '节约',
    })
    sessionStorage.setItem(key, saved)
    let v = setup()
    await flushPromises()
    vi.mocked(request).mockRejectedValueOnce(new ApiError(status, 'failed'))
    await btn(v, '原样重试结束请求').trigger('click')
    await flushPromises()
    expect(sessionStorage.getItem(key)).toBe(saved)
    expect(v.text()).toContain('结果待核对')
    v.unmount()
    v = setup()
    await flushPromises()
    await btn(v, '原样重试结束请求').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenLastCalledWith(base, {
      method: 'POST',
      body: saved,
    })
    v.unmount()
  },
)
it('clears stale actionable data on read failure and allows refresh', async () => {
  const v = setup()
  await flushPromises()
  vi.mocked(request).mockRejectedValueOnce(new Error('offline'))
  await btn(v, '刷新核对').trigger('click')
  await flushPromises()
  expect(v.find('form').exists()).toBe(false)
  expect(v.text()).toContain('读取结束核对失败')
  await btn(v, '刷新核对').trigger('click')
  await flushPromises()
  expect(v.find('form').exists()).toBe(true)
  v.unmount()
})
it('drops a rejected stale command and requires an explicit fresh read', async () => {
  const v = setup()
  await flushPromises()
  await fill(v)
  vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '事实已变化'))
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(sessionStorage.getItem(key)).toBeNull()
  expect(v.find('form').exists()).toBe(false)
  expect(v.text()).toContain('事实已变化')
  v.unmount()
})
it('preserves pending credentials when a successful reply arrives after leaving', async () => {
  const v = setup()
  await flushPromises()
  await fill(v)
  let resolve!: (v: unknown) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  await v.get('form').trigger('submit')
  const saved = sessionStorage.getItem(key)
  v.unmount()
  resolve(initial)
  await flushPromises()
  expect(sessionStorage.getItem(key)).toBe(saved)
})
it('does not overwrite a newer refresh with a late response', async () => {
  let resolve!: (v: unknown) => void
  vi.mocked(request).mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r
      }),
  )
  const v = setup()
  v.unmount()
  const second = setup()
  await flushPromises()
  resolve({ ...initial, canClose: false })
  await flushPromises()
  expect(second.find('form').exists()).toBe(true)
  second.unmount()
})
it('blocks writes if retry storage fails', async () => {
  const v = setup()
  await flushPromises()
  await fill(v)
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw Error('storage')
  })
  await v.get('form').trigger('submit')
  await flushPromises()
  expect(request).toHaveBeenCalledTimes(1)
  expect(v.text()).toContain('无法保存重试凭据')
  v.unmount()
})
