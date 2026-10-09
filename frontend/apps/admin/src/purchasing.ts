export interface Fulfillment {
  canClose: boolean
  outcome: string
  factHash: string
  blockers: string[]
  lines: {
    orderLineId: number
    code: string
    unit: string
    ordered: string
    received: string
    reversed: string
    netReceived: string
    remaining: string
    pendingInspection: string
    pendingPutaway: string
    putaway: string
    rejectedPendingReturn: string
    returned: string
  }[]
  notices: {
    arrangementId: number
    delivery: { arrivalId: number }
    facts: {
      receipts: {
        receiptId: number
        receiptNumber: string
        receiptItemId: number
        quantity: string
        correctionStatus: string
        status: string
        qualityReference: string | null
        qualified: string | null
        rejected: string | null
        putawayLocation: number | null
      }[]
      cases: { id: number; kind: string; status: string; reason: string }[]
      returns: {
        id: number
        receiptItemId: number
        quantity: string
        status: string
        handover: string | null
      }[]
    }
  }[]
}
export interface Closure {
  outcome: string
  fact_hash: string
  snapshot: string
  reason: string
  closed_by: string
  closed_at: string
}
