<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request, type Page, type Warehouse } from '../api'
import { remaining, scaled } from '../quantity'

const props = defineProps<{ authorities: string[]; username: string }>()
type Kind = 'REQUEST' | 'ORDER'
interface Line {
  id: number
  source_line_id: number | null
  material_id: number
  material_code: string
  material_name: string
  unit: string
  quantity: string
}
interface Document {
  id: number
  document_no: string
  kind: Kind
  warehouse_id: number
  warehouse_name: string
  status: string
  version: number
  purpose: string
  needed_date: string
  supplier_id: number | null
  supplier_name: string | null
  request_id: number | null
  created_by: string
  last_edited_by: string
  submitted_by: string | null
  lines: Line[]
  history: {
    id: number
    action: string
    reason: string
    created_by: string
    created_at: string
    before_state: string | null
    after_state: string
  }[]
  related: { id: number; document_no: string; status: string }[]
  arrangements?: Arrangement[]
}
interface Arrangement {
  id: number
  expected_date: string
  status: string
  last_error: string | null
  wms_arrival_id: number | null
  lines: {
    id: number
    order_line_id: number
    material_code: string
    quantity: string
    wms_arrival_item_id: number | null
  }[]
  wms?: {
    status: string
    lines: { arrangementLineId: number; receivedQty: string }[]
    receipts: { id: number; number: string; status: string }[]
  }
}
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
const base = '/api/v1/purchasing/documents'
const statuses: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CONVERTED: '已转单',
  CANCELLED: '已取消',
  FULFILLING: '履约中',
  PENDING: '待送达',
  DELIVERED: '已送达',
  WITHDRAWN: '已撤回',
}
const actions: Record<string, string> = {
  submit: '提交审核',
  approve: '批准',
  reject: '驳回',
  cancel: '取消',
  convert: '转采购订单',
  arrange: '安排到货',
  deliver: '送达 WMS',
  withdraw: '撤回安排',
}
const auditNames: Record<string, string> = {
  CREATE: '建单',
  EDIT: '编辑',
  SUBMIT: '提交审核',
  APPROVE: '批准',
  REJECT: '驳回',
  CANCEL: '取消',
  CONVERT: '转采购订单',
  RELEASE_ORDER: '释放订单占用',
  ARRANGE: '安排到货',
  DELIVER: '送达 WMS',
  WITHDRAW: '撤回安排',
  DELIVERY_FAILED: '送达失败',
}
const kind = ref<Kind>('REQUEST'),
  warehouse = ref(0),
  page = ref(0)
const warehouses = ref<Warehouse[]>([]),
  materials = ref<Directory[]>([]),
  suppliers = ref<Directory[]>([])
const result = ref<{ items: Document[]; total: number }>(),
  detail = ref<Document>()
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
  supplierId: 0,
  lines: [{ materialId: 0, quantity: '' }],
})
const storageKey = `mdop-purchasing-pending:${props.username}`
const pending = ref<Pending>()
const arrangementId = ref(0)
const arrangementLines = ref<{ orderLineId: number; quantity: string }[]>([])
const canReceive = computed(() =>
  props.authorities.includes('wms:arrival:read'),
)
function allocated(lineId: number) {
  const value = (detail.value?.arrangements || [])
    .filter((a) => a.status !== 'WITHDRAWN')
    .flatMap((a) => a.lines)
    .filter((l) => l.order_line_id === lineId)
    .reduce((sum, l) => sum + scaled(l.quantity), 0n)
  return `${value / 1000000n}.${(value % 1000000n).toString().padStart(6, '0')}`
}
let generation = 0,
  detailGeneration = 0
