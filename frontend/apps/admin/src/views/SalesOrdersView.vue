<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request, type Page, type Warehouse } from '../api'
import { remaining, scaled } from '../quantity'
import type { SalesDocument as Document, SalesFulfillment } from '../sales'

const props = defineProps<{ authorities: string[]; username: string }>()
interface Directory {
  id: number
  code: string
  name: string
  status: string
  unit?: string
}
interface Pending {
  url: string
  method: string
  body: string
  warehouse: number
}
const base = '/api/v1/sales/documents'
const statuses: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  FULFILLING: '履约中',
  CLOSED: '已结案',
  PENDING: '待送达',
  DELIVERED: '已送达',
  WITHDRAWN: '已撤回',
  OPEN: '待预占',
  RESERVED: '待拣货',
  PICKED: '待独立复核',
  VERIFIED: '待实际出库',
  SHIPPED: '已出库',
}
const actions: Record<string, string> = {
  submit: '提交审核',
  approve: '批准',
  reject: '驳回',
  cancel: '取消',
  arrange: '安排发货',
  deliver: '送达 WMS',
  withdraw: '撤回安排',
  close: '履约结案',
}
const auditNames: Record<string, string> = {
  CREATE: '建单',
  EDIT: '编辑',
  SUBMIT: '提交审核',
  APPROVE: '批准',
  REJECT: '驳回',
  CANCEL: '取消',
  ARRANGE: '安排发货',
  DELIVER: '送达 WMS',
  WITHDRAW: '撤回安排',
  DELIVERY_FAILED: '送达失败',
  CLOSE: '履约结案',
}
const warehouse = ref(0),
  page = ref(0)
const warehouses = ref<Warehouse[]>([]),
  materials = ref<Directory[]>([]),
  customers = ref<Directory[]>([])
const result = ref<{ items: Document[]; total: number }>(),
  detail = ref<Document>()
const closureLines = computed(() => {
  if (!detail.value?.closure) return []
  try {
    return (JSON.parse(detail.value.closure.snapshot) as SalesFulfillment).lines
  } catch {
    return []
  }
})
const ready = ref(false),
  loading = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  detailError = ref('')
const mode = ref(''),
  reason = ref(''),
  formError = ref('')
const draft = ref({
  purpose: '',
  neededDate: '',
  customerId: 0,
  customerReference: '',
  lines: [{ materialId: 0, quantity: '' }],
})
const storageKey = `mdop-sales-pending:${props.username}`
const pending = ref<Pending>()
const arrangementId = ref(0)
const arrangementLine = ref(0),
  arrangementQty = ref('')
const canShip = computed(() => props.authorities.includes('wms:sales:read'))
function allocated(lineId: number) {
  const sum = (detail.value?.arrangements || [])
    .filter((a) => a.status !== 'WITHDRAWN' && a.order_line_id === lineId)
    .reduce((n, a) => n + scaled(a.quantity), 0n)
  return `${sum / 1000000n}.${(sum % 1000000n).toString().padStart(6, '0')}`
}
let generation = 0,
  detailGeneration = 0
