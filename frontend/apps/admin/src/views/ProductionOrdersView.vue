<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request, type Page, type Warehouse } from '../api'
import MaterialPlanPanel from '../components/MaterialPlanPanel.vue'

const props = defineProps<{ authorities: string[]; username: string }>()
interface Directory {
  id: number
  code: string
  name: string
  status: string
  unit?: string
}
interface Bom {
  id: number
  product_id: number
  product_code: string
  version_label: string
  base_quantity: string
  product_unit: string
  components: {
    material_code: string
    material_name: string
    quantity: string
    unit: string
  }[]
}
interface Order {
  id: number
  order_no: string
  status: string
  version: number
  site_id: number
  site_code: string
  site_name: string
  warehouse_name: string
  bom_id: number
  bom_snapshot: string
  planned_date: string
  created_by: string
  last_edited_by: string
  submitted_by: string
  approved_by: string
  approved_at: string
}
interface Demand {
  id: number
  warehouse_id: number
  source_type: string
  source_reference: string
  product_id: number
  product_code: string
  product_name: string
  quantity: string
  unit: string
  needed_date: string
  purpose: string
  status: string
  version: number
  created_by: string
  orders: Order[]
  order_no?: string
  order_status?: string
  history: {
    id: number
    action: string
    reason: string
    created_by: string
    created_at: string
  }[]
}
interface Source {
  id: number
  document_no: string
  material_code: string
  material_name: string
  quantity: string
  unit: string
}
interface Pending {
  url: string
  method: string
  body: string
  warehouse: number
}
const base = '/api/v1/manufacturing'
const storageKey = `mdop-production-order-pending:${props.username}`
const warehouse = ref(0),
  page = ref(0),
  warehouses = ref<Warehouse[]>([])
const materials = ref<Directory[]>([]),
  sites = ref<Directory[]>([]),
  boms = ref<Bom[]>([]),
  sources = ref<Source[]>([])
const result = ref<{ items: Demand[]; total: number }>(),
  detail = ref<Demand>(),
  selected = ref<Order>()
const error = ref(''),
  notice = ref(''),
  mode = ref(''),
  reason = ref(''),
  formError = ref('')
const loading = ref(false),
  busy = ref(false),
  locked = ref(false),
  pending = ref<Pending>()
const demand = ref({
  sourceType: 'MANUAL',
  sourceReference: '',
  productId: 0,
  quantity: '',
  neededDate: '',
  purpose: '',
  salesLineId: 0,
})
const order = ref({ siteId: 0, bomId: 0, plannedDate: '' })
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
const active = computed(() =>
  detail.value?.orders.some((o) => o.status !== 'CANCELLED'),
)
const labels: Record<string, string> = {
  OPEN: '有效需求',
  CANCELLED: '已取消',
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  REJECTED: '已驳回',
  APPROVED: '已批准',
  create: '新建需求',
  order: '转生产订单',
  edit: '编辑工单',
  submit: '提交审核',
  approve: '批准',
  reject: '驳回',
  cancel: '取消工单',
  'cancel-demand': '取消需求',
  DEMAND: '创建需求',
  ORDER: '转生产订单',
  EDIT: '编辑工单',
  SUBMIT: '提交审核',
  APPROVE: '批准',
  REJECT: '驳回',
  CANCEL: '取消工单',
  CANCEL_DEMAND: '取消需求',
  MATERIAL_CALCULATE: '计算材料需求',
  MATERIAL_PURCHASE: '确认采购建议',
}
const label = (s: string) => labels[s] || s
let generation = 0,
  detailGeneration = 0
