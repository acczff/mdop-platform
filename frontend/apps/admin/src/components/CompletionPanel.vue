<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
const props = defineProps<{
  orderId: number
  username: string
  authorities: string[]
}>()
interface Report {
  id: number
  report_no: string
  quantity: string
  batch_no: string
  date_code: string
  production_date: string
  expiry_date: string | null
  reason: string
  status: string
  version: number
  created_by: string
  reviewed_by: string | null
  review_reason: string | null
  receipt?: {
    id: number
    warehouse_id: number
    status: string
    amount: string
    quality_reason: string | null
    stored_balance_id: number | null
  }
}
interface Detail {
  orderStatus: string
  authorized: string
  pending: string
  approved: string
  remaining: string
  unit: string
  reports: Report[]
}
type Pending = { path: string; body: string }
const base = `/api/v1/manufacturing/orders/${props.orderId}/completions`
const storageKey = `mdop-completion:${props.username}:${props.orderId}`
const data = ref<Detail>(),
  pending = ref<Pending>(),
  busy = ref(false),
  locked = ref(false),
  loading = ref(false)
const error = ref(''),
  notice = ref(''),
  reason = ref(''),
  ack = ref(false)
const form = ref({
  reportNo: '',
  quantity: '',
  batchNo: '',
  dateCode: '',
  productionDate: '',
  expiryDate: '',
})
const report = computed(() =>
  props.authorities.includes('manufacturing:completion:report'),
)
const review = computed(() =>
  props.authorities.includes('manufacturing:completion:review'),
)
const blocked = computed(
  () =>
    busy.value ||
    loading.value ||
    locked.value ||
    !!pending.value ||
    !data.value,
)
const retryAllowed = computed(() =>
  /\/(approve|reject)$/.test(pending.value?.path || '')
    ? review.value
    : report.value,
)
const labels: Record<string, string> = {
  SUBMITTED: '待复核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CANCELLED: '已撤销',
  OPEN: '待实物收货',
  RECEIVED: '待检验',
  QUALIFIED: '合格待上架',
  STORED: '已上架',
}
let generation = 0,
  alive = true
