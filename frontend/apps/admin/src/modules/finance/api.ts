/** Decimal strings only: six-place quantity/price, two-place HALF_UP line amount. */
export function lineAmount(quantity: string, price: string): string {
  function scaled(value: string) {
    if (!/^\d{1,12}(\.\d{1,6})?$/.test(value)) return undefined
    const [whole, fraction = ''] = value.split('.')
    return BigInt(whole!) * 1000000n + BigInt(fraction.padEnd(6, '0'))
  }
  const q = scaled(quantity),
    p = scaled(price)
  if (q === undefined || p === undefined) return ''
  const cents = (q * p + 5000000000n) / 10000000000n
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`
}

export interface Audit {
  id: number
  action: string
  reason: string
  actor: string
  created_at: string
}

export interface Baseline {
  status: string
  version: number
  organization: string | null
  start_date: string | null
  end_date: string | null
  created_by: string | null
  history: Audit[]
}

export interface Fact {
  orderId: number
  orderNumber: string
  orderLineId: number
  factId: number
  factNumber: string
  warehouseId: number
  partyId: number
  partyName: string
  materialCode: string
  materialName: string
  unit: string
  quantity: string
}

export interface Basis {
  id: number
  number: string
  direction: string
  warehouse_id: number
  order_id: number
  order_number: string
  fact_number: string
  fact_id: number
  party_name: string
  material_code: string
  material_name: string
  unit: string
  quantity: string
  unit_price: string
  amount: string
  reference: string
  business_date: string
  reason: string
  status: string
  version: number
  created_by: string
  created_at: string
  reviewed_by: string | null
  reviewed_at: string | null
  open_amount: string | null
  selfReview: boolean
  sourceMatches: boolean
  balanceMatches: boolean
  history: Audit[]
}

export interface PendingFinanceRequest {
  url: string
  body: string
}

export const FINANCE_LABELS: Record<string, string> = {
  UNINITIALIZED: '未初始化',
  PENDING: '待复核',
  ACTIVE: '已启用',
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
}

export const FINANCE_ACTION_NAMES: Record<string, string> = {
  CREATE: '登记依据',
  SUBMIT: '提交复核',
  APPROVE: '确认往来',
  REJECT: '驳回',
  CANCEL: '取消',
  SETUP: '提交初始化',
  ACTIVATE: '启用演示基线',
  REJECT_SETUP: '驳回初始化',
}
