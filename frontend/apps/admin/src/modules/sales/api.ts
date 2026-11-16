import { scaled } from '@/shared/quantity'

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

export interface SalesDirectoryEntry {
  id: number
  code: string
  name: string
  status: string
  unit?: string
}

export interface PendingSalesRequest {
  url: string
  method: string
  body: string
  warehouse: number
}

export interface SalesOrderDraft {
  purpose: string
  neededDate: string
  customerId: number
  customerReference: string
  lines: { materialId: number; quantity: string }[]
}

export const SALES_STATUSES: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  FULFILLING: '履约中',
  CLOSED: '已结案',
  PENDING: '待送达',
  DELIVERED: '已送达',
  WITHDRAWN: '已撤回',
  OPEN: '待预占',
  RESERVED: '待拣货',
  PICKED: '待独立复核',
  VERIFIED: '待实际出库',
  SHIPPED: '已出库',
}

export const SALES_ACTIONS: Record<string, string> = {
  submit: '提交审核',
  approve: '批准',
  reject: '驳回',
  cancel: '取消',
  arrange: '安排发货',
  deliver: '送达 WMS',
  withdraw: '撤回安排',
  close: '履约结案',
}

export const SALES_AUDIT_NAMES: Record<string, string> = {
  CREATE: '建单',
  EDIT: '编辑',
  SUBMIT: '提交审核',
  APPROVE: '批准',
  REJECT: '驳回',
  CANCEL: '取消',
  ARRANGE: '安排发货',
  DELIVER: '送达 WMS',
  WITHDRAW: '撤回安排',
  DELIVERY_FAILED: '送达失败',
  CLOSE: '履约结案',
}

export function stateText(value: string) {
  return SALES_STATUSES[value] || value
}

export function allocatedQuantity(
  arrangements: SalesArrangement[] | undefined,
  lineId: number,
) {
  const sum = (arrangements || [])
    .filter((a) => a.status !== 'WITHDRAWN' && a.order_line_id === lineId)
    .reduce((n, a) => n + scaled(a.quantity), 0n)
  return `${sum / 1000000n}.${(sum % 1000000n).toString().padStart(6, '0')}`
}

export function describeSnapshot(raw: string | null) {
  if (!raw) return '无前值'
  try {
    const d = JSON.parse(raw) as SalesDocument
    return [
      stateText(d.status),
      d.purpose,
      `${d.warehouse_name} · ${d.needed_date}`,
      d.customer_name,
      ...d.lines.map(
        (l) =>
          `${l.material_code} · ${l.material_name}：${l.quantity} ${l.unit}`,
      ),
      ...(d.arrangements || []).map(
        (a) =>
          `发货 #${a.id} · ${stateText(a.status)} · ${a.expected_date} · ${a.quantity}${a.last_error ? ' · ' + a.last_error : ''}`,
      ),
    ]
      .filter(Boolean)
      .join('\n')
  } catch {
    return '快照暂时无法展示，请联系管理员核对。'
  }
}
