<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request, type Page } from '@/shared/api'
import { type Warehouse } from '@/modules/masterdata/api'
import { scaled } from '@/shared/quantity'
import {
  stateText,
  type DirectoryEntry,
  type Kind,
  type PendingRequest,
  type PurchaseDocument,
  type PurchaseDraft,
} from '../api'
import PurchaseDocumentList from '../components/PurchaseDocumentList.vue'
import PurchaseDocumentDetail from '../components/PurchaseDocumentDetail.vue'
import PurchaseDocumentModal from '../components/PurchaseDocumentModal.vue'

const props = defineProps<{ authorities: string[]; username: string }>()

const base = '/api/v1/purchasing/documents'
const kind = ref<Kind>('REQUEST'),
  warehouse = ref(0),
  page = ref(0)
const warehouses = ref<Warehouse[]>([]),
  materials = ref<DirectoryEntry[]>([]),
  suppliers = ref<DirectoryEntry[]>([])
const result = ref<{ items: PurchaseDocument[]; total: number }>(),
  detail = ref<PurchaseDocument>()
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
const draft = ref<PurchaseDraft>({
  purpose: '',
  neededDate: '',
  supplierId: 0,
  lines: [{ materialId: 0, quantity: '' }],
})
const storageKey = `mdop-purchasing-pending:${props.username}`
const pending = ref<PendingRequest>()
const detailTab = ref('overview')
const detailLoading = ref(false)
const detailRequestId = ref<number>()
const pageRoot = ref<HTMLElement>()
let listScrollTop = 0
const arrangementId = ref(0)
const originalOrderId = ref<number | null>(null)
const arrangementLines = ref<{ orderLineId: number; quantity: string }[]>([])

let generation = 0,
  detailGeneration = 0

const writable = computed(() => props.authorities.includes('purchasing:write'))
const reviewable = computed(() =>
  props.authorities.includes('purchasing:review'),
)
const canReceive = computed(() =>
  props.authorities.includes('wms:arrival:read'),
)
const canReplenish = computed(
  () =>
    writable.value &&
    detail.value?.kind === 'ORDER' &&
    !!detail.value.fulfillment?.lines.some((l) => scaled(l.returned) > 0n),
)
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
  detailError.value = ''
  detailLoading.value = false
  void nextTick(() => {
    const workspace = pageRoot.value?.closest<HTMLElement>('.workspace')
    if (workspace) workspace.scrollTop = listScrollTop
    pageRoot.value
      ?.querySelector<HTMLButtonElement>(
        `[data-document="${detailRequestId.value}"]`,
      )
      ?.focus({ preventScroll: true })
  })
}

function remember(value?: PendingRequest) {
  if (value) sessionStorage.setItem(storageKey, JSON.stringify(value))
  else sessionStorage.removeItem(storageKey)
  pending.value = value
}

async function load(next = 0) {
  if (busy.value || locked.value) return
  const token = ++generation
  ++detailGeneration
  detailLoading.value = false
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
        ...data.items.filter((w) =>
          props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (p >= data.totalPages) break
    }
    const [m, s] = await Promise.all([
      request<DirectoryEntry[]>('/api/master-data/materials'),
      request<DirectoryEntry[]>('/api/master-data/suppliers'),
    ])
    if (token !== generation) return
    warehouses.value = all
    materials.value = m
    suppliers.value = s
    warehouse.value = all.some((w) => w.id === warehouse.value)
      ? warehouse.value
      : all[0]?.id || 0
    ready.value = true
    if (!warehouse.value) return
    const data = await request<{ items: PurchaseDocument[]; total: number }>(
      `${base}?warehouseId=${warehouse.value}&kind=${kind.value}&page=${next}&size=20`,
    )
    if (token === generation) result.value = data
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}

function switchKind(value: Kind) {
  if (blocked.value) return
  kind.value = value
  notice.value = ''
  void load()
}

async function openDetail(id: number) {
  if (blocked.value) return
  if (!detail.value && !detailLoading.value && !detailError.value)
    listScrollTop =
      pageRoot.value?.closest<HTMLElement>('.workspace')?.scrollTop || 0
  const token = ++detailGeneration
  if (detailRequestId.value !== id) detailTab.value = 'overview'
  detailRequestId.value = id
  detailLoading.value = true
  detail.value = undefined
  detailError.value = ''
  try {
    const data = await request<PurchaseDocument>(`${base}/${id}`)
    if (token === detailGeneration) {
      detail.value = data
      await nextTick()
      if (token === detailGeneration)
        pageRoot.value
          ?.querySelector<HTMLButtonElement>('.back-to-list')
          ?.focus()
    }
  } catch (e) {
    if (token === detailGeneration) detailError.value = (e as Error).message
  } finally {
    if (token === detailGeneration) detailLoading.value = false
  }
}

