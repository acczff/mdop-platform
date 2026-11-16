<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError, request, type Page } from '@/shared/api'
import { type Warehouse } from '@/modules/masterdata/api'
import { useListPosition } from '@/shared/composables/useListPosition'
import {
  lineAmount,
  type Baseline,
  type Basis,
  type Fact,
  type PendingFinanceRequest,
} from '../api'
import FinanceDetail from '../components/FinanceDetail.vue'
import FinanceBaselineCard from '../components/FinanceBaselineCard.vue'
import FinanceBasisList from '../components/FinanceBasisList.vue'

const props = defineProps<{ authorities: string[]; username: string }>()

const base = '/api/v1/finance',
  storageKey = `mdop-finance-pending:${props.username}`
const write = computed(() => props.authorities.includes('finance:write'))
const review = computed(() => props.authorities.includes('finance:review'))
const params = new URLSearchParams(window.location.search)
const warehouse = ref(Number(params.get('warehouseId')) || 0),
  direction = ref(
    params.get('direction') === 'RECEIVABLE' ? 'RECEIVABLE' : 'PAYABLE',
  ),
  orderNumber = ref(params.get('orderNumber') || '')
const warehouses = ref<Warehouse[]>([]),
  baseline = ref<Baseline>(),
  result = ref<{ items: Basis[]; total: number }>(),
  detail = ref<Basis>(),
  facts = ref<Fact[]>(),
  selected = ref<number>()
const loading = ref(false),
  busy = ref(false),
  locked = ref(false),
  error = ref(''),
  notice = ref(''),
  pending = ref<PendingFinanceRequest>(),
  page = ref(0)
const organization = ref('演示组织'),
  startDate = ref(''),
  endDate = ref(''),
  setupReason = ref(''),
  unitPrice = ref(''),
  amount = ref(''),
  reference = ref(''),
  businessDate = ref(''),
  reason = ref(''),
  actionReason = ref('')

const { pageRoot, rememberList, focusDetail, restoreList } = useListPosition()
const source = computed(() =>
  facts.value?.find((f) => f.factId === selected.value),
)
const expected = computed(() =>
  source.value ? lineAmount(source.value.quantity, unitPrice.value) : '',
)
const blocked = computed(
  () => loading.value || busy.value || locked.value || !!pending.value,
)
let alive = true,
  generation = 0

watch(
  [warehouse, direction, orderNumber],
  () => {
    ++generation
    facts.value = undefined
    selected.value = undefined
    detail.value = undefined
    result.value = undefined
  },
  { flush: 'sync' },
)

async function refresh(next = page.value) {
  if (busy.value || locked.value) return
  const token = ++generation
  loading.value = true
  error.value = ''
  result.value = undefined
  detail.value = undefined
  facts.value = undefined
  selected.value = undefined
  baseline.value = undefined
  page.value = next
  try {
    const b = await request<Baseline>(`${base}/baseline`)
    if (!alive || token !== generation) return
    baseline.value = b
    if (warehouse.value) {
      const rows = await request<{ items: Basis[]; total: number }>(
        `${base}/bases?warehouseId=${warehouse.value}&direction=${direction.value}&page=${page.value}&orderNumber=${encodeURIComponent(orderNumber.value.trim())}`,
      )
      if (alive && token === generation) result.value = rows
    }
  } catch (e) {
    if (alive && token === generation)
      error.value = `读取失败：${(e as Error).message}`
  } finally {
    if (alive && token === generation) loading.value = false
  }
}

