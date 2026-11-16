<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useListPosition } from '@/shared/composables/useListPosition'
import { ApiError, request, type Page } from '@/shared/api'
import { type Warehouse } from '@/modules/masterdata/api'
import {
  stateText,
  type PendingSalesRequest,
  type SalesDirectoryEntry,
  type SalesDocument,
  type SalesOrderDraft,
} from '../api'
import SalesOrderList from '../components/SalesOrderList.vue'
import SalesOrderDetail from '../components/SalesOrderDetail.vue'
import SalesOrderModal from '../components/SalesOrderModal.vue'

const props = defineProps<{ authorities: string[]; username: string }>()

const base = '/api/v1/sales/documents'
const warehouse = ref(0),
  page = ref(0)
const warehouses = ref<Warehouse[]>([]),
  materials = ref<SalesDirectoryEntry[]>([]),
  customers = ref<SalesDirectoryEntry[]>([])
const result = ref<{ items: SalesDocument[]; total: number }>(),
  detail = ref<SalesDocument>()
const { pageRoot, rememberList, focusDetail, restoreList } = useListPosition()
const detailTab = ref('overview')
const ready = ref(false),
  loading = ref(false),
  busy = ref(false),
  locked = ref(false),
  error = ref(''),
  notice = ref(''),
  detailError = ref('')
const mode = ref(''),
  reason = ref(''),
  formError = ref('')
const draft = ref<SalesOrderDraft>({
  purpose: '',
  neededDate: '',
  customerId: 0,
  customerReference: '',
  lines: [{ materialId: 0, quantity: '' }],
})
const storageKey = `mdop-sales-pending:${props.username}`
const pending = ref<PendingSalesRequest>()
const arrangementId = ref(0)
const arrangementLine = ref(0),
  arrangementQty = ref('')

let generation = 0,
  detailGeneration = 0

const writable = computed(() => props.authorities.includes('sales:write'))
const reviewable = computed(() => props.authorities.includes('sales:review'))
const canShip = computed(() => props.authorities.includes('wms:sales:read'))
const pendingPermission = computed(
  () =>
    !!pending.value &&
    props.authorities.includes(`wms:warehouse:${pending.value.warehouse}`) &&
    (/\/actions\/(approve|reject)$/.test(pending.value.url)
      ? reviewable.value
      : writable.value),
)
const blocked = computed(
  () => locked.value || busy.value || !!pending.value || !!mode.value,
)
const canEdit = computed(
  () =>
    !!detail.value &&
    ['DRAFT', 'REJECTED'].includes(detail.value.status) &&
    writable.value,
)
const canReview = computed(
  () =>
    detail.value?.status === 'SUBMITTED' &&
    reviewable.value &&
    ![
      detail.value.created_by,
      detail.value.last_edited_by,
      detail.value.submitted_by,
    ].includes(props.username),
)

function returnToList() {
  if (blocked.value) return
  ++detailGeneration
  detail.value = undefined
  void restoreList()
}

function remember(value?: PendingSalesRequest) {
  if (value) sessionStorage.setItem(storageKey, JSON.stringify(value))
  else sessionStorage.removeItem(storageKey)
  pending.value = value
}