function open(value: string, target = 0) {
  if (blocked.value || !result.value) return
  mode.value = value
  arrangementId.value = target
  arrangementLines.value =
    detail.value?.lines.map((l) => ({ orderLineId: l.id, quantity: '' })) || []
  reason.value = ''
  formError.value = ''
  const d = detail.value
  originalOrderId.value =
    value === 'create' ? target || null : d?.original_order_id || null
  draft.value =
    value === 'create'
      ? {
          purpose: target && d ? `原订单 ${d.document_no} 退供后补货` : '',
          neededDate: '',
          supplierId: 0,
          lines: [{ materialId: 0, quantity: '' }],
        }
      : {
          purpose: d?.purpose || '',
          neededDate: d?.needed_date || '',
          supplierId: d?.supplier_id || 0,
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

async function send(payload: {
  draft: PurchaseDraft
  arrangementLines: { orderLineId: number; quantity: string }[]
}) {
  if (locked.value || busy.value || pending.value) return
  draft.value = payload.draft
  arrangementLines.value = payload.arrangementLines
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
      originalOrderId: originalOrderId.value,
    }
    if (mode.value === 'edit' && d) {
      url += `/${d.id}`
      method = 'PUT'
      body.version = d.version
      body.reason = reason.value
      if (d.kind === 'ORDER') body.supplierId = draft.value.supplierId
    }
  } else {
    if (!d) return
    body = {
      idempotencyKey: crypto.randomUUID(),
      version: d.version,
      reason: reason.value,
    }
    url += `/${d.id}/${mode.value === 'arrange' ? 'arrangements' : ['deliver', 'withdraw'].includes(mode.value) ? `arrangements/${arrangementId.value}/${mode.value}` : mode.value === 'convert' ? 'convert' : `actions/${mode.value}`}`
    if (mode.value === 'arrange') {
      body.expectedDate = draft.value.neededDate
      body.lines = arrangementLines.value.filter(
        (l) => l.quantity.trim() !== '',
      )
      if (!(body.lines as unknown[]).length) {
        formError.value = '至少填写一行安排数量。'
        return
      }
    }
    if (mode.value === 'convert') {
      body.supplierId = draft.value.supplierId
      body.neededDate = draft.value.neededDate
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
  detailLoading.value = false
  loading.value = false
  busy.value = true
  error.value = ''
  formError.value = ''
  notice.value = ''
  const original = pending.value
  let saved: PurchaseDocument
  try {
    saved = await request<PurchaseDocument>(original.url, {
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
  kind.value = saved.kind
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
      const parsed = JSON.parse(stored) as PendingRequest
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
        '保存的采购重试凭据无法读取，请联系管理员核对；本页已停止新操作。'
      locked.value = true
      return
    }
  }
  const linked = new URLSearchParams(window.location.search)
  const linkedId = Number(linked.get('documentId'))
  if (!pending.value && linkedId > 0 && Number.isSafeInteger(linkedId)) {
    warehouse.value = Number(linked.get('warehouseId')) || 0
    kind.value = 'ORDER'
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
  <main ref="pageRoot" class="page purchasing-page">
    <div
      v-show="!detail && !detailLoading && !detailError"
      class="page-heading"
    >
      <h1>采购需求与订单</h1>
      <span v-if="!writable && !reviewable" class="hint">只读</span>
    </div>
    <p v-show="!detail && !detailLoading && !detailError" class="page-intro">
      从已批准需求到分批到货，核对采购单据与履约事实。
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
    <PurchaseDocumentList
      v-show="!detail && !detailLoading && !detailError"
      v-model:warehouse="warehouse"
      :kind="kind"
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
      :error="error"
      @switch-kind="switchKind"
      @load="load"
      @create="open('create')"
      @open-detail="openDetail"
    />
    <div v-if="detailLoading || detailError" class="detail-read-state panel">
      <button :disabled="blocked" @click="returnToList">返回列表</button>
      <p v-if="detailLoading" role="status">正在读取单据详情…</p>
      <template v-else>
        <p class="error" role="alert">详情读取失败：{{ detailError }}</p>
        <button
          :disabled="blocked"
          @click="detailRequestId && openDetail(detailRequestId)"
        >
          重新读取详情
        </button>
      </template>
    </div>
    <PurchaseDocumentDetail
      v-if="detail"
      v-model:detail-tab="detailTab"
      :detail="detail"
      :blocked="blocked"
      :writable="writable"
      :can-edit="canEdit"
      :can-review="canReview"
      :can-replenish="canReplenish"
      :can-receive="canReceive"
      :authorities="authorities"
      @return-to-list="returnToList"
      @open-detail="openDetail"
      @open-action="open"
    />
    <PurchaseDocumentModal
      v-if="mode"
      v-model:reason="reason"
      :mode="mode"
      :detail="detail"
      :initial-draft="draft"
      :initial-arrangement-lines="arrangementLines"
      :original-order-id="originalOrderId"
      :suppliers="suppliers"
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