async function findSources() {
  if (blocked.value || !warehouse.value || !orderNumber.value.trim()) return
  const token = ++generation
  facts.value = undefined
  selected.value = undefined
  loading.value = true
  error.value = ''
  try {
    const rows = await request<Fact[]>(
      `${base}/sources?warehouseId=${warehouse.value}&direction=${direction.value}&orderNumber=${encodeURIComponent(orderNumber.value.trim())}`,
    )
    const listed = await request<{ items: Basis[]; total: number }>(
      `${base}/bases?warehouseId=${warehouse.value}&direction=${direction.value}&page=0&orderNumber=${encodeURIComponent(orderNumber.value.trim())}`,
    )
    if (alive && token === generation) {
      facts.value = rows
      result.value = listed
      page.value = 0
    }
  } catch (e) {
    if (alive && token === generation)
      error.value = `来源查询失败：${(e as Error).message}`
  } finally {
    if (alive && token === generation) loading.value = false
  }
}

async function open(id: number) {
  if (blocked.value) return
  rememberList()
  const token = ++generation
  detail.value = undefined
  loading.value = true
  error.value = ''
  actionReason.value = ''
  try {
    const d = await request<Basis>(`${base}/bases/${id}`)
    if (alive && token === generation) detail.value = d
  } catch (e) {
    if (alive && token === generation)
      error.value = `详情读取失败：${(e as Error).message}`
  } finally {
    if (alive && token === generation) {
      loading.value = false
      if (detail.value) void focusDetail()
    }
  }
}

async function back() {
  if (blocked.value) return
  const id = detail.value?.id
  detail.value = undefined
  await refresh()
  await restoreList()
  await nextTick()
  if (id)
    pageRoot.value
      ?.querySelector<HTMLButtonElement>(`[data-basis-id="${id}"]`)
      ?.focus({ preventScroll: true })
}

function remember(p?: PendingFinanceRequest) {
  if (p) sessionStorage.setItem(storageKey, JSON.stringify(p))
  else sessionStorage.removeItem(storageKey)
  pending.value = p
}

async function send(url: string, body: Record<string, unknown>) {
  if (blocked.value) return
  try {
    remember({
      url,
      body: JSON.stringify({ ...body, idempotencyKey: crypto.randomUUID() }),
    })
  } catch {
    locked.value = true
    error.value = '无法保存重试凭据，停止新操作'
    return
  }
  await retry()
}

async function retry() {
  if (!pending.value || busy.value || locked.value) return
  ++generation
  loading.value = false
  busy.value = true
  error.value = ''
  notice.value = ''
  const p = pending.value
  try {
    const data = await request<Baseline | Basis>(p.url, {
      method: 'POST',
      body: p.body,
    })
    if (!alive) return
    remember()
    notice.value = '操作已保存'
    if ('number' in data) {
      detail.value = data
      actionReason.value = ''
      void focusDetail()
    } else baseline.value = data
    result.value = undefined
    facts.value = undefined
    selected.value = undefined
  } catch (e) {
    if (!alive) return
    if (
      e instanceof ApiError &&
      e.status >= 400 &&
      e.status < 500 &&
      ![401, 403, 408, 429].includes(e.status)
    ) {
      try {
        remember()
      } catch {
        locked.value = true
      }
      detail.value = undefined
      result.value = undefined
      facts.value = undefined
      selected.value = undefined
      error.value = `操作被拒绝：${e.message}。请刷新核对。`
    } else
      error.value = `结果待核对：${(e as Error).message}。请保留原请求重试。`
  } finally {
    if (alive) busy.value = false
  }
}

function create() {
  if (!source.value) return
  void send(`${base}/bases`, {
    direction: direction.value,
    warehouseId: warehouse.value,
    orderNumber: source.value.orderNumber,
    factId: source.value.factId,
    unitPrice: unitPrice.value,
    amount: amount.value,
    reference: reference.value,
    businessDate: businessDate.value,
    reason: reason.value,
  })
}

function action(name: string) {
  if (!detail.value || !actionReason.value.trim()) {
    error.value = '请填写本次操作依据'
    return
  }
  void send(`${base}/bases/${detail.value.id}/${name}`, {
    version: detail.value.version,
    reason: actionReason.value,
  })
}

