export interface ManufacturingDirectoryEntry {
  id: number
  code: string
  name: string
  status: string
  unit?: string
}

export interface BomSnapshot {
  id: number
  product_id: number
  product_code: string
  version_label: string
  base_quantity: string
  product_unit: string
  components: {
    material_code: string
    material_name: string
    quantity: string
    unit: string
  }[]
}

export interface ProductionOrderItem {
  id: number
  order_no: string
  status: string
  version: number
  site_id: number
  site_code: string
  site_name: string
  warehouse_name: string
  bom_id: number
  bom_snapshot: string
  planned_date: string
  created_by: string
  last_edited_by: string
  submitted_by: string
  approved_by: string
  approved_at: string
}

export interface ProductionDemand {
  id: number
  warehouse_id: number
  source_type: string
  source_reference: string
  product_id: number
  product_code: string
  product_name: string
  quantity: string
  unit: string
  needed_date: string
  purpose: string
  status: string
  version: number
  created_by: string
  orders: ProductionOrderItem[]
  order_no?: string
  order_status?: string
  history: {
    id: number
    action: string
    reason: string
    created_by: string
    created_at: string
  }[]
}

export interface SalesSourceLine {
  id: number
  document_no: string
  material_code: string
  material_name: string
  quantity: string
  unit: string
}

export interface PendingProductionRequest {
  url: string
  method: string
  body: string
  warehouse: number
}

export interface DemandDraft {
  sourceType: string
  sourceReference: string
  productId: number
  quantity: string
  neededDate: string
  purpose: string
  salesLineId: number
}

export interface OrderDraft {
  siteId: number
  bomId: number
  plannedDate: string
}

const PRODUCTION_LABELS: Record<string, string> = {
  OPEN: '有效需求',
  CANCELLED: '已取消',
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  REJECTED: '已驳回',
  APPROVED: '已批准',
  COMPLETED: '已完成',
  CLOSE: '确认结束',
  create: '新建需求',
  order: '转生产订单',
  edit: '编辑工单',
  submit: '提交审核',
  approve: '批准',
  reject: '驳回',
  cancel: '取消工单',
  'cancel-demand': '取消需求',
  DEMAND: '创建需求',
  ORDER: '转生产订单',
  EDIT: '编辑工单',
  SUBMIT: '提交审核',
  APPROVE: '批准',
  REJECT: '驳回',
  CANCEL: '取消工单',
  CANCEL_DEMAND: '取消需求',
  MATERIAL_CALCULATE: '计算材料需求',
  MATERIAL_PURCHASE: '确认采购建议',
}

export function productionLabel(s: string) {
  return PRODUCTION_LABELS[s] || s
}

export function parseBomSnapshot(
  o: ProductionOrderItem,
): BomSnapshot | undefined {
  try {
    return JSON.parse(o.bom_snapshot) as BomSnapshot
  } catch {
    return undefined
  }
}
