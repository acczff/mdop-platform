<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useListPosition } from '@/shared/composables/useListPosition'
import { ApiError, request, type Page } from '@/shared/api'
import { type Warehouse } from '@/modules/masterdata/api'
import {
  type BomSnapshot,
  type DemandDraft,
  type ManufacturingDirectoryEntry,
  type OrderDraft,
  type PendingProductionRequest,
  type ProductionDemand,
  type ProductionOrderItem,
  type SalesSourceLine,
} from '../productionOrderTypes'
import ProductionDemandList from '../components/ProductionDemandList.vue'
import ProductionOrderDetail from '../components/ProductionOrderDetail.vue'
import ProductionOrderModal from '../components/ProductionOrderModal.vue'

const props = defineProps<{ authorities: string[]; username: string }>()

const base = '/api/v1/manufacturing'
const storageKey = `mdop-production-order-pending:${props.username}`
const warehouse = ref(0),
  page = ref(0),
  warehouses = ref<Warehouse[]>([])
const materials = ref<ManufacturingDirectoryEntry[]>([]),
  sites = ref<ManufacturingDirectoryEntry[]>([]),
  boms = ref<BomSnapshot[]>([]),
  sources = ref<SalesSourceLine[]>([])
const result = ref<{ items: ProductionDemand[]; total: number }>(),
  detail = ref<ProductionDemand>(),
  selected = ref<ProductionOrderItem>()
const { pageRoot, rememberList, focusDetail, restoreList } = useListPosition()
const error = ref(''),
  notice = ref(''),
  mode = ref(''),
  reason = ref(''),
  formError = ref('')
const loading = ref(false),
  busy = ref(false),
  locked = ref(false),
  pending = ref<PendingProductionRequest>()
const demand = ref<DemandDraft>({
  sourceType: 'MANUAL',
  sourceReference: '',
  productId: 0,
  quantity: '',
  neededDate: '',
  purpose: '',
  salesLineId: 0,
})
const order = ref<OrderDraft>({ siteId: 0, bomId: 0, plannedDate: '' })

let generation = 0,
  detailGeneration = 0

const write = computed(() => props.authorities.includes('manufacturing:write'))
const review = computed(() =>
  props.authorities.includes('manufacturing:review'),
)
const blocked = computed(
  () => busy.value || locked.value || !!pending.value || !!mode.value,
)
const pendingPermission = computed(() =>
  pending.value?.url.match(/\/(approve|reject)$/) ? review.value : write.value,
)

function canReview(o: ProductionOrderItem) {
  return (
    review.value &&
    ![
      detail.value?.created_by,
      o.created_by,
      o.last_edited_by,
      o.submitted_by,
    ].includes(props.username)
  )
}

function returnToList() {
  if (blocked.value) return
  ++detailGeneration
  detail.value = undefined
  void restoreList()
}

function remember(p?: PendingProductionRequest) {
  if (p) sessionStorage.setItem(storageKey, JSON.stringify(p))
  else sessionStorage.removeItem(storageKey)
  pending.value = p
}