function setup() {
  if (!baseline.value) return
  void send(`${base}/baseline`, {
    version: baseline.value.version,
    organization: organization.value,
    startDate: startDate.value,
    endDate: endDate.value,
    reason: setupReason.value,
  })
}

function reviewSetup(name: string) {
  if (!baseline.value || !setupReason.value.trim()) {
    error.value = '请填写初始化复核依据'
    return
  }
  void send(`${base}/baseline/${name}`, {
    version: baseline.value.version,
    reason: setupReason.value,
  })
}

onMounted(async () => {
  try {
    const stored = sessionStorage.getItem(storageKey)
    if (stored) {
      const p = JSON.parse(stored) as PendingFinanceRequest
      if (
        typeof p.body !== 'string' ||
        typeof p.url !== 'string' ||
        !/^\/api\/v1\/finance\/(baseline(?:\/(?:approve|reject))?|bases(?:\/\d+\/(?:submit|approve|reject|cancel))?)$/.test(
          p.url,
        ) ||
        typeof JSON.parse(p.body).idempotencyKey !== 'string'
      )
        throw Error('请求凭据损坏')
      pending.value = p
    }
    const all: Warehouse[] = []
    for (let i = 1; ; i++) {
      const data = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${i}&size=100`,
      )
      if (!alive) return
      all.push(
        ...data.items.filter((w) =>
          props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (i >= data.totalPages) break
    }
    warehouses.value = all
    if (!all.some((w) => w.id === warehouse.value))
      warehouse.value = all[0]?.id || 0
    await Promise.resolve()
    if (alive) await refresh(0)
  } catch (e) {
    if (alive) {
      locked.value = true
      error.value = `初始化失败，请重新打开页面：${(e as Error).message}`
    }
  }
})

onBeforeUnmount(() => {
  alive = false
  ++generation
})
</script>

<template>
  <main ref="pageRoot" class="page finance-page">
    <header class="page-header">
      <div>
        <h1>金额依据与往来</h1>
        <p>演示口径 · 人民币 · 数量履约与金额确认分别核对</p>
      </div>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="pending" class="notice">
      <p>有一笔写入结果待核对。新操作已锁定，请原样重试。</p>
      <button :disabled="busy || locked" @click="retry">原样重试</button>
    </div>
    <p v-if="loading" role="status">正在读取…</p>
    <FinanceDetail
      v-if="detail"
      v-model:action-reason="actionReason"
      :detail="detail"
      :blocked="blocked"
      :write="write"
      :review="review"
      :username="username"
      @back="back"
      @action="action"
    />
    <template v-else>
      <FinanceBaselineCard
        v-if="baseline"
        v-model:organization="organization"
        v-model:start-date="startDate"
        v-model:end-date="endDate"
        v-model:setup-reason="setupReason"
        :baseline="baseline"
        :blocked="blocked"
        :write="write"
        :review="review"
        :username="username"
        @setup="setup"
        @review-setup="reviewSetup"
      />
      <FinanceBasisList
        v-model:warehouse="warehouse"
        v-model:direction="direction"
        v-model:order-number="orderNumber"
        v-model:selected="selected"
        v-model:reference="reference"
        v-model:business-date="businessDate"
        v-model:unit-price="unitPrice"
        v-model:amount="amount"
        v-model:reason="reason"
        :warehouses="warehouses"
        :baseline="baseline"
        :result="result"
        :facts="facts"
        :page="page"
        :blocked="blocked"
        :busy="busy"
        :locked="locked"
        :write="write"
        :expected="expected"
        :has-source="!!source"
        @refresh="refresh"
        @open="open"
        @find-sources="findSources"
        @create="create"
      />
    </template>
  </main>
</template>

<style scoped>
.error {
  color: #b42318;
}
.notice {
  padding: 12px;
  background: #fff3da;
}
.finance-page {
  min-width: 0;
}
.page-header p {
  color: #697386;
}
</style>
