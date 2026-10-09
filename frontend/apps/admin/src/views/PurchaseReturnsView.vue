<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request, type Warehouse, type Page } from '../api'
const props = defineProps<{ authorities: string[]; username: string }>()
const allowed = (permission: string) =>
  props.authorities.includes('ROLE_ADMIN') ||
  props.authorities.includes(permission)
interface Candidate {
  receipt_item_id: number
  receipt_id: number
  receipt_no: string
  material_code: string
  material_name: string
  supplier_name: string
  location_name: string
  batch_no: string
  rejected_qty: string
  committed_qty: string
  remaining_qty: string
}
interface ReturnRecord {
  id: number
  receiptId: number
  receiptItemId: number
  quantity: string
  reason: string
  status: string
  version: number
  requestedBy: string
  decidedBy: string | null
  decisionReason: string | null
  handoverNo: string | null
  confirmedBy: string | null
}
interface Audit {
  action: string
  actor: string
  detail: string
  occurred_at: string
}
const names: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '待实际退货',
  REJECTED: '已驳回',
  CANCELLED: '已撤销',
  RETURNED: '已退货',
}
const warehouses = ref<Warehouse[]>([]),
  warehouseId = ref(0),
  candidates = ref<Candidate[]>([]),
  records = ref<ReturnRecord[]>([])
const selected = ref<ReturnRecord>(),
  history = ref<Audit[]>([])
const busy = ref(false),
  error = ref(''),
  message = ref('')
const itemId = ref(0),
  quantity = ref(''),
  reason = ref(''),
  createKey = ref(crypto.randomUUID())
const decision = ref('APPROVE'),
  decisionReason = ref(''),
  decisionKey = ref(crypto.randomUUID())
const handoverNo = ref(''),
  handedOver = ref(false),
  confirmKey = ref(crypto.randomUUID())
const cancelReason = ref(''),
  cancelKey = ref(crypto.randomUUID())
