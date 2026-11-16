export interface Supplier {
  id: number
  code: string
  name: string
}
export interface Material extends Supplier {
  unit: string
  trackingMode: string
  requireDateCode: boolean
  requireExpiry: boolean
}
export interface Location extends Supplier {
  warehouseId: number
  areaType: string
}
export interface Arrival {
  id: number
  externalNoticeNo: string
  purchaseOrderNo: string
  supplierId: number
  supplierCode?: string
  supplierName?: string
  warehouseId: number
  status: string
  version: number
}
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
