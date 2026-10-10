export interface SalesArrangement {
  id: number
  order_line_id: number
  quantity: string
  expected_date: string
  status: string
  wms_sales_id: number | null
  last_error: string | null
  wms?: {
    id: number
    status: string
    shippedQuantity: string
    batchNo: string | null
    ledgerCount: number
  }
}
export interface SalesFulfillment {
  canClose: boolean
  factHash: string
  blockers: string[]
  lines: {
    orderLineId: number
    code: string
    unit: string
    ordered: string
    allocated: string
    shipped: string
    remaining: string
  }[]
}
export interface SalesDocument {
  id: number
  document_no: string
  warehouse_id: number
  warehouse_name: string
  customer_id: number
  customer_name: string
  customer_reference: string | null
  status: string
  version: number
  purpose: string
  needed_date: string
  created_by: string
  last_edited_by: string
  submitted_by: string | null
  lines: {
    id: number
    material_id: number
    material_code: string
    material_name: string
    unit: string
    quantity: string
  }[]
  arrangements: SalesArrangement[]
  fulfillment: SalesFulfillment
  closure: {
    snapshot: string
    fact_hash: string
    reason: string
    closed_by: string
    closed_at: string
  } | null
  closureMatches: boolean
  history: {
    id: number
    action: string
    reason: string
    created_by: string
    created_at: string
    before_state: string | null
    after_state: string
  }[]
}