const writable = computed(() => props.authorities.includes('sales:write'))
const reviewable = computed(() => props.authorities.includes('sales:review'))
const blocked = computed(() => busy.value || !!pending.value || !!mode.value)
const canEdit = computed(
  () =>
    detail.value &&
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
function remember(value?: Pending) {
  // Persist the original payload before sending so a reload cannot silently generate a new key.
  if (value) sessionStorage.setItem(storageKey, JSON.stringify(value))
  else sessionStorage.removeItem(storageKey)
  pending.value = value
}
async function load(next = 0) {
  if (busy.value) return
  const token = ++generation
  ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  result.value = undefined
  error.value = ''
  loading.value = true
  page.value = next
  try {
    if (!ready.value) {
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
        request<Directory[]>('/api/master-data/materials'),
        request<Directory[]>('/api/master-data/customers'),
      ])
      if (token !== generation) return
      warehouses.value = all
      materials.value = m
      customers.value = s
      warehouse.value = all.some((w) => w.id === warehouse.value)
        ? warehouse.value
        : all[0]?.id || 0
      ready.value = true
    }
    if (!warehouse.value) return
    const data = await request<{ items: Document[]; total: number }>(
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
  const token = ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  try {
    const data = await request<Document>(`${base}/${id}`)
    if (token === detailGeneration) {
      detail.value = data
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
function stateText(value: string) {
  return statuses[value] || value
}
function describeSnapshot(raw: string | null) {
  if (!raw) return '无前值'
  try {
    const d = JSON.parse(raw) as Document
    return [
      stateText(d.status),
      d.purpose,
      `${d.warehouse_name} · ${d.needed_date}`,
      d.customer_name,
      ...d.lines.map(
        (l) =>
          `${l.material_code} · ${l.material_name}：${l.quantity} ${l.unit}`,
      ),
      ...(d.arrangements || []).map(
        (a) =>
          `发货 #${a.id} · ${stateText(a.status)} · ${a.expected_date} · ${a.quantity}${a.last_error ? ' · ' + a.last_error : ''}`,
      ),
    ]
      .filter(Boolean)
      .join('\n')
  } catch {
    return '快照暂时无法展示，请联系管理员核对。'
  }
}
async function send() {
  if (busy.value || pending.value) return
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
  if (busy.value || !pending.value) return
  busy.value = true
  error.value = ''
  formError.value = ''
  notice.value = ''
  const original = pending.value
  let saved: Document
  try {
    saved = await request<Document>(original.url, {
      method: original.method,
      body: original.body,
    })
    remember()
    mode.value = ''
  } catch (e) {
    const failure = e as Error
    if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
      remember()
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
    busy.value = true
    return
  }
  if (stored) {
    try {
      const parsed = JSON.parse(stored) as Pending
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
      busy.value = true
      return
    }
  }
  void load()
})
onBeforeUnmount(() => {
  generation++
  detailGeneration++
})
</script>
<template>
  <main class="page sales-page">
    <div class="page-heading">
      <h1>销售订单</h1>
      <span v-if="!writable && !reviewable" class="hint">只读</span>
    </div>
    <p class="hint">客户订单 → 分批发货 → 实际出库 → 数量结案</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <div v-if="pending" class="pending panel">
      <strong>有一笔操作等待确认</strong>
      <p>原请求已保留。原样重试会核对同一笔操作，不会重新建单。</p>
      <button :disabled="busy" @click="retry">
        {{ busy ? '正在核对…' : '原样重试' }}
      </button>
    </div>
    <section class="panel">
      <div class="filters">
        <label
          >发货仓库<select
            v-model="warehouse"
            :disabled="blocked || loading"
            @change="load()"
          >
            <option v-for="w in warehouses" :key="w.id" :value="w.id">
              {{ w.name }}{{ w.status === 'DISABLED' ? '（停用）' : '' }}
            </option>
          </select></label
        >
        <button :disabled="busy || loading || !!mode" @click="load(page)">
          刷新
        </button>
        <button
          v-if="writable"
          class="primary create"
          :disabled="
            blocked ||
            !result ||
            warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
          "
          @click="open('create')"
        >
          新建销售订单
        </button>
      </div>
      <p v-if="ready && !warehouse" class="hint empty">
        暂无已授权成品仓，请联系管理员分配仓库范围。
      </p>
      <div
        class="table-wrap"
        tabindex="0"
        aria-label="销售订单列表，可横向滚动"
      >
        <table>
          <thead>
            <tr>
              <th>订单 / 用途</th>
              <th>客户</th>
              <th>约定日期</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in result?.items" :key="d.id">
              <td>
                {{ d.document_no }}<small>{{ d.purpose }}</small>
              </td>
              <td>{{ d.customer_name }}</td>
              <td>{{ d.needed_date }}</td>
              <td>{{ stateText(d.status) }}</td>
              <td>
                <button :disabled="blocked" @click="openDetail(d.id)">
                  查看
                </button>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="5" class="empty">
                {{
                  loading
                    ? '正在加载…'
                    : result
                      ? '暂无订单'
                      : '资料未就绪，请刷新'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pagination">
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
    </section>
    <p v-if="detailError" class="error" role="alert">
      详情读取失败：{{ detailError }}，请重新选择单据。
    </p>
    <section v-if="detail" class="panel detail">
      <div class="detail-heading">
        <div>
          <h2>{{ detail.document_no }} · {{ stateText(detail.status) }}</h2>
          <p class="hint">
            {{ detail.customer_name }} · {{ detail.warehouse_name }} ·
            {{ detail.needed_date }}
          </p>
        </div>
        <button :disabled="blocked" @click="detail = undefined">
          收起详情
        </button>
      </div>
      <p>{{ detail.purpose }}</p>
      <p v-if="detail.customer_reference" class="hint">
        客户单号：{{ detail.customer_reference }}
      </p>
      <div class="detail-actions">
        <button v-if="canEdit" :disabled="blocked" @click="open('edit')">
          编辑</button
        ><button
          v-if="canEdit"
          class="primary"
          :disabled="blocked"
          @click="open('submit')"
        >
          提交审核
        </button>
        <button
          v-if="canReview"
          class="primary"
          :disabled="blocked"
          @click="open('approve')"
        >
          批准</button
        ><button v-if="canReview" :disabled="blocked" @click="open('reject')">
          驳回
        </button>
        <button
          v-if="writable && !['CLOSED', 'CANCELLED'].includes(detail.status)"
          :disabled="
            blocked || detail.arrangements.some((a) => a.status !== 'WITHDRAWN')
          "
          @click="open('cancel')"
        >
          取消订单
        </button>
      </div>
      <p v-if="detail.status === 'SUBMITTED' && !canReview" class="hint">
        等待其他销售审批员审核。
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>订单数量</th>
              <th>已安排</th>
              <th>实际已发</th>
              <th>未发数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.fulfillment.lines" :key="l.orderLineId">
              <td>
                {{ l.code
                }}<small>{{
                  detail.lines.find((x) => x.id === l.orderLineId)
                    ?.material_name
                }}</small>
              </td>
              <td>{{ l.ordered }} {{ l.unit }}</td>
              <td>{{ l.allocated }}</td>
              <td>{{ l.shipped }}</td>
              <td>{{ l.remaining }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <section class="arrangements">
        <div class="detail-heading">
          <h2>发货安排</h2>
          <button
            v-if="
              writable && ['APPROVED', 'FULFILLING'].includes(detail.status)
            "
            class="primary"
            :disabled="blocked"
            @click="open('arrange')"
          >
            安排发货
          </button>
        </div>
        <p class="hint">
          每次安排一个物料，仓库选择一个批次整笔执行。待送达、失败和未确认撤回仍占额度。
        </p>
        <p v-if="!detail.arrangements.length" class="hint">暂无发货安排</p>
        <article
          v-for="a in detail.arrangements"
          :key="a.id"
          class="arrangement-record"
        >
          <strong
            >发货 #{{ a.id }} · {{ stateText(a.status)
            }}{{ a.wms ? ' · ' + stateText(a.wms.status) : '' }}</strong
          >
          <p>
            {{
              detail.lines.find((l) => l.id === a.order_line_id)?.material_code
            }}
            · {{ a.quantity }} · 预计发货 {{ a.expected_date }}
          </p>
          <p v-if="a.last_error" class="error">
            送达失败：{{ a.last_error }}。核对后可重试，额度仍保留。
          </p>
          <p v-if="a.wms" class="hint">
            WMS #{{ a.wms.id }} · 批次 {{ a.wms.batchNo || '待选择' }} ·
            实际已发 {{ a.wms.shippedQuantity }}
          </p>
          <div class="detail-actions">
            <button
              v-if="writable && a.status === 'PENDING'"
              :disabled="blocked"
              @click="open('deliver', a.id)"
            >
              {{ a.last_error ? '重试送达' : '送达 WMS' }}</button
            ><button
              v-if="
                writable &&
                a.status !== 'WITHDRAWN' &&
                a.wms?.status !== 'SHIPPED'
              "
              :disabled="blocked"
              @click="open('withdraw', a.id)"
            >
              撤回安排</button
            ><a
              v-if="canShip && a.wms_sales_id && !blocked"
              :href="`/sales?warehouseId=${detail.warehouse_id}&salesOrderId=${a.wms_sales_id}`"
              >查看 WMS 出库</a
            >
          </div>
        </article>
      </section>
      <section class="arrangements">
        <div class="detail-heading">
          <h2>履约核对</h2>
          <button :disabled="blocked" @click="openDetail(detail.id)">
            核对最新进度
          </button>
        </div>
        <template v-if="detail.closure"
          ><p class="success">
            已保存数量结案快照 · {{ detail.closure.closed_by }} ·
            {{ detail.closure.closed_at }}
          </p>
          <p>{{ detail.closure.reason }}</p>
          <p v-if="!detail.closureMatches" class="error" role="alert">
            当前事实与结案快照不一致，请联系管理员核对。
          </p>
          <details>
            <summary>结案时数量</summary>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>物料</th>
                    <th>订单数量</th>
                    <th>实际已发</th>
                    <th>未发数量</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="l in closureLines" :key="l.orderLineId">
                    <td>{{ l.code }}</td>
                    <td>{{ l.ordered }} {{ l.unit }}</td>
                    <td>{{ l.shipped }}</td>
                    <td>{{ l.remaining }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details></template
        >
        <template v-else
          ><ul v-if="detail.fulfillment.blockers.length">
            <li v-for="b in detail.fulfillment.blockers" :key="b">{{ b }}</li>
          </ul>
          <p v-else class="success">授权数量与实际出库、流水一致，可以结案。</p>
          <button
            v-if="writable && detail.status === 'FULFILLING'"
            class="primary"
            :disabled="blocked || !detail.fulfillment.canClose"
            @click="open('close')"
          >
            履约结案
          </button></template
        >
        <p class="hint">
          结案仅确认数量履约。金额依据未建立，不代表应收或收款结清。
        </p>
      </section>
      <details class="history">
        <summary>操作历史（{{ detail.history.length }}）</summary>
        <div v-for="h in detail.history" :key="h.id" class="history-item">
          <strong>{{ auditNames[h.action] || h.action }}</strong> ·
          {{ h.created_by }} · {{ h.created_at }}
          <p>{{ h.reason }}</p>
          <details>
            <summary>变更快照</summary>
            <p class="hint">变更前</p>
            <pre>{{ describeSnapshot(h.before_state) }}</pre>
            <p class="hint">变更后</p>
            <pre>{{ describeSnapshot(h.after_state) }}</pre>
          </details>
        </div>
      </details>
    </section>
    <div v-if="mode" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sales-modal-title"
      >
        <h2 id="sales-modal-title">
          {{
            mode === 'create'
              ? '新建销售订单'
              : mode === 'edit'
                ? '编辑订单'
                : actions[mode]
          }}
        </h2>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <p v-if="mode === 'withdraw'" class="hint">
          尚未实际出库的安排可撤回；WMS 确认取消并释放预占后，才释放订单额度。
        </p>
        <p v-if="mode === 'close'" class="hint">
          逐行核对授权数量、实际出库及流水，保存不可覆盖的数量结案快照。
        </p>
        <form @submit.prevent="send">
          <fieldset :disabled="busy || !!pending">
            <div class="form-grid">
              <template v-if="['create', 'edit'].includes(mode)"
                ><label
                  >用途<input
                    v-model="draft.purpose"
                    required
                    maxlength="500" /></label
                ><label
                  >客户<select v-model="draft.customerId" required>
                    <option :value="0" disabled>请选择客户</option>
                    <option
                      v-for="c in customers"
                      :key="c.id"
                      :value="c.id"
                      :disabled="c.status !== 'ENABLED'"
                    >
                      {{ c.code }} · {{ c.name
                      }}{{ c.status !== 'ENABLED' ? '（停用）' : '' }}
                    </option>
                  </select></label
                ><label
                  >客户单号（选填）<input
                    v-model="draft.customerReference"
                    maxlength="128" /></label
              ></template>
              <label v-if="['create', 'edit', 'arrange'].includes(mode)"
                >{{ mode === 'arrange' ? '预计发货日期' : '约定交付日期'
                }}<input v-model="draft.neededDate" type="date" required
              /></label>
            </div>
            <template v-if="mode === 'arrange'"
              ><label
                >订单物料<select v-model="arrangementLine" required>
                  <option v-for="l in detail?.lines" :key="l.id" :value="l.id">
                    {{ l.material_code }} · 可安排
                    {{ remaining(l.quantity, allocated(l.id)) }} {{ l.unit }}
                  </option>
                </select></label
              ><label class="reason"
                >本次安排数量<input
                  v-model="arrangementQty"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?" /></label
            ></template>
            <template v-if="['create', 'edit'].includes(mode)"
              ><p class="hint">
                使用基本单位，同物料自动合并。批准后数量不可修改。
              </p>
              <div
                v-for="(l, index) in draft.lines"
                :key="index"
                class="draft-line"
              >
                <label
                  >物料 {{ index + 1
                  }}<select v-model="l.materialId" required>
                    <option :value="0" disabled>请选择物料</option>
                    <option
                      v-for="m in materials"
                      :key="m.id"
                      :value="m.id"
                      :disabled="m.status !== 'ENABLED'"
                    >
                      {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                    </option>
                  </select></label
                ><label
                  >数量 {{ index + 1
                  }}<input
                    v-model="l.quantity"
                    required
                    inputmode="decimal"
                    pattern="[0-9]{1,12}(\.[0-9]{1,6})?" /></label
                ><button
                  type="button"
                  :disabled="draft.lines.length === 1"
                  @click="draft.lines.splice(index, 1)"
                >
                  移除
                </button>
              </div>
              <button
                type="button"
                :disabled="draft.lines.length >= 100"
                @click="draft.lines.push({ materialId: 0, quantity: '' })"
              >
                添加物料
              </button>
              <p
                v-if="materials.length >= 1000 || customers.length >= 1000"
                class="hint"
              >
                目录最多加载 1000 条，未找到资料时请联系资料管理员核对。
              </p></template
            >
            <label v-if="mode !== 'create'" class="reason"
              >{{ mode === 'edit' ? '变更原因' : '操作说明'
              }}<textarea v-model="reason" required maxlength="500" />
            </label>
            <div class="modal-actions">
              <button type="button" @click="close">取消</button
              ><button class="primary" type="submit">
                {{
                  busy
                    ? '处理中…'
                    : ['create', 'edit'].includes(mode)
                      ? '保存草稿'
                      : '确认' + (actions[mode] || '操作')
                }}
              </button>
            </div>
          </fieldset>
        </form>
        <button v-if="pending" :disabled="busy" @click="retry">
          结果待核对，原样重试
        </button>
      </section>
    </div>
  </main>
</template>

<style scoped>
.sales-page h1 {
  font-size: 24px;
}
.arrangements {
  margin-top: 24px;
}
.arrangement-record {
  padding: 16px 0;
  border-top: 1px solid #ece9f1;
}
.purchase-tabs {
  padding: 12px 16px 0;
  margin: 0;
}
.create {
  margin-left: auto;
}
.filters label {
  min-width: 220px;
}
.pagination,
.detail-actions,
.related {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 12px 16px;
}
.pagination span {
  margin-right: auto;
  font-size: 13px;
  color: #6c687d;
}
.detail {
  margin-top: 20px;
  padding: 20px;
}
.detail-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.detail h2 {
  font-size: 19px;
  margin-bottom: 6px;
}
.detail-actions,
.related {
  padding: 8px 0 16px;
}
.history {
  margin-top: 16px;
}
.history summary {
  cursor: pointer;
  color: #6941b5;
}
.history-item {
  padding: 12px 0;
  border-bottom: 1px solid #eee;
}
.history-item p {
  margin: 4px 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
.draft-line {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
  margin: 12px 0;
}
.reason {
  margin-top: 16px;
}
.pending {
  padding: 16px;
  margin-bottom: 16px;
}
.pending p {
  margin: 6px 0 12px;
}
@media (max-width: 640px) {
  .draft-line {
    grid-template-columns: 1fr;
  }
  .filters label {
    min-width: 0;
    flex: 1 1 100%;
  }
  .detail {
    padding: 14px;
  }
  .detail-heading {
    flex-wrap: wrap;
  }
  .table-wrap table {
    min-width: 560px;
  }
}
</style>
