import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReceivingView from '../views/ReceivingView.vue'
import { request } from '../api'
vi.mock('../api', async (original) => ({
  ...(await original<typeof import('../api')>()),
  request: vi.fn(),
}))
afterEach(() => vi.resetAllMocks())
describe('receiving workflow', () => {
  it('saves a draft without submitting and uses a stable key for confirmed submission', async () => {
    let saved = false,
      submitted = false
    const arrival = {
      id: 1,
      warehouseId: 1,
      supplierId: 1,
      externalNoticeNo: 'ERP-01',
      purchaseOrderNo: 'PO-01',
      status: 'PENDING_RECEIPT',
      version: 0,
    }
    const receipt = {
      id: 22,
      receiptNo: 'REC-22',
      arrivalId: 1,
      status: 'DRAFT',
      version: 2,
    }
    const lines = [
      {
        arrivalItemId: 11,
        locationId: 1,
        quantity: 12,
        batchNo: 'B-01',
        dateCode: '',
        productionDate: null,
        expiryDate: null,
      },
    ]
    vi.mocked(request).mockImplementation(async (path, options) => {
      if (path.startsWith('/api/master-data/warehouses'))
        return { items: [{ id: 1, name: '原料仓', status: 'ENABLED' }] }
      if (path === '/api/master-data/suppliers')
        return [{ id: 1, name: '供应商' }]
      if (path === '/api/master-data/materials')
        return [{ id: 1, code: 'MAT-01', name: '物料' }]
      if (path === '/api/master-data/locations')
        return [
          {
            id: 1,
            warehouseId: 1,
            code: 'LOC-01',
            name: '暂存',
            areaType: 'RECEIVING',
          },
        ]
      if (path.endsWith('/capabilities')) return { enabled: true }
      if (path.includes('/inventory?'))
        return submitted
          ? [
              {
                id: 1,
                locationId: 1,
                materialId: 1,
                batchNo: 'B-01',
                onHandQty: 12,
                availableQty: 0,
              },
            ]
          : []
      if (path.includes('/arrival-notices?')) return [arrival]
      if (path === '/api/v1/wms/arrival-notices/1')
        return {
          arrival,
          items: [
            {
              id: 11,
              materialId: 1,
              materialCode: 'MAT-01',
              materialName: '物料',
              unit: '件',
              noticeQty: 100,
              receivedQty: submitted ? 12 : 0,
            },
          ],
        }
      if (path === '/api/v1/wms/arrival-notices/1/receipts') {
        if (options?.method === 'POST') {
          saved = true
          return { receipt, items: lines }
        }
        return saved ? [receipt] : []
      }
      if (path === '/api/v1/wms/receipts/22') return { receipt, items: lines }
      if (path === '/api/v1/wms/receipts/22/submit') {
        submitted = true
        return { receipt: { ...receipt, status: 'SUBMITTED' }, items: lines }
      }
      throw new Error(`Unexpected path ${path}`)
    })
    const wrapper = mount(ReceivingView, {
      props: { authorities: ['ROLE_ADMIN'] },
    })
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '查看与收货')!
      .trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '登记本次收货')!
      .trigger('click')
    await wrapper
      .get('[role="dialog"] input[inputmode="decimal"]')
      .setValue('12')
    await wrapper.get('[placeholder="按批次管理的物料必填"]').setValue('B-01')
    await wrapper.get('[role="dialog"] form').trigger('submit')
    await flushPromises()
    expect(saved).toBe(true)
    expect(submitted).toBe(false)
    expect(wrapper.text()).toContain('库存尚未变化')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '查看 / 编辑草稿')!
      .trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '提交已保存草稿')!
      .trigger('click')
    expect(submitted).toBe(false)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '确认提交')!
      .trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/v1/wms/receipts/22/submit', {
      method: 'POST',
      body: JSON.stringify({ version: 2, idempotencyKey: 'receipt-submit-22' }),
    })
    expect(wrapper.text()).toContain('可用库存仍为 0')
    wrapper.unmount()
  })
})
