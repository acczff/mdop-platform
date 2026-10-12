<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ApiError, request, type Page, type Warehouse } from '../api'
import { quantity } from '../presentation'
import { lineAmount } from '../finance'
import ResultPager from '../components/ResultPager.vue'
import RecordHeading from '../components/RecordHeading.vue'
import OperationRecord from '../components/OperationRecord.vue'
import OperationTimeNote from '../components/OperationTimeNote.vue'
import { useListPosition } from '../composables/useListPosition'
const props = defineProps<{ authorities: string[]; username: string }>()
interface Audit {
  id: number
  action: string
  reason: string
  actor: string
  created_at: string
}
interface Baseline {
  status: string
  version: number
  organization: string | null
  start_date: string | null
  end_date: string | null
  created_by: string | null
  history: Audit[]
}
interface Fact {
  orderId: number
  orderNumber: string
  orderLineId: number
  factId: number
  factNumber: string
  warehouseId: number
  partyId: number
  partyName: string
  materialCode: string
  materialName: string
  unit: string
  quantity: string
}
interface Basis {
  id: number
  number: string
  direction: string
  warehouse_id: number
  order_id: number
  order_number: string
  fact_number: string
  fact_id: number
  party_name: string
  material_code: string
  material_name: string
  unit: string
  quantity: string
  unit_price: string
  amount: string
  reference: string
  business_date: string
  reason: string
  status: string
  version: number
  created_by: string
  created_at: string
  reviewed_by: string | null
  reviewed_at: string | null
  open_amount: string | null
  selfReview: boolean
  sourceMatches: boolean
  balanceMatches: boolean
  history: Audit[]
}
interface Pending {
  url: string
  body: string
}
const base = '/api/v1/finance',
  storageKey = `mdop-finance-pending:${props.username}`
