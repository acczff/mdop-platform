<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { request, type Warehouse, type Page } from '../api'
import type { Arrival, ArrivalDetail, Receipt } from '../receiving'
const props = defineProps<{ authorities: string[]; username: string }>()
const allowed = (permission: string) =>
  props.authorities.includes('ROLE_ADMIN') ||
  props.authorities.includes(permission)
const canCreate = computed(() => allowed('wms:correction:create'))
const canApprove = computed(() => allowed('wms:correction:approve'))
interface Case {
  id: number
  kind: string
  arrivalId: number
  receiptId: number | null
  differenceType: string | null
  quantity: string | null
  reason: string
  status: string
  version: number
  requestedBy: string
  decidedBy: string | null
  decisionReason: string | null
}
const warehouses = ref<Warehouse[]>([]),
  warehouseId = ref(0),
  arrivals = ref<Arrival[]>([]),
  arrivalId = ref(0)
const detail = ref<ArrivalDetail>(),
  receipts = ref<Receipt[]>([]),
  cases = ref<Case[]>([])
const busy = ref(false),
  error = ref(''),
  message = ref(''),
  selected = ref<Case>()
const history = ref<
  { action: string; actor: string; detail: string; occurred_at: string }[]
>([])
const form = reactive({
  kind: 'DIFFERENCE',
  type: 'SHORT',
  arrivalItemId: 0,
  receiptId: 0,
  observedMaterial: '',
  quantity: '1',
  reason: '',
})
const decision = reactive({ action: 'APPROVE', reason: '' })
const requestKey = ref(crypto.randomUUID()),
  decisionKey = ref(crypto.randomUUID())
