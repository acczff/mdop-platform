import type { components } from '@/shared/api'

type WarehouseSchema = components['schemas']['WarehouseResponse']
type WarehousePageSchema = components['schemas']['WarehousePageResponse']

export type WarehousePurpose = NonNullable<WarehouseSchema['purpose']>
export type WarehouseForm = NonNullable<WarehouseSchema['form']>
export type WarehouseManagementCategory = NonNullable<
  WarehouseSchema['managementCategory']
>
export type WarehouseStatus = NonNullable<WarehouseSchema['status']>
export type Warehouse = Omit<Required<WarehouseSchema>, 'remark'> & {
  remark: string | null
}
export type WarehousePage = Omit<Required<WarehousePageSchema>, 'items'> & {
  items: Warehouse[]
}

export const purposes: Record<string, string> = {
  RAW_MATERIAL: '原材料',
  SEMI_FINISHED: '半成品',
  FINISHED_GOODS: '成品',
  LINE_SIDE: '线边',
  MRO: '维修备件',
}

export const forms: Record<string, string> = {
  PHYSICAL: '实体仓库',
  LOGICAL: '逻辑仓库',
}

export const categories: Record<string, string> = {
  GENERAL: '普通',
  HAZARDOUS_CHEMICAL: '危险化学品',
}

export const statuses: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}