const writable = computed(() => props.authorities.includes('purchasing:write'))
const reviewable = computed(() =>
  props.authorities.includes('purchasing:review'),
)
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
          ...data.items.filter((w) =>
            props.authorities.includes(`wms:warehouse:${w.id}`),
          ),
        )
        if (p >= data.totalPages) break
      }
      const [m, s] = await Promise.all([
        request<Directory[]>('/api/master-data/materials'),
        request<Directory[]>('/api/master-data/suppliers'),
      ])
      if (token !== generation) return
      warehouses.value = all
      materials.value = m
      suppliers.value = s
      warehouse.value = all.some((w) => w.id === warehouse.value)
        ? warehouse.value
        : all[0]?.id || 0
      ready.value = true
    }
    if (!warehouse.value) return
    const data = await request<{ items: Document[]; total: number }>(
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
  const token = ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  try {
    const data = await request<Document>(`${base}/${id}`)
    if (token === detailGeneration) detail.value = data
  } catch (e) {
    if (token === detailGeneration) detailError.value = (e as Error).message
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
  draft.value =
    value === 'create'
      ? {
          purpose: '',
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
      d.supplier_name,
      ...d.lines.map(
        (l) =>
          `${l.material_code} · ${l.material_name}：${l.quantity} ${l.unit}`,
      ),
      ...(d.arrangements || []).map(
        (a) =>
          `到货 #${a.id} · ${stateText(a.status)} · ${a.expected_date} · ${a.lines.map((l) => `${l.material_code} ${l.quantity}`).join('，')}${a.last_error ? ' · ' + a.last_error : ''}`,
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
  const stored = sessionStorage.getItem(storageKey)
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
        '保存的采购重试凭据无法读取，请联系管理员核对；本页已停止新操作。'
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
  <main class="page purchasing-page">
    <div class="page-heading">
      <h1>采购需求与订单</h1>
      <span v-if="!writable && !reviewable" class="hint">只读</span>
    </div>
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
      <div class="tabs purchase-tabs">
        <button
          :class="{ active: kind === 'REQUEST' }"
          :aria-pressed="kind === 'REQUEST'"
          :disabled="blocked"
          @click="switchKind('REQUEST')"
        >
          采购需求
        </button>
        <button
          :class="{ active: kind === 'ORDER' }"
          :aria-pressed="kind === 'ORDER'"
          :disabled="blocked"
          @click="switchKind('ORDER')"
        >
          采购订单
        </button>
      </div>
      <div class="filters">
        <label
          >收货仓库<select
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
          v-if="writable && kind === 'REQUEST'"
          class="primary create"
          :disabled="
            blocked ||
            !result ||
            warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
          "
          @click="open('create')"
        >
          新建需求
        </button>
      </div>
      <p v-if="ready && !warehouse" class="hint empty">
        暂无已授权仓库，请联系管理员分配仓库范围。
      </p>
      <div
        class="table-wrap"
        tabindex="0"
        aria-label="采购单据列表，可横向滚动"
      >
        <table>
          <thead>
            <tr>
              <th>单号 / 用途</th>
              <th v-if="kind === 'ORDER'">供应商</th>
              <th>需要 / 到货日期</th>
              <th>状态</th>
              <th>申请人</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in result?.items" :key="d.id">
              <td>
                {{ d.document_no }}<small>{{ d.purpose }}</small>
              </td>
              <td v-if="kind === 'ORDER'">{{ d.supplier_name }}</td>
              <td>{{ d.needed_date }}</td>
              <td>{{ stateText(d.status) }}</td>
              <td>{{ d.created_by }}</td>
              <td>
                <button :disabled="blocked" @click="openDetail(d.id)">
                  查看
                </button>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="6" class="empty">
                {{
                  loading
                    ? '正在加载…'
                    : result
                      ? '暂无单据'
                      : '资料未就绪，请刷新'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pagination">
        <span>共 {{ result.total }} 条 · 第 {{ page + 1 }} 页</span
        ><button :disabled="blocked || page === 0" @click="load(page - 1)">
          上一页</button
        ><button
          :disabled="blocked || (page + 1) * 20 >= result.total"
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
          <h2>
            {{ detail.kind === 'REQUEST' ? '采购需求' : '采购订单' }} ·
            {{ stateText(detail.status) }}
          </h2>
          <p class="hint">{{ detail.document_no }}</p>
        </div>
        <button :disabled="blocked" @click="detail = undefined">
          收起详情
        </button>
      </div>
      <p>{{ detail.purpose }}</p>
      <p class="hint">
        {{ detail.warehouse_name }} · {{ detail.needed_date
        }}{{ detail.supplier_name ? ' · ' + detail.supplier_name : '' }}
      </p>
      <div class="detail-actions">
        <button v-if="canEdit" :disabled="blocked" @click="open('edit')">
          编辑
        </button>
        <button
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
          批准
        </button>
        <button v-if="canReview" :disabled="blocked" @click="open('reject')">
          驳回
        </button>
        <button
          v-if="
            writable &&
            detail.kind === 'REQUEST' &&
            detail.status === 'APPROVED'
          "
          class="primary"
          :disabled="blocked"
          @click="open('convert')"
        >
          转采购订单
        </button>
        <button
          v-if="
            writable &&
            [
              'DRAFT',
              'REJECTED',
              'SUBMITTED',
              'APPROVED',
              'FULFILLING',
            ].includes(detail.status)
          "
          :disabled="
            blocked ||
            (detail.kind === 'ORDER' &&
              (detail.arrangements || []).some((a) => a.status !== 'WITHDRAWN'))
          "
          @click="open('cancel')"
        >
          {{ detail.kind === 'REQUEST' ? '撤销需求' : '取消订单' }}
        </button>
      </div>
      <p v-if="detail.status === 'SUBMITTED' && !canReview" class="hint">
        等待其他采购审批员审核。
      </p>
      <p v-if="detail.kind === 'ORDER'" class="hint">
        金额依据：未建立。物料与数量来自已批准需求，不能在订单中修改。
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料编码</th>
              <th>物料名称</th>
              <th>数量</th>
              <th>单位</th>
              <th v-if="detail.kind === 'ORDER'">需求行</th>
              <th v-if="detail.kind === 'ORDER'">已安排 / 剩余</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.lines" :key="l.id">
              <td>{{ l.material_code }}</td>
              <td>{{ l.material_name }}</td>
              <td>{{ l.quantity }}</td>
              <td>{{ l.unit }}</td>
              <td v-if="detail.kind === 'ORDER'">{{ l.source_line_id }}</td>
              <td v-if="detail.kind === 'ORDER'">
                {{ allocated(l.id) }} /
                {{ remaining(l.quantity, allocated(l.id)) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <section v-if="detail.kind === 'ORDER'" class="arrangements">
        <div class="detail-heading">
          <h2>到货安排</h2>
          <button
            v-if="
              writable && ['APPROVED', 'FULFILLING'].includes(detail.status)
            "
            class="primary"
            :disabled="blocked"
            @click="open('arrange')"
          >
            安排到货
          </button>
        </div>
        <p class="hint">
          待送达和送达失败仍占用数量；全部确认撤回后才可取消订单。
        </p>
        <p v-if="!detail.arrangements?.length" class="hint">暂无到货安排</p>
        <article
          v-for="a in detail.arrangements"
          :key="a.id"
          class="arrangement-record"
        >
          <strong
            >PA-{{ String(a.id).padStart(8, '0') }} ·
            {{ stateText(a.status) }}</strong
          >
          <p class="hint">
            预计到货 {{ a.expected_date
            }}<span v-if="a.wms_arrival_id">
              · WMS 通知 #{{ a.wms_arrival_id }}</span
            >
          </p>
          <p v-if="a.last_error" class="error">
            送达失败：{{ a.last_error }}。核对后可重试，额度仍保留。
          </p>
          <p v-for="l in a.lines" :key="l.id">
            {{ l.material_code }} · 安排 {{ l.quantity
            }}<span v-if="a.wms">
              · 已收
              {{
                a.wms.lines.find((x) => x.arrangementLineId === l.id)
                  ?.receivedQty || '0'
              }}</span
            >
          </p>
          <div class="detail-actions">
            <button
              v-if="writable && a.status === 'PENDING'"
              :disabled="blocked"
              @click="open('deliver', a.id)"
            >
              {{ a.last_error ? '重试送达' : '送达 WMS' }}
            </button>
            <button
              v-if="writable && a.status !== 'WITHDRAWN'"
              :disabled="blocked || !!a.wms?.receipts.length"
              @click="open('withdraw', a.id)"
            >
              撤回安排
            </button>
            <a
              v-if="canReceive && a.wms_arrival_id && !blocked"
              :href="`/receiving?warehouseId=${detail.warehouse_id}&arrivalId=${a.wms_arrival_id}`"
              >查看 WMS 收货</a
            >
          </div>
          <p v-if="a.wms?.receipts.length" class="hint">
            已有收货记录，不能撤回。<span
              v-for="r in a.wms.receipts"
              :key="r.id"
            >
              {{ r.number }}（{{
                r.status === 'DRAFT' ? '草稿' : '已提交'
              }}）</span
            >
          </p>
        </article>
      </section>
      <div v-if="detail.related.length" class="related">
        <strong>关联单据</strong
        ><button
          v-for="d in detail.related"
          :key="d.id"
          :disabled="blocked"
          @click="openDetail(d.id)"
        >
          {{ d.document_no }} · {{ stateText(d.status) }}
        </button>
      </div>
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
        aria-labelledby="purchase-modal-title"
      >
        <h2 id="purchase-modal-title">
          {{
            mode === 'create'
              ? '新建采购需求'
              : mode === 'edit'
                ? '编辑单据'
                : actions[mode]
          }}
        </h2>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <p v-if="mode === 'cancel'" class="hint">
          取消保留原单和历史。采购订单取消成功后，对应需求可重新转单。
        </p>
        <p v-if="mode === 'convert'" class="hint">
          按已批准需求整单转入一个供应商，数量与单位保持不变。
        </p>
        <p v-if="mode === 'withdraw'" class="hint">
          只有没有任何收货记录（含草稿）的安排才能撤回；WMS
          确认撤回后才释放数量。
        </p>
        <p v-if="mode === 'deliver'" class="hint">
          将这份安排送达 WMS 供仓管收货；重复请求不会新建另一份通知。
        </p>
        <form @submit.prevent="send">
          <fieldset :disabled="busy || !!pending">
            <div class="form-grid">
              <template v-if="mode === 'create' || mode === 'edit'"
                ><label
                  >用途<input
                    v-model="draft.purpose"
                    required
                    maxlength="500" /></label
              ></template>
              <label
                v-if="['create', 'edit', 'convert', 'arrange'].includes(mode)"
                >{{
                  mode === 'arrange'
                    ? '预计到货日期'
                    : (mode === 'edit' && detail?.kind === 'ORDER') ||
                        mode === 'convert'
                      ? '约定到货日期'
                      : '需要日期'
                }}<input v-model="draft.neededDate" type="date" required
              /></label>
              <label
                v-if="
                  mode === 'convert' ||
                  (mode === 'edit' && detail?.kind === 'ORDER')
                "
                >供应商<select v-model="draft.supplierId" required>
                  <option :value="0" disabled>请选择供应商</option>
                  <option
                    v-for="s in suppliers"
                    :key="s.id"
                    :value="s.id"
                    :disabled="s.status !== 'ENABLED'"
                  >
                    {{ s.code }} · {{ s.name
                    }}{{ s.status !== 'ENABLED' ? '（停用）' : '' }}
                  </option>
                </select></label
              >
            </div>
            <template v-if="mode === 'arrange'">
              <p class="hint">填写本次安排数量；不安排的物料留空。</p>
              <label
                v-for="(l, index) in arrangementLines"
                :key="l.orderLineId"
                class="reason"
                >{{ detail?.lines[index]?.material_code }} · 剩余
                {{
                  remaining(
                    detail?.lines[index]?.quantity || '0',
                    allocated(l.orderLineId),
                  )
                }}
                <input
                  v-model="l.quantity"
                  :aria-label="`安排数量 ${index + 1}`"
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
                />
              </label>
            </template>
            <template v-if="mode === 'create' || mode === 'edit'">
              <p class="hint">
                使用基本单位；同物料自动合并。订单保留已批准需求数量。
              </p>
              <div
                v-for="(l, index) in draft.lines"
                :key="index"
                class="draft-line"
              >
                <label
                  >物料 {{ index + 1
                  }}<select
                    v-model="l.materialId"
                    required
                    :disabled="mode === 'edit' && detail?.kind === 'ORDER'"
                  >
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
                >
                <label
                  >数量 {{ index + 1
                  }}<input
                    v-model="l.quantity"
                    required
                    inputmode="decimal"
                    pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
                    :disabled="mode === 'edit' && detail?.kind === 'ORDER'"
                /></label>
                <button
                  v-if="mode === 'create' || detail?.kind === 'REQUEST'"
                  type="button"
                  :disabled="draft.lines.length === 1"
                  @click="draft.lines.splice(index, 1)"
                >
                  移除
                </button>
              </div>
              <button
                v-if="mode === 'create' || detail?.kind === 'REQUEST'"
                type="button"
                :disabled="draft.lines.length >= 100"
                @click="draft.lines.push({ materialId: 0, quantity: '' })"
              >
                添加物料
              </button>
              <p
                v-if="materials.length >= 1000 || suppliers.length >= 1000"
                class="hint"
              >
                目录最多加载 1000 条；未找到所需资料时请联系资料管理员核对。
              </p>
            </template>
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
                    : mode === 'create' || mode === 'edit'
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
.purchasing-page h1 {
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