const labels: Record<string, string> = {
  UNINITIALIZED: '未初始化',
  PENDING: '待复核',
  ACTIVE: '已启用',
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
}
const actionNames: Record<string, string> = {
  CREATE: '登记依据',
  SUBMIT: '提交复核',
  APPROVE: '确认往来',
  REJECT: '驳回',
  CANCEL: '取消',
  SETUP: '提交初始化',
  ACTIVATE: '启用演示基线',
  REJECT_SETUP: '驳回初始化',
}
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
  pending = ref<Pending>(),
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
function remember(p?: Pending) {
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
    // A list may predate this write. Never present it as the current result.
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
      const p = JSON.parse(stored)
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
    // Let scope watchers invalidate old reads before starting the initial query.
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
    <template v-if="detail">
      <button :disabled="blocked" class="back-to-list" @click="back">
        ← 返回列表
      </button>
      <RecordHeading
        :title="detail.number"
        :subtitle="
          detail.party_name +
          ' · ' +
          (detail.direction === 'PAYABLE' ? '应付' : '应收')
        "
        :status="labels[detail.status] || detail.status"
      />
      <section class="card">
        <h2>金额与来源</h2>
        <dl class="facts-grid">
          <div>
            <dt>确认金额</dt>
            <dd>{{ detail.amount }} CNY</dd>
          </div>
          <div>
            <dt>未核销金额</dt>
            <dd>
              {{
                detail.open_amount === null
                  ? '尚未确认往来'
                  : detail.open_amount + ' CNY'
              }}
            </dd>
          </div>
          <div>
            <dt>业务订单</dt>
            <dd>
              <a
                :href="`${detail.direction === 'PAYABLE' ? '/purchasing' : '/sales-orders'}?warehouseId=${detail.warehouse_id}&documentId=${detail.order_id}`"
                >{{ detail.order_number }}</a
              >
            </dd>
          </div>
          <div>
            <dt>收发依据</dt>
            <dd>{{ detail.fact_number }}</dd>
          </div>
          <div>
            <dt>物料</dt>
            <dd>{{ detail.material_code }} · {{ detail.material_name }}</dd>
          </div>
          <div>
            <dt>本次计价数量</dt>
            <dd>{{ quantity(detail.quantity) }} {{ detail.unit }}</dd>
          </div>
          <div>
            <dt>单价</dt>
            <dd>{{ detail.unit_price }} CNY / {{ detail.unit }}</dd>
          </div>
          <div>
            <dt>金额依据号</dt>
            <dd>{{ detail.reference }}</dd>
          </div>
          <div>
            <dt>业务日期</dt>
            <dd>{{ detail.business_date }}</dd>
          </div>
          <div>
            <dt>录入说明</dt>
            <dd>{{ detail.reason }}</dd>
          </div>
        </dl>
        <p>
          来源核对：{{
            detail.sourceMatches ? '一致' : '异常，请核对原单'
          }}；往来核对：{{
            detail.balanceMatches ? '一致' : '异常，禁止据此结清'
          }}。
        </p>
        <p class="hint">
          本轮未登记收付款或核销，已确认不代表已收款／付款。不计算税额，也不代表凭证已过账。
        </p>
      </section>
      <section
        v-if="
          (write && ['DRAFT', 'PENDING'].includes(detail.status)) ||
          (review && detail.status === 'PENDING')
        "
        class="card"
      >
        <h2>处理依据</h2>
        <label
          >本次操作依据<textarea
            v-model="actionReason"
            aria-label="本次操作依据"
            maxlength="500"
            :disabled="blocked"
          />
        </label>
        <div class="detail-actions">
          <button
            v-if="write && detail.status === 'DRAFT'"
            :disabled="blocked"
            @click="action('submit')"
          >
            提交复核
          </button>
          <button
            v-if="review && detail.status === 'PENDING'"
            :disabled="
              blocked ||
              detail.selfReview ||
              detail.created_by === username ||
              !detail.sourceMatches
            "
            @click="action('approve')"
          >
            确认往来
          </button>
          <button
            v-if="review && detail.status === 'PENDING'"
            :disabled="
              blocked || detail.selfReview || detail.created_by === username
            "
            @click="action('reject')"
          >
            驳回
          </button>
          <button v-if="write" :disabled="blocked" @click="action('cancel')">
            取消依据
          </button>
        </div>
        <p
          v-if="(detail.selfReview || detail.created_by === username) && review"
          class="hint"
        >
          录入人或提交人不能复核自己的依据。
        </p>
      </section>
      <section class="card">
        <h2>操作记录</h2>
        <ol>
          <li v-for="h in detail.history" :key="h.id">
            <OperationRecord
              :title="actionNames[h.action] || h.action"
              :account="h.actor"
              :at="h.created_at"
              :note="h.reason"
              note-label="操作依据"
            />
          </li>
        </ol>
        <OperationTimeNote v-if="detail.history.length" />
      </section>
    </template>
    <template v-else>
      <details
        v-if="baseline"
        class="card"
        :open="baseline.status !== 'ACTIVE'"
      >
        <summary>演示基线 · {{ labels[baseline.status] }}</summary>
        <p>
          DEMO-CNY-V1：金额 2 位、单价 6
          位、按行四舍五入；一条收发事实整量计价一次，不回填历史金额。
        </p>
        <p v-if="baseline.organization">
          {{ baseline.organization }} · 开放期间 {{ baseline.start_date }} 至
          {{ baseline.end_date }}
        </p>
        <form
          v-if="
            write && ['UNINITIALIZED', 'REJECTED'].includes(baseline.status)
          "
          @submit.prevent="setup"
        >
          <fieldset :disabled="blocked">
            <div class="form-grid">
              <label
                >演示组织<input
                  v-model="organization"
                  required
                  maxlength="100" /></label
              ><label
                >启用日<input v-model="startDate" type="date" required /></label
              ><label
                >期间结束日<input v-model="endDate" type="date" required
              /></label>
            </div>
            <label
              >初始化依据<textarea
                v-model="setupReason"
                required
                maxlength="500"
              /></label
            ><button type="submit">提交初始化</button>
          </fieldset>
        </form>
        <div v-if="review && baseline.status === 'PENDING'">
          <label
            >初始化复核依据<textarea
              v-model="setupReason"
              :disabled="blocked"
              maxlength="500"
            /></label
          ><button
            :disabled="blocked || baseline.created_by === username"
            @click="reviewSetup('approve')"
          >
            复核启用</button
          ><button
            :disabled="blocked || baseline.created_by === username"
            @click="reviewSetup('reject')"
          >
            驳回初始化
          </button>
        </div>
        <ol>
          <li v-for="h in baseline.history" :key="h.id">
            <OperationRecord
              :title="actionNames[h.action] || h.action"
              :account="h.actor"
              :at="h.created_at"
              :note="h.reason"
              note-label="操作依据"
            />
          </li>
        </ol>
        <OperationTimeNote v-if="baseline.history.length" />
      </details>
      <section class="card">
        <div class="toolbar">
          <label
            >仓库<select
              v-model="warehouse"
              :disabled="blocked"
              @change="refresh(0)"
            >
              <option :value="0">选择仓库</option>
              <option v-for="w in warehouses" :key="w.id" :value="w.id">
                {{ w.code }} · {{ w.name }}
              </option>
            </select></label
          ><label
            >往来方向<select
              v-model="direction"
              :disabled="blocked"
              @change="refresh(0)"
            >
              <option value="PAYABLE">采购应付</option>
              <option value="RECEIVABLE">销售应收</option>
            </select></label
          ><button :disabled="busy || locked" @click="refresh()">刷新</button>
        </div>
        <div v-if="result" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>金额依据 / 原订单</th>
                <th>往来方 / 物料</th>
                <th>金额（CNY）</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in result.items" :key="b.id">
                <td>
                  <button
                    class="link-button"
                    :data-basis-id="b.id"
                    :disabled="blocked"
                    @click="open(b.id)"
                  >
                    {{ b.number }}</button
                  ><small>{{ b.order_number }}</small>
                </td>
                <td>
                  {{ b.party_name
                  }}<small>{{ b.material_code }} · {{ b.material_name }}</small>
                </td>
                <td>
                  {{ b.amount
                  }}<small>{{
                    b.open_amount === null
                      ? '未确认往来'
                      : '未核销 ' + b.open_amount
                  }}</small>
                </td>
                <td>{{ labels[b.status] }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="!result.items.length">当前筛选条件下没有金额依据。</p>
          <ResultPager
            :total="result.total"
            :page="page"
            :disabled="blocked"
            @change="refresh"
          />
        </div>
      </section>
      <section v-if="baseline?.status === 'ACTIVE'" class="card">
        <h2>{{ write ? '登记金额依据' : '查询业务来源' }}</h2>
        <form @submit.prevent="findSources">
          <fieldset :disabled="blocked">
            <label
              >业务订单号<input
                v-model="orderNumber"
                aria-label="业务订单号"
                :placeholder="
                  direction === 'PAYABLE' ? 'PO-00000001' : 'SO-00000001'
                "
                maxlength="40"
                required /></label
            ><button type="submit">查询收发事实</button>
          </fieldset>
        </form>
        <p v-if="facts && !facts.length">
          未找到可计价事实。请核对仓库和订单号；采购须合格上架，销售须实际出库。
        </p>
        <form v-if="facts?.length && write" @submit.prevent="create">
          <fieldset :disabled="blocked">
            <label
              >收发事实<select
                v-model="selected"
                aria-label="收发事实"
                required
              >
                <option :value="undefined">请选择</option>
                <option v-for="f in facts" :key="f.factId" :value="f.factId">
                  {{ f.factNumber }} · {{ f.materialCode }} ·
                  {{ quantity(f.quantity) }} {{ f.unit }} · {{ f.partyName }}
                </option>
              </select></label
            >
            <div class="form-grid">
              <label
                >金额依据号<input
                  v-model="reference"
                  aria-label="金额依据号"
                  required
                  maxlength="100" /></label
              ><label
                >业务日期<input
                  v-model="businessDate"
                  aria-label="业务日期"
                  type="date"
                  :min="baseline.start_date || undefined"
                  :max="baseline.end_date || undefined"
                  required /></label
              ><label
                >单价（CNY）<input
                  v-model="unitPrice"
                  aria-label="单价"
                  inputmode="decimal"
                  required /></label
              ><label
                >确认金额（CNY）<input
                  v-model="amount"
                  aria-label="确认金额"
                  inputmode="decimal"
                  required
              /></label>
            </div>
            <p v-if="expected">
              按行计算金额：{{ expected }} CNY，请核对后填写确认金额。
            </p>
            <label
              >录入依据<textarea
                v-model="reason"
                aria-label="录入依据"
                required
                maxlength="500"
              /></label
            ><button type="submit" :disabled="!source">保存草稿</button>
          </fieldset>
        </form>
        <ul v-else-if="facts?.length">
          <li v-for="f in facts" :key="f.factId">
            {{ f.factNumber }} · {{ f.materialCode }} ·
            {{ quantity(f.quantity) }} {{ f.unit }} · {{ f.partyName }}
          </li>
        </ul>
      </section>
    </template>
  </main>
</template>
<style scoped>
.card {
  padding: 20px;
  margin: 16px 0;
  border: 1px solid #e4e8ef;
  border-radius: 8px;
  background: white;
}
.toolbar,
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
}
.form-grid,
.facts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}
label {
  display: grid;
  gap: 6px;
  margin: 12px 0;
}
fieldset {
  border: 0;
  padding: 0;
  min-width: 0;
}
input,
select,
textarea {
  max-width: 100%;
  min-width: 0;
}
textarea {
  min-height: 64px;
}
.table-wrap {
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
}
td,
th {
  padding: 12px;
  text-align: left;
}
small {
  display: block;
  color: #697386;
  margin-top: 4px;
}
dt,
.hint {
  color: #697386;
}
dd {
  margin: 4px 0;
  overflow-wrap: anywhere;
}
.error {
  color: #b42318;
}
.notice {
  padding: 12px;
  background: #fff3da;
}
summary {
  cursor: pointer;
}
.finance-page {
  min-width: 0;
}
.page-header p {
  color: #697386;
}
ol {
  padding-left: 22px;
}
li {
  margin: 10px 0;
  overflow-wrap: anywhere;
}
</style>
