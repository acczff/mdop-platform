import { statuses } from './api'

export const CATALOG_LABELS = {
  suppliers: '供应商',
  customers: '客户',
  materials: '物料',
  units: '基本单位',
  organizations: '组织',
  'production-sites': '生产地点',
  locations: '库位',
}

export type CatalogKind = keyof typeof CATALOG_LABELS

export interface CatalogRow {
  id: number
  code: string
  name: string
  status?: string
  version?: number
  unitId?: number
  unit?: string
  trackingMode?: string
  requireDateCode?: boolean
  requireExpiry?: boolean
  identityLocked?: boolean
  warehouseId?: number
  areaType?: string
}

export interface CatalogAudit {
  id: number
  action_type: string
  reason: string
  created_by: string
  created_at: string
  before_state: unknown
  after_state: unknown
}

export interface CatalogDraft {
  code: string
  name: string
  status: string
  unitId: number
  trackingMode: string
  requireDateCode: boolean
  requireExpiry: boolean
  warehouseId: number
  areaType: string
  reason: string
}

export function describeCatalogState(value: unknown) {
  if (!value) return '—'
  try {
    const v = (typeof value === 'string' ? JSON.parse(value) : value) as Record<
      string,
      unknown
    >
    const tracking = v.trackingMode || v.tracking_mode
    const result = [
      String(v.name || ''),
      v.status ? statuses[String(v.status)] : '',
      String(v.unit || ''),
      tracking === 'BATCH' ? '按批次' : tracking === 'QUANTITY' ? '按数量' : '',
    ]
    if ('requireDateCode' in v || 'require_date_code' in v)
      result.push(
        (v.requireDateCode ?? v.require_date_code)
          ? 'Date Code 必填'
          : 'Date Code 非必填',
      )
    if ('requireExpiry' in v || 'require_expiry' in v)
      result.push(
        (v.requireExpiry ?? v.require_expiry) ? '有效期必填' : '有效期非必填',
      )
    return result.filter(Boolean).join(' / ')
  } catch {
    return '记录无法解析，请联系管理员核对'
  }
}