async function load(next = 0) {
  if (busy.value || locked.value) return
  const token = ++generation
  ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  result.value = undefined
  error.value = ''
  loading.value = true
  ready.value = false
  page.value = next
  try {
    const all: Warehouse[] = []
    for (let p = 1; ; p++) {
      const data = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${p}&size=100`,
      )
      all.push(
        ...data.items.filter(
          (w) =>
            w.purpose === 'FINISHED_GOODS' &&
            props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (p >= data.totalPages) break
    }
    const [m, s] = await Promise.all([
      request<SalesDirectoryEntry[]>('/api/master-data/materials'),
      request<SalesDirectoryEntry[]>('/api/master-data/customers'),
    ])
    if (token !== generation) return
    warehouses.value = all
    materials.value = m
    customers.value = s
    warehouse.value = all.some((w) => w.id === warehouse.value)
      ? warehouse.value
      : all[0]?.id || 0
    ready.value = true
    if (!warehouse.value) return
    const data = await request<{ items: SalesDocument[]; total: number }>(
      `${base}?warehouseId=${warehouse.value}&page=${next}&size=20`,
    )
    if (token === generation) result.value = data
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}

async function openDetail(id: number) {
  if (blocked.value) return
  if (!detail.value) rememberList()
  if (detail.value?.id !== id) detailTab.value = 'overview'
  const token = ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  try {
    const data = await request<SalesDocument>(`${base}/${id}`)
    if (token === detailGeneration) {
      detail.value = data
      void focusDetail()
      if (result.value)
        result.value.items = result.value.items.map((d) =>
          d.id === data.id ? data : d,
        )
    }
  } catch (e) {
    if (token === detailGeneration) detailError.value = (e as Error).message
  }
}

function open(value: string, target = 0) {
  if (blocked.value || !result.value) return
  mode.value = value
  arrangementId.value = target
  arrangementLine.value = detail.value?.lines[0]?.id || 0
  arrangementQty.value = ''
  reason.value = ''
  formError.value = ''
  const d = detail.value
  draft.value =
    value === 'create'
      ? {
          purpose: '',
          neededDate: '',
          customerId: 0,
          customerReference: '',
          lines: [{ materialId: 0, quantity: '' }],
        }
      : {
          purpose: d?.purpose || '',
          neededDate: d?.needed_date || '',
          customerId: d?.customer_id || 0,
          customerReference: d?.customer_reference || '',
          lines:
            d?.lines.map((l) => ({
              materialId: l.material_id,
              quantity: l.quantity,
            })) || [],
        }
}

function close() {
  if (busy.value || pending.value) return
  mode.value = ''
  formError.value = ''
}

async function send(submittedDraft: SalesOrderDraft) {
  if (locked.value || busy.value || pending.value) return
  draft.value = submittedDraft
  const d = detail.value
  let url = base,
    method = 'POST',
    body: Record<string, unknown>
  if (mode.value === 'create' || mode.value === 'edit') {
    body = {
      idempotencyKey: crypto.randomUUID(),
      warehouseId: warehouse.value,
      purpose: draft.value.purpose,
      neededDate: draft.value.neededDate,
      lines: draft.value.lines,
      customerId: draft.value.customerId,
      customerReference: draft.value.customerReference,
    }
    if (mode.value === 'edit' && d) {
      url += `/${d.id}`
      method = 'PUT'
      body.version = d.version
      body.reason = reason.value
    }
  } else {
    if (!d) return
    body = {
      idempotencyKey: crypto.randomUUID(),
      version: d.version,
      reason: reason.value,
    }
    url += `/${d.id}/${mode.value === 'arrange' ? 'arrangements' : ['deliver', 'withdraw'].includes(mode.value) ? `arrangements/${arrangementId.value}/${mode.value}` : `actions/${mode.value}`}`
    if (mode.value === 'arrange') {
      body.expectedDate = draft.value.neededDate
      body.orderLineId = arrangementLine.value
      body.quantity = arrangementQty.value
    }
  }
  try {
    remember({
      url,
      method,
      body: JSON.stringify(body),
      warehouse: warehouse.value,
    })
  } catch {
    formError.value = '无法保存重试凭据，本次请求未发送。请检查浏览器存储设置。'
    return
  }
  await retry()
}

async function retry() {
  if (locked.value || busy.value || !pending.value || !pendingPermission.value)
    return
  ++generation
  ++detailGeneration
  loading.value = false
  busy.value = true
  error.value = ''
  formError.value = ''
  notice.value = ''
  const original = pending.value
  let saved: SalesDocument
  try {
    saved = await request<SalesDocument>(original.url, {
      method: original.method,
      body: original.body,
    })
    remember()
    mode.value = ''
  } catch (e) {
    const failure = e as Error
    if (
      e instanceof ApiError &&
      e.status >= 400 &&
      e.status < 500 &&
      ![401, 403].includes(e.status)
    ) {
      try {
        remember()
      } catch {
        locked.value = true
        result.value = undefined
        detail.value = undefined
        error.value = '无法清理重试凭据，请联系管理员核对；本页已停止新操作。'
        busy.value = false
        return
      }
      mode.value = ''
      result.value = undefined
      detail.value = undefined
      error.value = `操作被拒绝：${failure.message}。请刷新核对后再操作。`
    } else {
      error.value = `结果待核对：${failure.message}。请原样重试，不要重复建单。`
      detail.value = undefined
      result.value = undefined
    }
    busy.value = false
    return
  }
  busy.value = false
  warehouse.value = saved.warehouse_id
  notice.value = `操作已保存：${saved.document_no} · ${stateText(saved.status)}`
  await load()
  if (error.value) {
    notice.value += '；列表刷新失败，请刷新核对。'
    return
  }
  await openDetail(saved.id)
}

onMounted(() => {
  let stored: string | null
  try {
    stored = sessionStorage.getItem(storageKey)
  } catch {
    error.value = '无法读取重试凭据，请检查浏览器存储设置'
    locked.value = true
    return
  }
  if (stored) {
    try {
      const parsed = JSON.parse(stored) as PendingSalesRequest
      if (
        !parsed.url.startsWith(base) ||
        !['POST', 'PUT'].includes(parsed.method) ||
        typeof parsed.body !== 'string' ||
        !Number.isInteger(parsed.warehouse)
      )
        throw new Error('invalid')
      pending.value = parsed
      warehouse.value = parsed.warehouse
    } catch {
      error.value =
        '保存的销售重试凭据无法读取，请联系管理员核对；本页已停止新操作。'
      locked.value = true
      return
    }
  }
  const linked = new URLSearchParams(window.location.search)
  const linkedId = Number(linked.get('documentId'))
  if (!pending.value && linkedId > 0 && Number.isSafeInteger(linkedId)) {
    warehouse.value = Number(linked.get('warehouseId')) || 0
    void load().then(() => {
      if (ready.value && !pending.value) void openDetail(linkedId)
    })
  } else void load()
})

onBeforeUnmount(() => {
  generation++
  detailGeneration++
})
</script>

<template>
  <main ref="pageRoot" class="page sales-page">
    <div v-show="!detail" class="page-heading">
      <h1>销售订单</h1>
      <span v-if="!writable && !reviewable" class="hint">只读</span>
    </div>
    <p v-show="!detail" class="hint">
      客户订单 → 分批发货 → 实际出库 → 数量结案
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <div v-if="pending" class="pending panel">
      <strong>有一笔操作等待确认</strong>
      <p>原请求已保留。原样重试会核对同一笔操作，不会重新建单。</p>
      <p v-if="!pendingPermission">当前账号无原操作权限，请联系管理员核对。</p>
      <button :disabled="locked || busy || !pendingPermission" @click="retry">
        {{ busy ? '正在核对…' : '原样重试' }}
      </button>
    </div>
    <SalesOrderList
      v-show="!detail"
      v-model:warehouse="warehouse"
      :warehouses="warehouses"
      :page="page"
      :result="result"
      :ready="ready"
      :loading="loading"
      :blocked="blocked"
      :locked="locked"
      :busy="busy"
      :has-modal="!!mode"
      :writable="writable"
      @load="load"
      @create="open('create')"
      @open-detail="openDetail"
    />
    <p v-if="detailError" class="error" role="alert">
      详情读取失败：{{ detailError }}，请重新选择单据。
    </p>
    <SalesOrderDetail
      v-if="detail"
      v-model:detail-tab="detailTab"
      :detail="detail"
      :blocked="blocked"
      :writable="writable"
      :can-edit="canEdit"
      :can-review="canReview"
      :can-ship="canShip"
      :authorities="authorities"
      @return-to-list="returnToList"
      @open-detail="openDetail"
      @open-action="open"
    />
    <SalesOrderModal
      v-if="mode"
      v-model:arrangement-line="arrangementLine"
      v-model:arrangement-qty="arrangementQty"
      v-model:reason="reason"
      :mode="mode"
      :detail="detail"
      :initial-draft="draft"
      :customers="customers"
      :materials="materials"
      :form-error="formError"
      :error="error"
      :busy="busy"
      :locked="locked"
      :pending="pending"
      :pending-permission="pendingPermission"
      @close="close"
      @send="send"
      @retry="retry"
    />
  </main>
</template>

<style scoped>
.sales-page h1 {
  font-size: 24px;
}
.pending {
  padding: 16px;
  margin-bottom: 16px;
}
.pending p {
  margin: 6px 0 12px;
}
</style>
