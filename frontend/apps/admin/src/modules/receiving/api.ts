import type { components } from '@/shared/api'

export type Supplier = Pick<
  Required<components['schemas']['Supplier']>,
  'id' | 'code' | 'name'
>

export type Material = Pick<
  Required<components['schemas']['Material']>,
  | 'id'
  | 'code'
  | 'name'
  | 'unit'
  | 'trackingMode'
  | 'requireDateCode'
  | 'requireExpiry'
>

export interface Location extends Supplier {
  warehouseId: number
  areaType: string
}

export type Arrival = Pick<
  Required<components['schemas']['Arrival']>,
  | 'id'
  | 'externalNoticeNo'
  | 'purchaseOrderNo'
  | 'supplierId'
  | 'warehouseId'
  | 'status'
  | 'version'
> &
  Pick<components['schemas']['Arrival'], 'supplierCode' | 'supplierName'>
export interface ArrivalItem {
  id: number
  materialId: number
  materialCode: string
  materialName: string
  unit: string
  noticeQty: string
  receivedQty: string
}
export interface ArrivalDetail {
  arrival: Arrival
  items: ArrivalItem[]
}
export interface Receipt {
  id: number
  receiptNo: string
  arrivalId: number
  status: string
  version: number
  correctionStatus?: string
  downstreamStage?: string
}
export interface ReceiptLine {
  arrivalItemId: number
  locationId: number
  quantity: string | number
  batchNo: string
  dateCode: string
  productionDate: string | null
  expiryDate: string | null
}
export interface ReceiptDetail {
  receipt: Receipt
  items: ReceiptLine[]
}
export interface Balance {
  id: number
  locationId: number
  materialId: number
  batchNo: string
  onHandQty: string
  availableQty: string
  qualityStatus: string
}
export interface Ledger {
  id: number
  beforeQty: string
  changeQty: string
  afterQty: string
}
export const arrivalStatuses: Record<string, string> = {
  WITHDRAWN: '已撤回',
  PENDING_RECEIPT: '待收货',
  PARTIALLY_RECEIVED: '部分收货',
  RECEIVED: '全部收货',
}
export const areaNames: Record<string, string> = {
  RECEIVING: '收货暂存区',
  INSPECTION: '待检区',
  STORAGE: '存储区',
}
