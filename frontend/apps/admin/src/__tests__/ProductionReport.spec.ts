import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import View from '../components/ProductionReport.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const props = {
  issueId: 3,
  workOrderNo: 'MFG-00000001',
  warehouseId: 1,
  username: 'recorder',
  authorities: ['wms:production:report'],
}
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  vi.mocked(request).mockResolvedValue([
    { id: 4, warehouseId: 1, areaType: 'STORAGE', name: '原料存储位' },
    { id: 5, warehouseId: 8, areaType: 'STORAGE', name: '外仓' },
  ])
})
it('records actual consumption with reason and immutable source, then refreshes facts', async () => {
  const v = mount(View, { props })
  await flushPromises()
  await v.find('input[inputmode="decimal"]').setValue('19')
  await v.find('textarea').setValue('实物核对')
  await v.find('[type=checkbox]').setValue(true)
  vi.mocked(request).mockResolvedValueOnce({ id: 1 })
  await v.find('form').trigger('submit')
  await flushPromises()
  const call = vi
    .mocked(request)
    .mock.calls.find((c) => c[1]?.method === 'POST')!
  expect(call[0]).toBe('/api/v1/wms/production/consumptions')
  expect(JSON.parse(call[1]!.body as string)).toMatchObject({
    consumption: { issueId: 3, workOrderNo: props.workOrderNo, quantity: '19' },
    reason: '实物核对',
  })
  expect(v.emitted('changed')).toHaveLength(1)
  expect(sessionStorage.length).toBe(0)
  v.unmount()
})
it.each([0, 401, 403, 503])(
  'preserves unknown result across remount and replays same body after HTTP %s',
  async (status) => {
    let v = mount(View, { props })
    await flushPromises()
    await v.find('input[inputmode="decimal"]').setValue('1')
    await v.find('textarea').setValue('实际领用')
    await v.find('[type=checkbox]').setValue(true)
    vi.mocked(request).mockRejectedValueOnce(new ApiError(status, '暂不可用'))
    await v.find('form').trigger('submit')
    await flushPromises()
    const original = vi
      .mocked(request)
      .mock.calls.find((c) => c[1]?.method === 'POST')![1]!.body
    expect(v.text()).toContain('结果待核对')
    v.unmount()
    v = mount(View, { props })
    await flushPromises()
    vi.mocked(request).mockResolvedValueOnce({ id: 9 })
    await v.find('button').trigger('click')
    await flushPromises()
    const calls = vi
      .mocked(request)
      .mock.calls.filter((c) => c[1]?.method === 'POST')
    expect(calls[1]![1]!.body).toBe(original)
    expect(sessionStorage.length).toBe(0)
    v.unmount()
  },
)
it('offers scoped return locations, requires warehouse receipt and keeps formal permission separate', async () => {
  const v = mount(View, { props })
  await flushPromises()
  await v.find('select').setValue('returns')
  expect(v.text()).toContain('原料存储位')
  expect(v.text()).not.toContain('外仓')
  await v.findAll('select')[2]!.setValue('4')
  await v.find('input[inputmode="decimal"]').setValue('1')
  await v.find('textarea').setValue('余料')
  await v.find('[type=checkbox]').setValue(true)
  vi.mocked(request).mockResolvedValueOnce({ id: 1 })
  await v.find('form').trigger('submit')
  await flushPromises()
  expect(vi.mocked(request).mock.calls.slice(-1)[0]![0]).toBe(
    '/api/v1/wms/production/returns',
  )
  expect(v.text()).toContain('等待仓管确认实物')
  v.unmount()
  const read = mount(View, {
    props: { ...props, authorities: ['wms:production:read', 'ROLE_ADMIN'] },
  })
  await flushPromises()
  expect(read.find('form').exists()).toBe(false)
  read.unmount()
})
it('fails closed if retry storage cannot be read', async () => {
  sessionStorage.setItem('mdop-production-report:recorder:3', 'invalid')
  const v = mount(View, { props })
  await flushPromises()
  expect(v.text()).toContain('停止新登记')
  expect(v.find('fieldset').attributes('disabled')).toBeDefined()
  v.unmount()
})