async function load(next = 0) {
  if (busy.value || locked.value) return
  const token = ++generation
  ++detailGeneration
  detail.value = undefined
  result.value = undefined
  error.value = ''
  loading.value = true
  page.value = next
  try {
    const all: Warehouse[] = []
    for (let p = 1; ; p++) {
      const batch = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${p}&size=100`,
      )
      all.push(
        ...batch.items.filter(
          (w) =>
            w.purpose === 'FINISHED_GOODS' &&
            props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (p >= batch.totalPages) break
    }
    if (token !== generation) return
    warehouses.value = all
    if (!all.some((w) => w.id === warehouse.value))
      warehouse.value = all[0]?.id || 0
    if (!warehouse.value) return
    const [list, ms, ss, src] = await Promise.all([
      request<{ items: ProductionDemand[]; total: number }>(
        `${base}/demands?warehouseId=${warehouse.value}&page=${next}&size=20`,
      ),
      request<ManufacturingDirectoryEntry[]>('/api/master-data/materials'),
      request<ManufacturingDirectoryEntry[]>(
        '/api/master-data/production-sites',
      ),
      request<SalesSourceLine[]>(
        `${base}/sales-sources?warehouseId=${warehouse.value}`,
      ),
    ])
    if (token === generation) {
      result.value = list
      materials.value = ms
      sites.value = ss
      sources.value = src
    }
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}

function closureSaved(orderId: number) {
  notice.value = '工单结束已保存'
  if (result.value && detail.value) {
    const demandId = detail.value.id
    result.value.items = result.value.items.map((item) =>
      item.id === demandId
        ? {
            ...item,
            order_no: detail.value?.orders.find((o) => o.id === orderId)
              ?.order_no,
            order_status: 'COMPLETED',
          }
        : item,
    )
  }
  if (detail.value) void openDetail(detail.value.id)
}

async function openDetail(id: number) {
  if (blocked.value || loading.value) return
  if (!detail.value) rememberList()
  const token = ++detailGeneration
  detail.value = undefined
  error.value = ''
  try {
    const d = await request<ProductionDemand>(`${base}/demands/${id}`)
    if (token === detailGeneration) {
      detail.value = d
      void focusDetail()
    }
  } catch (e) {
    if (token === detailGeneration)
      error.value = `详情读取失败：${(e as Error).message}`
  }
}

async function open(value: string, o?: ProductionOrderItem) {
  if (blocked.value || loading.value || !result.value) return
  mode.value = value
  selected.value = o
  reason.value = ''
  formError.value = ''
  demand.value = {
    sourceType: 'MANUAL',
    sourceReference: '',
    productId: 0,
    quantity: '',
    neededDate: '',
    purpose: '',
    salesLineId: 0,
  }
  order.value = {
    siteId: o?.site_id || 0,
    bomId: o?.bom_id || 0,
    plannedDate: o?.planned_date || detail.value?.needed_date || '',
  }
  boms.value = []
  if (value === 'order' || value === 'edit') {
    busy.value = true
    try {
      const list: BomSnapshot[] = []
      for (let p = 0; p < 10; p++) {
        const r = await request<{ items: BomSnapshot[]; total: number }>(
          `${base}/boms?status=PUBLISHED&q=${encodeURIComponent(detail.value!.product_code)}&page=${p}&size=100`,
        )
        list.push(
          ...r.items.filter((b) => b.product_id === detail.value!.product_id),
        )
        if ((p + 1) * 100 >= r.total) break
      }
      boms.value = list
    } catch (e) {
      formError.value = (e as Error).message
    } finally {
      busy.value = false
    }
  }
}

function close() {
  if (!busy.value && !pending.value) {
    mode.value = ''
    formError.value = ''
  }
}

async function send(payload: { demand: DemandDraft; order: OrderDraft }) {
  if (busy.value || pending.value || locked.value) return
  demand.value = payload.demand
  order.value = payload.order
  const d = detail.value,
    o = selected.value
  const body: Record<string, unknown> = {
    idempotencyKey: crypto.randomUUID(),
    reason: reason.value,
  }
  let url = `${base}/demands`,
    method = 'POST'
  if (mode.value === 'create') {
    if (!write.value) return
    Object.assign(body, {
      warehouseId: warehouse.value,
      sourceType: demand.value.sourceType,
      purpose: demand.value.purpose,
    })
    if (demand.value.sourceType === 'SALES')
      body.salesLineId = demand.value.salesLineId
    else
      Object.assign(body, {
        sourceReference: demand.value.sourceReference,
        productId: demand.value.productId,
        quantity: demand.value.quantity,
        neededDate: demand.value.neededDate,
      })
  } else {
    if (!d) return
    if (mode.value === 'order' || mode.value === 'edit') {
      if (!write.value || !boms.value.some((b) => b.id === order.value.bomId))
        return
      Object.assign(body, order.value)
      body.version = o?.version ?? d.version
      if (mode.value === 'edit' && o) {
        url = `${base}/orders/${o.id}`
        method = 'PUT'
      } else url += `/${d.id}/orders`
    } else if (mode.value === 'cancel-demand') {
      if (!write.value) return
      body.version = d.version
      url += `/${d.id}/cancel`
    } else {
      if (
        !o ||
        (['approve', 'reject'].includes(mode.value)
          ? !canReview(o)
          : !write.value)
      )
        return
      body.version = o.version
      url = `${base}/orders/${o.id}/actions/${mode.value}`
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
    formError.value = '无法保存重试凭据，本次请求未发送。'
    return
  }
  await retry()
}

async function retry() {
  if (locked.value || busy.value || !pending.value || !pendingPermission.value)
    return
  busy.value = true
  loading.value = false
  ++generation
  ++detailGeneration
  error.value = ''
  notice.value = ''
  const p = pending.value
  let saved: ProductionDemand
  try {
    saved = await request<ProductionDemand>(p.url, {
      method: p.method,
      body: p.body,
    })
    remember()
    mode.value = ''
  } catch (e) {
    detail.value = undefined
    result.value = undefined
    mode.value = ''
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
      }
      error.value = `操作被拒绝：${e.message}。请刷新核对。`
    } else
      error.value = `结果待核对：${(e as Error).message}。请原样重试，不要另建需求或工单。`
    busy.value = false
    return
  }
  busy.value = false
  warehouse.value = saved.warehouse_id
  notice.value = '操作已保存'
  await load(page.value)
  if (!error.value) await openDetail(saved.id)
}

onMounted(() => {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (raw) {
      const p = JSON.parse(raw) as PendingProductionRequest
      if (
        !new RegExp(
          `^${base}/(?:demands(?:/[1-9][0-9]*/(?:orders|cancel))?|orders/[1-9][0-9]*(?:/actions/(?:submit|approve|reject|cancel))?)$`,
        ).test(p.url) ||
        !['POST', 'PUT'].includes(p.method) ||
        typeof p.body !== 'string' ||
        !Number.isInteger(p.warehouse)
      )
        throw Error('invalid')
      pending.value = p
      warehouse.value = p.warehouse
    }
  } catch {
    locked.value = true
    error.value = '重试凭据无法读取，本页停止新操作，请联系管理员核对。'
    return
  }
  void load()
})

onBeforeUnmount(() => {
  generation++
  detailGeneration++
})
</script>

<template>
  <main ref="pageRoot" class="page production-orders">
    <div v-show="!detail" class="heading">
      <h1>生产需求与订单</h1>
      <span v-if="!write && !review" class="muted">{{
        authorities.includes('manufacturing:completion:report')
          ? '完工登记'
          : '只读'
      }}</span>
    </div>
    <p v-show="!detail" class="muted">
      按需求整量建单，批准后办理领料与完工登记，复核后衔接成品入库。
    </p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="pending" class="pending" role="status">
      有一笔操作结果待核对。<button
        :disabled="locked || busy || !pendingPermission"
        @click="retry"
      >
        原样重试</button
      ><span v-if="!pendingPermission"
        >当前账号无原操作权限，请联系管理员核对。</span
      >
    </div>
    <ProductionDemandList
      v-show="!detail"
      v-model:warehouse="warehouse"
      :warehouses="warehouses"
      :page="page"
      :result="result"
      :loading="loading"
      :blocked="blocked"
      :write="write"
      @load="load"
      @create="open('create')"
      @open-detail="openDetail"
    />
    <ProductionOrderDetail
      v-if="detail"
      :detail="detail"
      :blocked="blocked"
      :write="write"
      :review="review"
      :authorities="authorities"
      :username="username"
      @return-to-list="returnToList"
      @open-action="open"
      @closure-saved="closureSaved"
    />
    <ProductionOrderModal
      v-if="mode"
      v-model:reason="reason"
      :mode="mode"
      :detail="detail"
      :selected="selected"
      :initial-demand="demand"
      :initial-order="order"
      :materials="materials"
      :sites="sites"
      :boms="boms"
      :sources="sources"
      :form-error="formError"
      :busy="busy"
      :pending="pending"
      @close="close"
      @send="send"
    />
  </main>
</template>

<style scoped>
.production-orders {
  max-width: 1200px;
}
.heading {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: space-between;
}
.pending {
  padding: 12px;
  background: #fff4da;
}
</style>
