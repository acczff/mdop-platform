<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request, type Page, type Warehouse } from '../api'

const props = defineProps<{
  orderId: number
  authorities: string[]
  username: string
}>()
interface MaterialLine {
  materialId: number
  code: string
  name: string
  unit: string
  required: string
  available: string
  outstanding: string
  gap: string
  suggested: string
  activePurchase: boolean
  output: string
  base: string
  componentQuantity: string
  roundingNumerator: string
  roundingDenominator: string
}
interface Purchase {
  id: number
  document_no: string
  status: string
  quantity: string
  putaway: string
  returned: string
  outstanding: string
}
interface Plan {
  orderStatus: string
  orderVersion: number
  planVersion: number
  plan: {
    warehouse_id: number
    snapshot: string
    created_at: string
    created_by: string
  } | null
  lines?: {
    id: number
    material_id: number
    purchase_request_id: number | null
  }[]
  purchases: Purchase[]
  history: {
    id: number
    version: number
    created_at: string
    created_by: string
  }[]
}
interface Pending {
  action: 'calculate' | 'confirm'
  body: string
}
const base = `/api/v1/manufacturing/orders/${props.orderId}/materials`
const storageKey = `mdop-material-plan-pending:${props.username}:${props.orderId}`
const opened = ref(false),
  busy = ref(false),
  loading = ref(false),
  locked = ref(false)
const error = ref(''),
  notice = ref(''),
  reason = ref(''),
  warehouse = ref(0)
const data = ref<Plan>(),
  warehouses = ref<Warehouse[]>([]),
  pending = ref<Pending>(),
  selection = ref<MaterialLine>()
