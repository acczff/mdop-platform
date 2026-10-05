import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WarehousesView from '../views/WarehousesView.vue'
import { ApiError, request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
const warehouse = {
  id: 1,
  code: 'WH-01',
  name: '原料仓',
  purpose: 'RAW_MATERIAL',
  form: 'PHYSICAL',
  managementCategory: 'GENERAL',
  status: 'ENABLED',
  version: 2,
  updatedBy: 'admin',
  updatedAt: '2026-10-05T00:00:00Z',
}
const page = { items: [warehouse], totalElements: 1, totalPages: 1 }
afterEach(() => vi.resetAllMocks())
describe('warehouse management', () => {
  it('protects structural fields and preserves the form on stale update', async () => {
    vi.mocked(request)
      .mockResolvedValueOnce(page)
      .mockResolvedValueOnce(warehouse)
    const wrapper = mount(WarehousesView, {
      props: { authorities: ['ROLE_ADMIN'] },
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '编辑')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="dialog"] select').attributes()).toHaveProperty(
      'disabled',
    )
    vi.mocked(request).mockRejectedValueOnce(new ApiError(409, '版本冲突'))
    await wrapper.get('[role="dialog"] form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').text()).toContain('重新打开')
    expect(request).toHaveBeenLastCalledWith(
      '/api/master-data/warehouses/1',
      expect.objectContaining({
        method: 'PUT',
        body: expect.stringContaining('"version":2'),
      }),
    )
    wrapper.unmount()
  })
  it('provides a read-only view without mutation actions', async () => {
    vi.mocked(request).mockResolvedValueOnce(page)
    const wrapper = mount(WarehousesView, { props: { authorities: [] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('新增仓库')
    expect(wrapper.findAll('button').map((b) => b.text())).not.toContain('停用')
    expect(wrapper.text()).toContain('详情')
    wrapper.unmount()
  })
})
