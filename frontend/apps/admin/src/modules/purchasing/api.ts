import { scaled } from '@/shared/quantity'

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
        arrangementLineId?: number
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

export type Kind = 'REQUEST' | 'ORDER'

export interface PurchaseLine {
  id: number
  source_line_id: number | null
  material_id: number
  material_code: string
  material_name: string
  unit: string
  quantity: string
}

export interface Arrangement {
  id: number
  expected_date: string
  status: string
  last_error: string | null
  wms_arrival_id: number | null
  lines: {
    id: number
    order_line_id: number
    material_code: string
    quantity: string
    wms_arrival_item_id: number | null
  }[]
  wms?: {
    status: string
    lines: { arrangementLineId: number; receivedQty: string }[]
    receipts: { id: number; number: string; status: string }[]
  }
}

export interface PurchaseDocument {
  displayLocations?: { id: number; code: string; name: string }[]
  id: number
  document_no: string
  kind: Kind
  warehouse_id: number
  warehouse_name: string
  status: string
  version: number
  purpose: string
  needed_date: string
  supplier_id: number | null
  supplier_name: string | null
  request_id: number | null
  original_order_id?: number | null
  source_type?: string
  production_order_no?: string | null
  production_plan_line_id?: number | null
  created_by: string
  last_edited_by: string
  submitted_by: string | null
  lines: PurchaseLine[]
  history: {
    id: number
    action: string
    reason: string
    created_by: string
    created_at: string
    before_state: string | null
    after_state: string
  }[]
  related: { id: number; document_no: string; status: string }[]
  arrangements?: Arrangement[]
  fulfillment?: Fulfillment
  closure?: Closure | null
  closureMatches?: boolean
}

export interface DirectoryEntry {
  id: number
  code: string
  name: string
  status: string
  unit?: string
}

export interface PendingRequest {
  url: string
  method: string
  body: string
  warehouse: number
}

export interface PurchaseDraft {
  purpose: string
  neededDate: string
  supplierId: number
  lines: { materialId: number; quantity: string }[]
}

export const PURCHASE_STATUSES: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CONVERTED: '已转单',
  CANCELLED: '已取消',
  FULFILLING: '履约中',
  CLOSED: '已结案',
  PENDING: '待送达',
  DELIVERED: '已送达',
  WITHDRAWN: '已撤回',
}

export const PURCHASE_ACTIONS: Record<string, string> = {
  submit: '提交审核',
  approve: '批准',
  reject: '驳回',
  cancel: '取消',
  convert: '转采购订单',
  arrange: '安排到货',
  deliver: '送达 WMS',
  withdraw: '撤回安排',
  close: '履约结案',
}

export const PURCHASE_AUDIT_NAMES: Record<string, string> = {
  PRODUCTION: '生产采购建议',
  CREATE: '建单',
  EDIT: '编辑',
  SUBMIT: '提交审核',
  APPROVE: '批准',
  REJECT: '驳回',
  CANCEL: '取消',
  CONVERT: '转采购订单',
  RELEASE_ORDER: '释放订单占用',
  ARRANGE: '安排到货',
  DELIVER: '送达 WMS',
  WITHDRAW: '撤回安排',
  DELIVERY_FAILED: '送达失败',
  CLOSE: '履约结案',
}

export function stateText(value: string) {
  return PURCHASE_STATUSES[value] || value
}

export function statusTone(value: string) {
  if (['CLOSED', 'CONVERTED', 'DELIVERED'].includes(value)) return 'complete'
  if (['APPROVED', 'FULFILLING'].includes(value)) return 'active'
  if (['SUBMITTED', 'PENDING'].includes(value)) return 'waiting'
  if (value === 'REJECTED') return 'rejected'
  return 'neutral'
}

export function allocatedQuantity(
  arrangements: Arrangement[] | undefined,
  lineId: number,
) {
  const value = (arrangements || [])
    .filter((a) => a.status !== 'WITHDRAWN')
    .flatMap((a) => a.lines)
    .filter((l) => l.order_line_id === lineId)
    .reduce((sum, l) => sum + scaled(l.quantity), 0n)
  return `${value / 1000000n}.${(value % 1000000n).toString().padStart(6, '0')}`
}

export function describeSnapshot(raw: string | null) {
  if (!raw) return '无前值'
  try {
    const d = JSON.parse(raw) as PurchaseDocument
    return [
      stateText(d.status),
      d.purpose,
      `${d.warehouse_name} · ${d.needed_date}`,
      d.supplier_name,
      d.original_order_id ? `补货原订单 #${d.original_order_id}` : '',
      ...d.lines.map(
        (l) =>
          `${l.material_code} · ${l.material_name}：${l.quantity} ${l.unit}`,
      ),
      ...(d.arrangements || []).map(
        (a) =>
          `到货 #${a.id} · ${stateText(a.status)} · ${a.expected_date} · ${a.lines.map((l) => `${l.material_code} ${l.quantity}`).join('，')}${a.last_error ? ' · ' + a.last_error : ''}`,
      ),
    ]
      .filter(Boolean)
      .join('\n')
  } catch {
    return '快照暂时无法展示，请联系管理员核对。'
  }
}