function canReview(o: Order) {
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
function snapshot(o: Order): Bom | undefined {
  try {
    return JSON.parse(o.bom_snapshot) as Bom
  } catch {
    return undefined
  }
}
function remember(p?: Pending) {
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
      request<{ items: Demand[]; total: number }>(
        `${base}/demands?warehouseId=${warehouse.value}&page=${next}&size=20`,
      ),
      request<Directory[]>('/api/master-data/materials'),
      request<Directory[]>('/api/master-data/production-sites'),
      request<Source[]>(`${base}/sales-sources?warehouseId=${warehouse.value}`),
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
async function openDetail(id: number) {
  if (blocked.value || loading.value) return
  const token = ++detailGeneration
  detail.value = undefined
  error.value = ''
  try {
    const d = await request<Demand>(`${base}/demands/${id}`)
    if (token === detailGeneration) detail.value = d
  } catch (e) {
    if (token === detailGeneration)
      error.value = `详情读取失败：${(e as Error).message}`
  }
}
async function open(value: string, o?: Order) {
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
      const list: Bom[] = []
      for (let p = 0; p < 10; p++) {
        const r = await request<{ items: Bom[]; total: number }>(
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
async function send() {
  if (busy.value || pending.value || locked.value) return
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
  if (busy.value || !pending.value || !pendingPermission.value) return
  busy.value = true
  ++generation
  ++detailGeneration
  error.value = ''
  notice.value = ''
  const p = pending.value
  let saved: Demand
  try {
    saved = await request<Demand>(p.url, { method: p.method, body: p.body })
    remember()
    mode.value = ''
  } catch (e) {
    detail.value = undefined
    result.value = undefined
    mode.value = ''
    if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
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
      const p = JSON.parse(raw) as Pending
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
  <main class="page production-orders">
    <div class="heading">
      <h1>生产需求与订单</h1>
      <span v-if="!write && !review" class="muted">只读</span>
    </div>
    <p class="muted">
      按需求整量建单，审核后固定生产依据。领料与完工将在后续接入。
    </p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="pending" class="pending" role="status">
      有一笔操作结果待核对。<button
        :disabled="busy || !pendingPermission"
        @click="retry"
      >
        原样重试</button
      ><span v-if="!pendingPermission"
        >当前账号无原操作权限，请联系管理员核对。</span
      >
    </div>
    <form class="toolbar" @submit.prevent="load(0)">
      <label
        >目标成品仓<select
          v-model.number="warehouse"
          :disabled="blocked || loading"
          @change="load(0)"
        >
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.code }} · {{ w.name
            }}{{ w.status === 'ENABLED' ? '' : '（停用）' }}
          </option>
        </select></label
      >
      <button :disabled="blocked || loading">刷新</button
      ><button
        v-if="write"
        type="button"
        class="primary"
        :disabled="
          blocked ||
          loading ||
          !result ||
          warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
        "
        @click="open('create')"
      >
        新建需求
      </button>
    </form>
    <p v-if="!loading && !warehouse" class="muted">
      尚未分配成品仓权限，请联系系统管理员。
    </p>
    <div
      class="table-scroll"
      role="region"
      aria-label="生产需求列表，可横向滚动"
      tabindex="0"
    >
      <table>
        <thead>
          <tr>
            <th>来源 / 产品</th>
            <th>计划数量</th>
            <th>需求日期</th>
            <th>当前工单</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in result?.items" :key="d.id">
            <td>
              {{ d.source_reference
              }}<small>{{ d.product_code }} · {{ d.product_name }}</small>
            </td>
            <td>{{ d.quantity }} {{ d.unit }}</td>
            <td>{{ d.needed_date }}</td>
            <td>
              {{ d.order_no || '无有效工单'
              }}<small>{{ label(d.order_status || d.status) }}</small>
            </td>
            <td>
              <button :disabled="blocked || loading" @click="openDetail(d.id)">
                查看
              </button>
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="5">
              {{
                loading
                  ? '正在加载…'
                  : result
                    ? '暂无生产需求'
                    : '资料未就绪，请刷新'
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="result" class="toolbar">
      <span>共 {{ result.total }} 条 · 第 {{ page + 1 }} 页</span
      ><button
        :disabled="blocked || loading || page === 0"
        @click="load(page - 1)"
      >
        上一页</button
      ><button
        :disabled="blocked || loading || (page + 1) * 20 >= result.total"
        @click="load(page + 1)"
      >
        下一页
      </button>
    </div>
    <section v-if="detail" aria-label="生产需求详情" class="detail">
      <h2>
        {{ detail.product_code }} · {{ detail.quantity }} {{ detail.unit }}
      </h2>
      <p>
        {{ detail.source_type === 'SALES' ? '销售来源' : '手工依据' }}：{{
          detail.source_reference
        }}
        · {{ label(detail.status) }}
      </p>
      <p>{{ detail.purpose }} · 需求日期 {{ detail.needed_date }}</p>
      <div v-if="write && detail.status === 'OPEN' && !active" class="toolbar">
        <button :disabled="blocked" @click="open('order')">转生产订单</button
        ><button :disabled="blocked" @click="open('cancel-demand')">
          取消需求
        </button>
      </div>
      <article v-for="o in detail.orders" :key="o.id" class="order">
        <div class="heading">
          <h3>{{ o.order_no }}</h3>
          <span>{{ label(o.status) }}</span>
        </div>
        <p>
          {{ o.site_code }} · {{ o.site_name }} → {{ o.warehouse_name }} · 计划
          {{ o.planned_date }}
        </p>
        <p v-if="o.approved_by">
          批准：{{ o.approved_by }} · {{ o.approved_at }}
        </p>
        <div class="toolbar">
          <template v-if="write && ['DRAFT', 'REJECTED'].includes(o.status)"
            ><button :disabled="blocked" @click="open('edit', o)">
              编辑工单</button
            ><button :disabled="blocked" @click="open('submit', o)">
              提交审核
            </button></template
          >
          <template v-if="o.status === 'SUBMITTED' && canReview(o)"
            ><button :disabled="blocked" @click="open('approve', o)">
              批准</button
            ><button :disabled="blocked" @click="open('reject', o)">
              驳回
            </button></template
          >
          <button
            v-if="write && o.status !== 'CANCELLED'"
            :disabled="blocked"
            @click="open('cancel', o)"
          >
            取消工单
          </button>
        </div>
        <details>
          <summary>
            固定 BOM · {{ snapshot(o)?.version_label || '待核对' }}
          </summary>
          <p v-if="snapshot(o)">
            基准产量 {{ snapshot(o)!.base_quantity }}
            {{ snapshot(o)!.product_unit }}；工单生产量 {{ detail.quantity }}
            {{ detail.unit }}
          </p>
          <p v-else class="error">快照无法读取，请核对原记录。</p>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>组件</th>
                  <th>基准用量</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in snapshot(o)?.components" :key="c.material_code">
                  <td>{{ c.material_code }} · {{ c.material_name }}</td>
                  <td>{{ c.quantity }} {{ c.unit }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p class="muted">工单批准时固定的 BOM 用量依据。</p>
        </details>
        <MaterialPlanPanel
          v-if="['APPROVED', 'CANCELLED'].includes(o.status)"
          :key="`${o.id}:${o.version}`"
          :order-id="o.id"
          :authorities="authorities"
          :username="username"
        />
      </article>
      <details>
        <summary>操作历史（{{ detail.history.length }}，最多 1000 条）</summary>
        <p v-for="h in detail.history" :key="h.id">
          {{ label(h.action) }} · {{ h.created_by }} · {{ h.created_at
          }}<br />{{ h.reason }}
        </p>
      </details>
    </section>
    <div v-if="mode" class="modal-backdrop">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        :aria-label="label(mode)"
      >
        <h2>{{ label(mode) }}</h2>
        <form @submit.prevent="send">
          <fieldset :disabled="busy || !!pending">
            <template v-if="mode === 'create'">
              <label
                >需求来源<select v-model="demand.sourceType">
                  <option value="MANUAL">手工依据</option>
                  <option value="SALES">已批准销售行</option>
                </select></label
              >
              <template v-if="demand.sourceType === 'MANUAL'"
                ><label
                  >来源依据<input
                    v-model="demand.sourceReference"
                    required
                    maxlength="100" /></label
                ><label
                  >产品<select v-model.number="demand.productId" required>
                    <option :value="0" disabled>选择启用物料</option>
                    <option
                      v-for="m in materials.filter(
                        (m) => m.status === 'ENABLED',
                      )"
                      :key="m.id"
                      :value="m.id"
                    >
                      {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                    </option>
                  </select></label
                ><label
                  >计划数量<input
                    v-model="demand.quantity"
                    required
                    inputmode="decimal"
                    pattern="(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,6})?" /></label
                ><label
                  >需求日期<input
                    v-model="demand.neededDate"
                    type="date"
                    required /></label
              ></template>
              <label v-else
                >销售行<select v-model.number="demand.salesLineId" required>
                  <option :value="0" disabled>选择销售行</option>
                  <option v-for="s in sources" :key="s.id" :value="s.id">
                    {{ s.document_no }} / {{ s.id }} · {{ s.material_code }} ·
                    {{ s.quantity }} {{ s.unit }}
                  </option>
                </select></label
              >
              <label
                >生产用途<textarea
                  v-model="demand.purpose"
                  required
                  maxlength="500"
                />
              </label>
              <p class="muted">
                产品与数量保存后不改写；销售来源整行转需求，不自动扣除库存。目录最多显示
                1000 条。
              </p>
            </template>
            <template v-else-if="mode === 'order' || mode === 'edit'"
              ><p>
                {{ detail?.product_code }} · {{ detail?.quantity }}
                {{ detail?.unit }}
              </p>
              <label
                >生产地点<select v-model.number="order.siteId" required>
                  <option :value="0" disabled>选择启用地点</option>
                  <option
                    v-for="s in sites.filter((s) => s.status === 'ENABLED')"
                    :key="s.id"
                    :value="s.id"
                  >
                    {{ s.code }} · {{ s.name }}
                  </option>
                </select></label
              ><label
                >BOM 版本<select v-model.number="order.bomId" required>
                  <option :value="0" disabled>明确选择已发布版本</option>
                  <option v-for="b in boms" :key="b.id" :value="b.id">
                    {{ b.version_label }} · 基准 {{ b.base_quantity }}
                    {{ b.product_unit }}
                  </option>
                </select></label
              ><label
                >计划日期<input
                  v-model="order.plannedDate"
                  type="date"
                  required
              /></label>
              <p class="muted">
                保存所选版本快照，后续新版本不覆盖本工单。地点及版本最多显示
                1000 条。
              </p></template
            >
            <p v-else>
              {{ selected?.order_no || detail?.source_reference }} ·
              {{ detail?.quantity }} {{ detail?.unit }}<br />{{
                mode === 'cancel'
                  ? '本轮工单尚未连接领料与完工；取消后释放需求，历史保留。'
                  : mode === 'approve'
                    ? '批准后固定 BOM、地点和生产数量；不自动发料或报工。'
                    : '请核对当前依据并填写原因。'
              }}
            </p>
            <label v-if="mode !== 'create'"
              >操作原因<textarea v-model="reason" required maxlength="500" />
            </label>
            <p v-if="formError" role="alert" class="error">{{ formError }}</p>
            <div class="toolbar">
              <button type="button" @click="close">返回</button
              ><button
                class="primary"
                :disabled="
                  busy ||
                  ((mode === 'order' || mode === 'edit') &&
                    !boms.some((b) => b.id === order.bomId))
                "
              >
                {{ busy ? '处理中…' : '确认保存' }}
              </button>
            </div>
          </fieldset>
        </form>
      </section>
    </div>
  </main>
</template>

<style scoped>
.production-orders {
  max-width: 1200px;
}
.heading,
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.heading {
  justify-content: space-between;
}
.toolbar {
  margin: 16px 0;
}
label {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.table-scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  min-width: 600px;
}
td,
th {
  text-align: left;
  padding: 12px;
  border-bottom: 1px solid var(--border, #ddd);
}
small {
  display: block;
  margin-top: 4px;
  color: var(--muted, #777);
}
button {
  white-space: nowrap;
}
.detail,
.order {
  border-top: 1px solid var(--border, #ddd);
  padding-top: 20px;
  margin-top: 24px;
}
.order {
  padding-bottom: 20px;
}
details {
  margin: 16px 0;
}
summary {
  cursor: pointer;
}
.pending {
  padding: 12px;
  background: #fff4da;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: #0005;
  display: grid;
  place-items: center;
  padding: 16px;
}
.modal {
  width: min(560px, 100%);
  max-height: 90dvh;
  overflow: auto;
  background: white;
  padding: 24px;
  border-radius: 12px;
  box-sizing: border-box;
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
  display: grid;
  gap: 16px;
}
input,
select,
textarea {
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
textarea {
  min-height: 70px;
}
@media (max-width: 600px) {
  .toolbar label {
    width: 100%;
  }
  .modal {
    padding: 16px;
  }
}
</style>