function remember(p?: Pending) {
  if (p) sessionStorage.setItem(storageKey, JSON.stringify(p))
  else sessionStorage.removeItem(storageKey)
  pending.value = p
}
async function load() {
  const token = ++generation
  data.value = undefined
  loading.value = true
  try {
    const result = await request<Detail>(base)
    if (alive && token === generation) data.value = result
  } catch (e) {
    if (alive && token === generation)
      error.value = `读取完工记录失败：${(e as Error).message}`
  } finally {
    if (alive && token === generation) loading.value = false
  }
}
async function send(path: string, body: object) {
  if (blocked.value || !ack.value) return
  if (!reason.value.trim()) {
    error.value = '请填写登记或处理依据'
    return
  }
  try {
    remember({ path, body: JSON.stringify(body) })
  } catch {
    locked.value = true
    error.value = '无法保存重试凭据，停止新操作'
    return
  }
  await retry()
}
async function save() {
  if (!report.value || blocked.value) return
  const f = form.value
  if (
    !/^[A-Za-z0-9_-]{1,64}$/.test(f.reportNo) ||
    !/^[0-9]{1,12}(\.[0-9]{1,6})?$/.test(f.quantity) ||
    !/[1-9]/.test(f.quantity) ||
    !f.batchNo.trim() ||
    !f.productionDate
  ) {
    error.value = '请填写报告号、正数完工量、批次和真实生产日期'
    return
  }
  await send('', {
    ...f,
    expiryDate: f.expiryDate || null,
    reason: reason.value.trim(),
    idempotencyKey: crypto.randomUUID(),
  })
}
async function act(r: Report, action: string) {
  if (
    (action === 'cancel' &&
      (!report.value || r.created_by !== props.username)) ||
    (action !== 'cancel' && (!review.value || r.created_by === props.username))
  )
    return
  await send(`/${r.id}/${action}`, {
    version: r.version,
    reason: reason.value.trim(),
    idempotencyKey: crypto.randomUUID(),
  })
}
async function retry() {
  if (!pending.value || busy.value || locked.value || !retryAllowed.value)
    return
  const p = pending.value
  ++generation
  loading.value = false
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const d = await request<Detail>(base + p.path, {
      method: 'POST',
      body: p.body,
    })
    if (!alive) return
    remember()
    data.value = d
    ack.value = false
    reason.value = ''
    if (!p.path)
      form.value = {
        reportNo: '',
        quantity: '',
        batchNo: '',
        dateCode: '',
        productionDate: '',
        expiryDate: '',
      }
    notice.value = '完工记录已保存，批准后由仓管办理实物收货和上架。'
  } catch (e) {
    if (!alive) return
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
      data.value = undefined
      await load()
    } else
      error.value = `结果待核对：${(e as Error).message}。请保留原请求重试。`
  } finally {
    if (alive) busy.value = false
  }
}
onMounted(async () => {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (raw) {
      const p = JSON.parse(raw) as Pending
      if (
        typeof p.path !== 'string' ||
        !/^(|\/[1-9][0-9]*\/(approve|reject|cancel))$/.test(p.path) ||
        typeof JSON.parse(p.body).idempotencyKey !== 'string'
      )
        throw Error('invalid')
      pending.value = p
      error.value = '有结果待核对的完工请求，请原样重试'
    }
  } catch {
    locked.value = true
    error.value = '重试凭据无法读取，停止新操作，请核对原记录'
  }
  await load()
})
onBeforeUnmount(() => {
  alive = false
  ++generation
})
</script>
<template>
  <section class="completion-panel" aria-label="工单完工">
    <div class="completion-heading">
      <h3>完工登记与复核</h3>
      <button :disabled="busy || loading" @click="load">刷新完工记录</button>
    </div>
    <p class="muted">
      登记实际产量，由另一人复核。实物收货后待检，合格并上架才增加可用库存。
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <button
      v-if="pending"
      :disabled="busy || locked || !retryAllowed"
      @click="retry"
    >
      原样重试完工请求
    </button>
    <template v-if="data">
      <p>
        授权 {{ data.authorized }} · 待复核 {{ data.pending }} · 已批准
        {{ data.approved }} · 可登记 {{ data.remaining }} {{ data.unit }}
      </p>
      <form
        v-if="report && data.orderStatus === 'APPROVED'"
        @submit.prevent="save"
      >
        <fieldset :disabled="blocked" class="completion-fields">
          <label
            >报告号<input
              v-model="form.reportNo"
              maxlength="64"
              placeholder="稳定的现场报告编号"
              required
          /></label>
          <label
            >本次完工量<input
              v-model="form.quantity"
              inputmode="decimal"
              required
          /></label>
          <label
            >批次<input v-model="form.batchNo" maxlength="64" required
          /></label>
          <label
            >生产日期<input v-model="form.productionDate" type="date" required
          /></label>
          <label
            >Date Code<input v-model="form.dateCode" maxlength="32"
          /></label>
          <label>有效期<input v-model="form.expiryDate" type="date" /></label>
        </fieldset>
        <button :disabled="blocked || !ack || !reason.trim()">
          提交完工复核
        </button>
      </form>
      <div v-if="report || review" class="completion-reason">
        <label
          >登记或处理依据<input
            v-model="reason"
            maxlength="500"
            :disabled="blocked"
            placeholder="填写实际完工依据或复核意见"
        /></label>
        <label
          ><input
            v-model="ack"
            type="checkbox"
            :disabled="blocked"
          />已核对报告内容及实际数量</label
        >
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>报告 / 批次</th>
              <th>数量 / 日期</th>
              <th>复核</th>
              <th>实物进度</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in data.reports" :key="r.id">
              <td>
                {{ r.report_no }}<br />{{ r.batch_no }}
                <details>
                  <summary>登记依据</summary>
                  {{ r.reason }}<br />Date Code：{{ r.date_code || '无'
                  }}<br />有效期：{{ r.expiry_date || '无' }}
                </details>
              </td>
              <td>
                {{ r.quantity }} {{ data.unit }}<br />{{ r.production_date }}
              </td>
              <td>
                {{ labels[r.status] }}<br />登记：{{ r.created_by
                }}<template v-if="r.reviewed_by"
                  ><br />处理：{{ r.reviewed_by }}<br />{{
                    r.review_reason
                  }}</template
                >
              </td>
              <td>
                <template v-if="r.receipt"
                  >FG-{{ r.receipt.id }} ·
                  {{
                    r.receipt.status === 'REJECTED'
                      ? '不合格隔离'
                      : labels[r.receipt.status]
                  }}<br />{{ r.receipt.quality_reason }}</template
                ><template v-else>未授权入库</template>
              </td>
              <td>
                <div class="completion-actions">
                  <template v-if="r.status === 'SUBMITTED'">
                    <button
                      v-if="review && r.created_by !== username"
                      :disabled="blocked || !ack || !reason.trim()"
                      @click="act(r, 'approve')"
                    >
                      批准完工
                    </button>
                    <button
                      v-if="review && r.created_by !== username"
                      :disabled="blocked || !ack || !reason.trim()"
                      @click="act(r, 'reject')"
                    >
                      驳回报告
                    </button>
                    <button
                      v-if="report && r.created_by === username"
                      :disabled="blocked || !ack || !reason.trim()"
                      @click="act(r, 'cancel')"
                    >
                      撤销登记
                    </button>
                  </template>
                  <RouterLink
                    v-if="
                      r.receipt && authorities.includes('wms:finished:read')
                    "
                    :to="`/finished-goods?warehouseId=${r.receipt.warehouse_id}`"
                    >办理成品入库</RouterLink
                  >
                </div>
              </td>
            </tr>
            <tr v-if="!data.reports.length">
              <td colspan="5">暂无完工报告</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted">
        不合格品保持隔离待处理；本页不办理返工、报废或工单结案。
      </p>
    </template>
  </section>
</template>
<style scoped>
.completion-panel {
  border-top: 1px solid var(--border, #ddd);
  padding-top: 1rem;
  margin-top: 1rem;
}
.completion-heading,
.completion-actions {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  flex-wrap: wrap;
}
.completion-heading {
  justify-content: space-between;
}
.completion-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.7rem;
  border: 0;
  padding: 0;
  margin: 1rem 0;
}
.completion-fields label,
.completion-reason > label:first-child {
  display: grid;
  gap: 0.3rem;
}
.completion-fields input,
.completion-reason input:not([type='checkbox']) {
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
}
.completion-reason {
  display: grid;
  gap: 0.7rem;
  margin: 1rem 0;
}
.table-scroll {
  overflow: auto;
}
td {
  vertical-align: top;
}
</style>
