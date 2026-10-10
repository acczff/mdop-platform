import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PurchaseFulfillmentPanel from '../components/PurchaseFulfillmentPanel.vue'
import type { Fulfillment } from '../purchasing'

const value: Fulfillment = {
  canClose: false,
  outcome: 'WITH_RETURNS',
  factHash: 'snapshot',
  blockers: ['M01不合格待实际退供 20.000000'],
  lines: [
    {
      orderLineId: 1,
      code: 'M01',
      unit: '件',
      ordered: '100.000000',
      received: '120.000000',
      reversed: '20.000000',
      netReceived: '100.000000',
      remaining: '0.000000',
      pendingInspection: '0.000000',
      pendingPutaway: '0.000000',
      putaway: '80.000000',
      rejectedPendingReturn: '20.000000',
      returned: '0.000000',
    },
  ],
  notices: [
    {
      arrangementId: 1,
      delivery: { arrivalId: 2 },
      facts: {
        receipts: [
          {
            receiptId: 3,
            receiptNumber: 'REC-3',
            receiptItemId: 4,
            quantity: '100.000000',
            correctionStatus: 'NONE',
            status: 'SUBMITTED',
            qualityReference: 'Q-1',
            qualified: '80.000000',
            rejected: '20.000000',
            putawayLocation: 6,
          },
        ],
        cases: [],
        returns: [
          {
            id: 5,
            receiptItemId: 4,
            quantity: '20.000000',
            status: 'APPROVED',
            handover: null,
          },
        ],
      },
    },
  ],
}
it('distinguishes net receipt, putaway and pending return and blocks premature closure', async () => {
  const view = mount(PurchaseFulfillmentPanel, {
    props: {
      value,
      matches: true,
      canWrite: true,
      blocked: false,
      authorities: [],
    },
  })
  expect(view.text()).toContain('净收货 100.000000')
  expect(view.text()).toContain('冲正 20.000000')
  expect(view.text()).toContain('M01不合格待实际退供')
  expect(view.findAll('button')[1]!.attributes('disabled')).toBeDefined()
  expect(view.findAll('a')).toHaveLength(0)
  await view.findAll('button')[0]!.trigger('click')
  expect(view.emitted('refresh')).toHaveLength(1)
})
it('preserves closure snapshot when current source facts change', () => {
  const view = mount(PurchaseFulfillmentPanel, {
    props: {
      value,
      closure: {
        outcome: 'WITH_RETURNS',
        fact_hash: 'old',
        snapshot: JSON.stringify({
          ...value,
          lines: [{ ...value.lines[0], returned: '20.000000' }],
        }),
        reason: '已交接',
        closed_by: 'buyer',
        closed_at: '2026-10-09',
      },
      matches: false,
      canWrite: false,
      blocked: false,
      authorities: [],
    },
  })
  expect(view.get('[role="alert"]').text()).toContain('结案后事实发生变化')
  expect(view.text()).toContain('含退供结案')
  expect(view.text()).toContain('实际退供 20.000000')
  expect(view.findAll('button')).toHaveLength(1)
})
it('offers closure only after all blockers clear and respects unknown pending writes', async () => {
  const view = mount(PurchaseFulfillmentPanel, {
    props: {
      value: { ...value, canClose: true, blockers: [] },
      matches: true,
      canWrite: true,
      blocked: false,
      authorities: ['wms:return:read'],
    },
  })
  expect(view.get('a').attributes('href')).toBe('/purchase-returns')
  await view.findAll('button')[1]!.trigger('click')
  expect(view.emitted('close')).toHaveLength(1)
  await view.setProps({ blocked: true })
  expect(
    view.findAll('button').every((b) => b.attributes('disabled') !== undefined),
  ).toBe(true)
})