const statuses: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}
const types: Record<string, string> = {
  SHORT: '短收',
  OVER: '超收',
  DAMAGED: '破损',
  WRONG_MATERIAL: '错料',
}
const eligible = computed(() =>
  receipts.value.filter(
    (r) => r.status === 'SUBMITTED' && r.correctionStatus !== 'REVERSED',
  ),
)
async function initialize() {
  try {
    const result = await request<Page<Warehouse>>(
      '/api/master-data/warehouses?size=100',
    )
    warehouses.value = result.items.filter(
      (w) =>
        props.authorities.includes('ROLE_ADMIN') ||
        props.authorities.includes(`wms:warehouse:${w.id}`),
    )
    warehouseId.value = warehouses.value[0]?.id || 0
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function load() {
  busy.value = true
  error.value = ''
  selected.value = undefined
  history.value = []
  cases.value = []
  arrivals.value = []
  detail.value = undefined
  receipts.value = []
  arrivalId.value = 0
  try {
    if (!warehouseId.value) {
      cases.value = []
      arrivals.value = []
      return
    }
    const [list, notices] = await Promise.all([
      request<Case[]>(
        `/api/v1/wms/corrections?warehouseId=${warehouseId.value}`,
      ),
      request<Arrival[]>(
        `/api/v1/wms/arrival-notices?warehouseId=${warehouseId.value}`,
      ),
    ])
    cases.value = list
    arrivals.value = notices
    arrivalId.value = notices[0]?.id || 0
    await loadArrival()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function loadArrival() {
  detail.value = undefined
  receipts.value = []
  form.arrivalItemId = 0
  form.receiptId = 0
  if (!arrivalId.value) return
  busy.value = true
  try {
    const [notice, records] = await Promise.all([
      request<ArrivalDetail>(`/api/v1/wms/arrival-notices/${arrivalId.value}`),
      request<Receipt[]>(
        `/api/v1/wms/arrival-notices/${arrivalId.value}/receipts`,
      ),
    ])
    detail.value = notice
    receipts.value = records
    form.arrivalItemId = notice.items[0]?.id || 0
    form.receiptId = eligible.value[0]?.id || 0
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function submit() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const reversal = form.kind === 'REVERSAL'
    await request(
      `/api/v1/wms/corrections/${reversal ? 'reversals' : 'differences'}`,
      {
        method: 'POST',
        body: JSON.stringify(
          reversal
            ? {
                idempotencyKey: requestKey.value,
                receiptId: form.receiptId,
                reason: form.reason,
              }
            : {
                idempotencyKey: requestKey.value,
                arrivalId: arrivalId.value,
                arrivalItemId: form.arrivalItemId || null,
                type: form.type,
                observedMaterial: form.observedMaterial,
                quantity: form.quantity,
                reason: form.reason,
              },
        ),
      },
    )
    requestKey.value = crypto.randomUUID()
    form.reason = ''
    message.value = '申请已登记，等待其他有权限人员审批；库存尚未改变。'
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function inspect(item: Case) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  selected.value = undefined
  decision.reason = ''
  decision.action = 'APPROVE'
  decisionKey.value = crypto.randomUUID()
  history.value = []
  try {
    history.value = await request<typeof history.value>(
      `/api/v1/wms/corrections/${item.id}/history`,
    )
    selected.value = item
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function decide() {
  if (!selected.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await request(`/api/v1/wms/corrections/${selected.value.id}/decision`, {
      method: 'POST',
      body: JSON.stringify({
        idempotencyKey: decisionKey.value,
        version: selected.value.version,
        decision: decision.action,
        reason: decision.reason,
      }),
    })
    message.value =
      decision.action === 'REJECT'
        ? '申请已驳回，库存未改变。'
        : selected.value.kind === 'REVERSAL'
          ? '冲正已通过，已生成反向流水和补偿消息。'
          : '差异处理意见已确认，库存未改变。'
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(initialize)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>收货差异与冲正</h1>
        <p class="muted">保留原始记录，通过审批纠正错误收货。</p>
      </div>
      <button :disabled="busy" @click="load">刷新</button>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <label
      >仓库<select v-model="warehouseId" :disabled="busy" @change="load">
        <option v-for="w in warehouses" :key="w.id" :value="w.id">
          {{ w.name }}
        </option>
      </select></label
    >
    <p v-if="!warehouses.length" class="hint">
      暂无已授权仓库，请联系管理员配置仓库权限。
    </p>
    <section v-if="canCreate && warehouseId" class="panel">
      <h2>登记申请</h2>
      <p class="hint">
        超收、破损和错料不得直接进入正常库存；短收不自动关闭剩余到货。差异审批仅确认处理意见，不改变采购数量或质量状态。冲正按整张收货单执行。
      </p>
      <form @submit.prevent="submit">
        <fieldset :disabled="busy">
          <label
            >申请类型<select v-model="form.kind">
              <option value="DIFFERENCE">差异登记</option>
              <option value="REVERSAL">收货冲正</option>
            </select></label
          >
          <label
            >到货通知<select v-model="arrivalId" required @change="loadArrival">
              <option v-for="a in arrivals" :key="a.id" :value="a.id">
                {{ a.externalNoticeNo }}
              </option>
            </select></label
          >
          <template v-if="form.kind === 'DIFFERENCE'">
            <label
              >差异类型<select v-model="form.type">
                <option v-for="(name, type) in types" :key="type" :value="type">
                  {{ name }}
                </option>
              </select></label
            >
            <label
              >通知物料<select v-model="form.arrivalItemId">
                <option v-if="form.type === 'WRONG_MATERIAL'" :value="0">
                  通知外物料
                </option>
                <option
                  v-for="item in detail?.items"
                  :key="item.id"
                  :value="item.id"
                >
                  {{ item.materialCode }} · {{ item.materialName }}
                </option>
              </select></label
            >
            <label v-if="form.type === 'WRONG_MATERIAL'"
              >实到物料<input
                v-model="form.observedMaterial"
                required
                maxlength="64"
            /></label>
            <label
              >差异数量<input
                v-model="form.quantity"
                required
                inputmode="decimal"
                pattern="\d+(\.\d{1,6})?"
            /></label>
          </template>
          <label v-else
            >收货单<select v-model="form.receiptId" required>
              <option v-for="r in eligible" :key="r.id" :value="r.id">
                {{ r.receiptNo }}
              </option>
            </select></label
          >
          <label
            >申请原因<textarea
              v-model="form.reason"
              required
              maxlength="500"
            ></textarea>
          </label>
          <button
            class="primary"
            :disabled="
              !arrivalId || (form.kind === 'REVERSAL' && !form.receiptId)
            "
          >
            提交申请
          </button>
        </fieldset>
      </form>
    </section>
    <section class="panel">
      <h2>申请记录</h2>
      <p class="hint">
        显示当前仓库最近100条申请；审批通过的差异记录不会直接调整库存。
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>编号</th>
              <th>类型</th>
              <th>数量</th>
              <th>原因</th>
              <th>申请人</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in cases" :key="item.id">
              <td>{{ item.id }}</td>
              <td>
                {{
                  item.kind === 'REVERSAL'
                    ? '整单冲正'
                    : types[item.differenceType || '']
                }}
              </td>
              <td>{{ item.quantity || '整单' }}</td>
              <td>{{ item.reason }}</td>
              <td>{{ item.requestedBy }}</td>
              <td>{{ statuses[item.status] }}</td>
              <td>
                <button :disabled="busy" @click="inspect(item)">
                  查看 / 审批
                </button>
              </td>
            </tr>
            <tr v-if="!cases.length">
              <td colspan="7">暂无申请</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-if="selected" class="panel">
      <h2>申请 {{ selected.id }} · {{ statuses[selected.status] }}</h2>
      <p>{{ selected.reason }}</p>
      <p v-if="selected.requestedBy === username" class="hint">
        你是申请人，不能审批自己的申请。
      </p>
      <form
        v-if="
          canApprove &&
          selected.status === 'PENDING' &&
          selected.requestedBy !== username
        "
        @submit.prevent="decide"
      >
        <fieldset :disabled="busy">
          <p v-if="selected.kind === 'REVERSAL'" class="error">
            通过后将扣减此收货单的待检库存，并向 ERP/QMS
            发送补偿消息。已检验或已上架的单据不能直接冲正。
          </p>
          <label
            >审批结果<select v-model="decision.action">
              <option value="APPROVE">通过</option>
              <option value="REJECT">驳回</option>
            </select></label
          >
          <label
            >审批意见<textarea
              v-model="decision.reason"
              required
              maxlength="500"
            ></textarea></label
          ><button class="primary">确认审批</button>
        </fieldset>
      </form>
      <p v-if="selected.decidedBy">
        审批人：{{ selected.decidedBy }} · {{ selected.decisionReason }}
      </p>
      <ul>
        <li v-for="(log, index) in history" :key="index">
          {{ log.occurred_at }} · {{ log.actor }} · {{ log.action }} ·
          {{ log.detail }}
        </li>
      </ul>
    </section>
  </main>
</template>