const write = computed(() => props.authorities.includes('manufacturing:write'))
const blocked = computed(
  () => busy.value || loading.value || locked.value || !!pending.value,
)
const lines = computed<MaterialLine[]>(() => {
  if (!data.value?.plan) return []
  try {
    return JSON.parse(data.value.plan.snapshot).lines
  } catch {
    return []
  }
})
const fmt = (v: string) => v.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '')
const positive = (v: string) => /[1-9]/.test(v)
const labels: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CONVERTED: '已转订单',
  CANCELLED: '已取消',
}
let generation = 0
function remember(p?: Pending) {
  if (p) sessionStorage.setItem(storageKey, JSON.stringify(p))
  else sessionStorage.removeItem(storageKey)
  pending.value = p
}
function accept(plan: Plan) {
  if (plan.plan) {
    const snapshot = JSON.parse(plan.plan.snapshot)
    if (!Array.isArray(snapshot.lines))
      throw Error('材料快照无法读取，请核对原记录')
    warehouse.value = plan.plan.warehouse_id
  }
  data.value = plan
}
async function load() {
  if (busy.value || locked.value) return
  opened.value = true
  loading.value = true
  error.value = ''
  data.value = undefined
  selection.value = undefined
  const token = ++generation
  try {
    const plan = await request<Plan>(base)
    const all: Warehouse[] = []
    for (let page = 1; ; page++) {
      const batch = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${page}&size=100`,
      )
      all.push(
        ...batch.items.filter(
          (w) =>
            w.purpose === 'RAW_MATERIAL' &&
            w.status === 'ENABLED' &&
            props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (page >= batch.totalPages) break
    }
    if (token !== generation) return
    warehouses.value = all
    accept(plan)
    if (!warehouse.value) warehouse.value = all[0]?.id || 0
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}
async function save(action: Pending['action']) {
  if (
    blocked.value ||
    !write.value ||
    !data.value ||
    data.value.orderStatus !== 'APPROVED'
  )
    return
  if (!reason.value.trim()) {
    error.value = '请填写核对说明'
    return
  }
  if (!warehouse.value) {
    error.value = '请选择有权限的原材料仓'
    return
  }
  const body: Record<string, unknown> = {
    idempotencyKey: crypto.randomUUID(),
    planVersion: data.value.planVersion,
    reason: reason.value.trim(),
  }
  if (action === 'calculate') {
    body.warehouseId = warehouse.value
    body.orderVersion = data.value.orderVersion
  } else {
    const line = data.value.lines?.find(
      (l) => l.material_id === selection.value?.materialId,
    )
    if (
      !line ||
      line.purchase_request_id ||
      !selection.value ||
      !positive(selection.value.suggested)
    )
      return
    body.lineId = line.id
  }
  try {
    remember({ action, body: JSON.stringify(body) })
  } catch {
    error.value = '无法保存重试凭据，本次请求未发送'
    return
  }
  await retry()
}
async function retry() {
  if (busy.value || locked.value || !pending.value || !write.value) return
  busy.value = true
  loading.value = false
  error.value = ''
  notice.value = ''
  const token = ++generation,
    p = pending.value
  try {
    const plan = await request<Plan>(`${base}/${p.action}`, {
      method: 'POST',
      body: p.body,
    })
    remember()
    if (token !== generation) return
    accept(plan)
    selection.value = undefined
    reason.value = ''
    notice.value =
      p.action === 'confirm'
        ? '已生成采购需求草稿，请交采购人员提交审核。其他建议需重新计算后确认。'
        : '计算已保存，请核对快照后确认采购建议。'
  } catch (e) {
    if (token !== generation) return
    data.value = undefined
    selection.value = undefined
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
      error.value = `结果待核对：${(e as Error).message}。请原样重试，不要另建采购需求。`
  } finally {
    if (token === generation) busy.value = false
  }
}
onMounted(() => {
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (raw) {
      const p = JSON.parse(raw) as Pending
      if (
        !['calculate', 'confirm'].includes(p.action) ||
        typeof p.body !== 'string'
      )
        throw Error('invalid')
      const body = JSON.parse(p.body)
      if (
        typeof body.idempotencyKey !== 'string' ||
        typeof body.reason !== 'string' ||
        !Number.isInteger(body.planVersion)
      )
        throw Error('invalid')
      pending.value = p
      opened.value = true
      error.value = '存在结果待核对的请求，请原样重试。'
    }
  } catch {
    locked.value = true
    opened.value = true
    error.value = '重试凭据无法读取，请联系管理员核对，本页停止新操作。'
  }
})
onBeforeUnmount(() => {
  generation++
})
</script>

<template>
  <section class="material-plan" aria-label="材料需求与采购建议">
    <button v-if="!opened" @click="load">材料需求与采购建议</button>
    <template v-else>
      <div class="plan-heading">
        <h4>材料需求与采购建议</h4>
        <button :disabled="busy || loading || locked" @click="load">
          刷新材料需求
        </button>
      </div>
      <p class="muted">
        按工单 BOM 计算，只作库存参考，不预占库存。确认后生成采购需求草稿。
      </p>
      <p v-if="loading" role="status">正在读取…</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <button v-if="pending && write" :disabled="busy || locked" @click="retry">
        原样重试材料请求
      </button>
      <template v-if="data">
        <div class="plan-controls">
          <label
            >供料原材料仓<select
              v-model="warehouse"
              :disabled="blocked || !!data.plan || !write"
            >
              <option :value="0">请选择</option>
              <option v-for="w in warehouses" :key="w.id" :value="w.id">
                {{ w.code }} · {{ w.name }}
              </option>
              <option
                v-if="data.plan && !warehouses.some((w) => w.id === warehouse)"
                :value="warehouse"
              >
                原供料仓 #{{ warehouse }}（不可新用）
              </option>
            </select></label
          >
          <label v-if="write && data.orderStatus === 'APPROVED'"
            >核对说明<input
              v-model="reason"
              maxlength="500"
              :disabled="blocked"
              placeholder="填写计算或采购确认依据"
          /></label>
          <button
            v-if="write && data.orderStatus === 'APPROVED'"
            :disabled="blocked || !warehouse"
            @click="save('calculate')"
          >
            {{ data.plan ? '重新计算' : '计算材料需求' }}
          </button>
        </div>
        <p v-if="!data.plan" class="muted">
          尚未计算材料需求。首次计算后固定供料仓。
        </p>
        <template v-else>
          <p class="muted">
            计算版本 {{ data.planVersion }} · {{ data.plan.created_by }} ·
            {{ data.plan.created_at }}。以下为计算时快照，确认时会再次校验。
          </p>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>材料 / 单位</th>
                  <th>理论需求</th>
                  <th>可用库存</th>
                  <th>关联采购未入库</th>
                  <th>缺口</th>
                  <th>采购建议</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="l in lines" :key="l.materialId">
                  <td>
                    {{ l.code }}<small>{{ l.name }} · {{ l.unit }}</small>
                  </td>
                  <td>{{ fmt(l.required) }}</td>
                  <td>{{ fmt(l.available) }}</td>
                  <td>{{ fmt(l.outstanding) }}</td>
                  <td>{{ fmt(l.gap) }}</td>
                  <td>
                    <template
                      v-if="
                        data.lines?.find((x) => x.material_id === l.materialId)
                          ?.purchase_request_id
                      "
                      >已生成草稿</template
                    ><template v-else-if="l.activePurchase">{{
                      positive(l.gap) ? '待核对原采购' : '已有采购覆盖'
                    }}</template
                    ><template v-else-if="positive(l.suggested)"
                      >{{ fmt(l.suggested) }}
                      <button
                        v-if="write && data.orderStatus === 'APPROVED'"
                        :disabled="blocked"
                        @click="selection = l"
                      >
                        核对建议
                      </button></template
                    ><template v-else>无需采购</template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div
            v-if="selection"
            class="confirmation"
            role="group"
            aria-label="确认采购建议"
          >
            <strong
              >{{ selection.code }} · 采购 {{ fmt(selection.suggested) }}
              {{ selection.unit }}</strong
            >
            <p>
              整条建议转采购需求草稿，仍需采购人员提交并由另一人审核。每种材料仅保留一条有效采购需求。
            </p>
            <button :disabled="blocked" @click="save('confirm')">
              确认生成采购需求
            </button>
            <button :disabled="blocked" @click="selection = undefined">
              返回核对
            </button>
          </div>
          <details>
            <summary>计算依据与数量口径</summary>
            <p v-for="l in lines" :key="l.materialId">
              {{ l.code }}：{{ fmt(l.output) }} ×
              {{ fmt(l.componentQuantity) }} ÷ {{ fmt(l.base) }} =
              {{ fmt(l.required) }} {{ l.unit }}（向上保留六位小数；增量
              {{ l.roundingNumerator }} / {{ l.roundingDenominator }}）。
            </p>
            <p>
              缺口 = 理论需求 − 当前可用库存 −
              本工单关联采购未入库参考，最小为零。已入库与已退货数量从采购参考中扣除。草稿也是待办意向，不代表采购已批准或保证到货；已有有效采购时只提示差额，不追加采购。
            </p>
          </details>
        </template>
        <div v-if="data.purchases.length">
          <h4>关联采购需求</h4>
          <p v-for="p in data.purchases" :key="p.id">
            {{ p.document_no }} · {{ labels[p.status] || p.status }} · 需求
            {{ fmt(p.quantity) }} / 已入库 {{ fmt(p.putaway) }} / 已退货
            {{ fmt(p.returned) }} / 未入库参考 {{ fmt(p.outstanding) }}
          </p>
          <a v-if="authorities.includes('purchasing:read')" href="/purchasing"
            >进入采购管理，按需求单号核对</a
          >
          <p v-else class="muted">请将需求单号交给采购人员继续办理。</p>
        </div>
        <details v-if="data.history.length">
          <summary>计算记录（{{ data.history.length }}，最多 1000 条）</summary>
          <p v-for="h in data.history" :key="h.id">
            版本 {{ h.version }} · {{ h.created_by }} · {{ h.created_at }}
          </p>
        </details>
      </template>
    </template>
  </section>
</template>

<style scoped>
.material-plan {
  border-top: 1px solid var(--line, #e4e4ed);
  margin-top: 18px;
  padding-top: 16px;
}
.plan-heading,
.plan-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.plan-heading {
  justify-content: space-between;
}
.plan-controls {
  align-items: end;
  margin: 16px 0;
}
.plan-controls label {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 180px;
}
h4 {
  margin: 0;
}
small {
  display: block;
  color: #777;
  margin-top: 4px;
}
.table-scroll {
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
}
th,
td {
  text-align: left;
  padding: 12px 8px;
  border-bottom: 1px solid #e4e4ed;
  white-space: nowrap;
}
th {
  font-size: 12px;
  color: #676779;
}
.muted {
  color: #777;
  font-size: 13px;
}
.error {
  color: #a02632;
}
.confirmation {
  padding: 16px;
  background: #f4f1fc;
  margin-top: 14px;
  border-radius: 8px;
}
details {
  margin-top: 16px;
}
p {
  line-height: 1.6;
}
input,
select {
  min-height: 38px;
}
</style>