const candidate = computed(() =>
  candidates.value.find((c) => c.receipt_item_id === itemId.value),
)
const canReview = computed(
  () =>
    selected.value?.status === 'PENDING' &&
    selected.value.requestedBy !== props.username &&
    allowed('wms:return:approve'),
)
const canCancel = computed(
  () =>
    selected.value &&
    ['PENDING', 'APPROVED'].includes(selected.value.status) &&
    selected.value.requestedBy === props.username &&
    allowed('wms:return:create'),
)
async function load() {
  busy.value = true
  error.value = ''
  selected.value = undefined
  history.value = []
  records.value = []
  candidates.value = []
  itemId.value = 0
  try {
    if (!warehouseId.value) return
    const [list, sources] = await Promise.all([
      request<ReturnRecord[]>(
        `/api/v1/wms/purchase-returns?warehouseId=${warehouseId.value}`,
      ),
      request<Candidate[]>(
        `/api/v1/wms/purchase-returns/candidates?warehouseId=${warehouseId.value}`,
      ),
    ])
    records.value = list
    candidates.value = sources
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function select(record: ReturnRecord) {
  busy.value = true
  error.value = ''
  message.value = ''
  selected.value = undefined
  history.value = []
  try {
    history.value = await request<Audit[]>(
      `/api/v1/wms/purchase-returns/${record.id}/history`,
    )
    selected.value = record
    decision.value = 'APPROVE'
    decisionReason.value = ''
    decisionKey.value = crypto.randomUUID()
    handoverNo.value = ''
    handedOver.value = false
    confirmKey.value = crypto.randomUUID()
    cancelReason.value = ''
    cancelKey.value = crypto.randomUUID()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function create() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await request('/api/v1/wms/purchase-returns', {
      method: 'POST',
      body: JSON.stringify({
        idempotencyKey: createKey.value,
        receiptItemId: itemId.value,
        quantity: quantity.value,
        reason: reason.value,
      }),
    })
    createKey.value = crypto.randomUUID()
    quantity.value = ''
    reason.value = ''
    await load()
    message.value =
      '申请已登记并占用可退额度，库存未扣减。请由另一位审批人审核。'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function act(action: 'decision' | 'confirm' | 'cancel') {
  if (!selected.value || (action === 'confirm' && !handedOver.value)) return
  busy.value = true
  error.value = ''
  message.value = ''
  const data =
    action === 'decision'
      ? {
          idempotencyKey: decisionKey.value,
          decision: decision.value,
          reason: decisionReason.value,
        }
      : action === 'confirm'
        ? { idempotencyKey: confirmKey.value, handoverNo: handoverNo.value }
        : { idempotencyKey: cancelKey.value, reason: cancelReason.value }
  try {
    await request(
      `/api/v1/wms/purchase-returns/${selected.value.id}/${action}`,
      {
        method: 'POST',
        body: JSON.stringify({ ...data, version: selected.value.version }),
      },
    )
    const outcome =
      action === 'confirm'
        ? '实际退货已确认，不合格库存已扣减，交接记录已保存。'
        : action === 'cancel'
          ? '申请已撤销，可退额度已释放。'
          : decision.value === 'APPROVE'
            ? '已批准。库存尚未扣减，等待仓管确认实际退货。'
            : '已驳回，可退额度已释放。'
    await load()
    message.value = outcome
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  try {
    const page = await request<Page<Warehouse>>(
      '/api/master-data/warehouses?size=100',
    )
    warehouses.value = page.items.filter(
      (w) =>
        props.authorities.includes('ROLE_ADMIN') ||
        props.authorities.includes(`wms:warehouse:${w.id}`),
    )
    warehouseId.value = warehouses.value[0]?.id || 0
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
})
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>不合格品退货</h1>
        <p class="muted">
          申请 → 跨人员审批 → 确认实际退货。最后一步才扣减库存。
        </p>
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
    <section v-if="allowed('wms:return:create')" class="panel">
      <div class="panel-body">
        <h2>申请采购退货</h2>
        <p class="hint">
          只退原供应商的不合格数量，可分批申请。待审批、已批准和已退货数量均占用可退额度。退款、财务与不合格品移库不在此页面处理。
        </p>
        <form @submit.prevent="create">
          <label
            >来源收货明细<select v-model="itemId" required :disabled="busy">
              <option :value="0" disabled>请选择不合格明细</option>
              <option
                v-for="c in candidates"
                :key="c.receipt_item_id"
                :value="c.receipt_item_id"
              >
                {{ c.receipt_no }} / {{ c.material_code }} ·
                {{ c.material_name }} / 可申请 {{ c.remaining_qty }}
              </option>
            </select></label
          >
          <p v-if="candidate" class="hint">
            供应商：{{ candidate.supplier_name }} · 库位：{{
              candidate.location_name
            }}
            · 批次：{{ candidate.batch_no || '无' }}<br />不合格
            {{ candidate.rejected_qty }}，已占额度（含已退）{{
              candidate.committed_qty
            }}，可申请 {{ candidate.remaining_qty }}
          </p>
          <label
            >本次退货数量<input
              v-model="quantity"
              type="number"
              required
              min="0.000001"
              step="0.000001"
              :max="candidate?.remaining_qty"
              :disabled="busy"
          /></label>
          <label
            >退货原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
            />
          </label>
          <button class="primary" :disabled="busy || !itemId">
            提交退货申请
          </button>
        </form>
      </div>
    </section>
    <section class="panel">
      <div class="panel-body">
        <h2>退货记录</h2>
        <p class="hint">最近 100 条申请。审批通过并不代表物料已经退回。</p>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>退货号</th>
              <th>收货明细</th>
              <th>数量</th>
              <th>状态</th>
              <th>申请人</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in records" :key="r.id">
              <td>RT-{{ r.id }}</td>
              <td>
                {{
                  candidates.find((c) => c.receipt_item_id === r.receiptItemId)
                    ?.receipt_no || r.receiptId
                }}
                /
                {{
                  candidates.find((c) => c.receipt_item_id === r.receiptItemId)
                    ?.material_name || r.receiptItemId
                }}
              </td>
              <td>{{ r.quantity }}</td>
              <td>{{ names[r.status] }}</td>
              <td>{{ r.requestedBy }}</td>
              <td>
                <button :disabled="busy" @click="select(r)">查看 / 处理</button>
              </td>
            </tr>
            <tr v-if="!records.length">
              <td colspan="6" class="empty">暂无退货申请</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-if="selected" class="panel">
      <div class="panel-body">
        <h2>RT-{{ selected.id }} · {{ names[selected.status] }}</h2>
        <p>
          本次数量 {{ selected.quantity }} · 申请人 {{ selected.requestedBy }}
        </p>
        <p>原因：{{ selected.reason }}</p>
        <p v-if="selected.decisionReason">
          审批意见：{{ selected.decisionReason }}（{{ selected.decidedBy }}）
        </p>
        <p v-if="selected.handoverNo">
          实际退货凭证：{{ selected.handoverNo }}（{{ selected.confirmedBy }}）
        </p>
        <p
          v-if="
            selected.status === 'PENDING' && selected.requestedBy === username
          "
          class="hint"
        >
          申请人不能审批自己的申请，请由另一位审批人处理。
        </p>
        <form v-if="canReview" @submit.prevent="act('decision')">
          <h3>审批退货</h3>
          <label
            >处理结果<select v-model="decision" :disabled="busy">
              <option value="APPROVE">批准</option>
              <option value="REJECT">驳回</option>
            </select></label
          ><label
            >审批意见<textarea
              v-model="decisionReason"
              required
              maxlength="500"
              :disabled="busy"
            /></label
          ><button :disabled="busy">确认审批</button>
        </form>
        <form
          v-if="selected.status === 'APPROVED' && allowed('wms:return:confirm')"
          @submit.prevent="act('confirm')"
        >
          <h3>确认实际退货</h3>
          <p class="hint">
            确认后扣减上述全部退货数量，并生成不可变流水。请在实物已交接后操作。
          </p>
          <label
            >交接凭证 / 运单号<input
              v-model="handoverNo"
              required
              maxlength="64"
              :disabled="busy" /></label
          ><label
            ><input
              v-model="handedOver"
              type="checkbox"
              :disabled="busy"
            />我确认本申请数量已实际交接退回供应商</label
          ><button class="primary" :disabled="busy || !handedOver">
            确认实际退货并扣库存
          </button>
        </form>
        <form v-if="canCancel" @submit.prevent="act('cancel')">
          <h3>撤销未退货申请</h3>
          <label
            >撤销原因<textarea
              v-model="cancelReason"
              required
              maxlength="500"
              :disabled="busy"
            /></label
          ><button :disabled="busy">撤销申请</button>
        </form>
        <h3>操作记录</h3>
        <ul>
          <li v-for="(event, index) in history" :key="index">
            {{ event.occurred_at }} · {{ event.actor }} ·
            {{ names[event.action] || '已申请' }} · {{ event.detail }}
          </li>
        </ul>
      </div>
    </section>
  </main>
</template>
