export interface BomMaterial {
  id: number
  code: string
  name: string
  unit: string
  status: string
}

export interface BomComponent {
  material_id: number
  material_code: string
  material_name: string
  quantity: string
  unit: string
}

export interface BomRecord {
  id: number
  product_id: number
  product_code: string
  product_name: string
  product_unit: string
  version_label: string
  base_quantity: string
  description: string
  status: string
  version: number
  copied_from_id: number | null
  published_by: string | null
  published_at: string | null
  disabled_by: string | null
  disabled_at: string | null
  components: BomComponent[]
  versions: { id: number; version_label: string; status: string }[]
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

export interface PendingBomRequest {
  url: string
  method: string
  body: string
}

export interface BomDraft {
  productId: number
  versionLabel: string
  baseQuantity: string
  description: string
  components: { materialId: number; quantity: string }[]
}

const BOM_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用',
  create: '新建 BOM',
  edit: '编辑草稿',
  copy: '复制新版本',
  publish: '发布版本',
  disable: '停用版本',
  CREATE: '创建',
  EDIT: '编辑',
  COPY: '复制',
  PUBLISH: '发布',
  DISABLE: '停用',
}

export function bomLabel(value: string) {
  return BOM_LABELS[value] || value
}

export function describeBomSnapshot(raw: string | null) {
  if (!raw) return '无前值'
  try {
    const d = JSON.parse(raw) as BomRecord
    return `${d.product_code} · ${d.product_name} · ${d.version_label} · ${bomLabel(d.status)}\n基准产量 ${d.base_quantity} ${d.product_unit}\n${d.components.map((c) => `${c.material_code} · ${c.material_name}：${c.quantity} ${c.unit}`).join('\n')}`
  } catch {
    return '快照无法显示，请核对原记录。'
  }
}
