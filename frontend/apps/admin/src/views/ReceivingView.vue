<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ApiError, request, type Warehouse, type Page } from '../api'
import { remaining, scaled } from '../quantity'
import {
  type Supplier,
  type Material,
  type Location,
  type Arrival,
  type ArrivalDetail,
  type Receipt,
  type ReceiptDetail,
  type ReceiptLine,
  type Balance,
  type Ledger,
  arrivalStatuses,
} from '../receiving'
const props = defineProps<{ authorities: string[] }>()
const admin = computed(() => props.authorities.includes('ROLE_ADMIN'))
const canDraft = computed(
  () => admin.value || props.authorities.includes('wms:receipt:create'),
)
const canSubmit = computed(
  () => admin.value || props.authorities.includes('wms:receipt:submit'),
)
const warehouses = ref<Warehouse[]>([]),
  suppliers = ref<Supplier[]>([]),
  materials = ref<Material[]>([]),
  locations = ref<Location[]>([])
const warehouseId = ref(0),
  arrivals = ref<Arrival[]>([]),
  balances = ref<Balance[]>([])
const selected = ref<ArrivalDetail | null>(null),
  history = ref<Receipt[]>([]),
  ledger = ref<Ledger[]>([])
const current = ref<ReceiptDetail | null>(null),
  lines = ref<ReceiptLine[]>([])
const creationKey = ref(crypto.randomUUID()),
  editor = ref(false),
  confirmSubmit = ref(false),
  simulator = ref(false),
  simEnabled = ref(false)
const busy = ref(false),
  loading = ref(false),
  error = ref(''),
  formError = ref(''),
  message = ref('')
const notice = reactive({
  externalNoticeNo: '',
  purchaseOrderNo: '',
  supplierId: 0,
  items: [{ materialId: 0, quantity: '1' }],
})
const eligibleLocations = computed(() =>
  locations.value.filter(
    (l) => l.warehouseId === warehouseId.value && l.areaType !== 'STORAGE',
  ),
)
const readonly = computed(() => current.value?.receipt.status === 'SUBMITTED')
async function loadFoundation() {
  clearSelection()
  arrivals.value = []
  balances.value = []
  loading.value = true
  error.value = ''
  try {
    const [w, s, m, l] = await Promise.all([
      request<Page<Warehouse>>('/api/master-data/warehouses?size=100'),
      request<Supplier[]>('/api/master-data/suppliers'),
      request<Material[]>('/api/master-data/materials'),
      request<Location[]>('/api/master-data/locations'),
    ])
    warehouses.value = w.items
    suppliers.value = s
    materials.value = m
    locations.value = l
    warehouseId.value = w.items[0]?.id || 0
    if (admin.value || props.authorities.includes('integration:simulate')) {
      try {
        simEnabled.value = (
          await request<{ enabled: boolean }>(
            '/api/local/erp-arrivals/capabilities',
          )
        ).enabled
      } catch {
        simEnabled.value = false
      }
    }
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
let generation = 0
function clearSelection() {
  selected.value = null
  history.value = []
  current.value = null
  lines.value = []
  ledger.value = []
  editor.value = false
  confirmSubmit.value = false
}
async function load() {
  const currentGeneration = ++generation
  clearSelection()
  arrivals.value = []
  balances.value = []
  if (!warehouseId.value) return
  loading.value = true
  error.value = ''
  try {
    const [a, b] = await Promise.all([
      request<Arrival[]>(
        `/api/v1/wms/arrival-notices?warehouseId=${warehouseId.value}`,
      ),
      request<Balance[]>(
        `/api/v1/wms/inventory?warehouseId=${warehouseId.value}`,
      ),
    ])
    if (currentGeneration === generation) {
      arrivals.value = a
      balances.value = b
    }
  } catch (e) {
    if (currentGeneration === generation) error.value = (e as Error).message
  } finally {
    if (currentGeneration === generation) loading.value = false
  }
}
async function switchWarehouse() {
  selected.value = null
  history.value = []
  arrivals.value = []
  balances.value = []
  await load()
}
async function selectArrival(id: number) {
  clearSelection()
  busy.value = true
  error.value = ''
  try {
    const [detail, receipts] = await Promise.all([
      request<ArrivalDetail>(`/api/v1/wms/arrival-notices/${id}`),
      request<Receipt[]>(`/api/v1/wms/arrival-notices/${id}/receipts`),
    ])
    selected.value = detail
    history.value = receipts
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
function addLine() {
  const source =
    selected.value?.items.find(
      (i) => scaled(i.receivedQty) < scaled(i.noticeQty),
    ) || selected.value?.items[0]
  if (source)
    lines.value.push({
      arrivalItemId: source.id,
      locationId: eligibleLocations.value[0]?.id || 0,
      quantity: '1',
      batchNo: '',
      dateCode: '',
      productionDate: null,
      expiryDate: null,
    })
}
function newDraft() {
  if (busy.value || loading.value || editor.value || simulator.value) return
  current.value = null
  lines.value = []
  addLine()
  creationKey.value = crypto.randomUUID()
  formError.value = ''
  ledger.value = []
  confirmSubmit.value = false
  editor.value = true
}
async function openReceipt(id: number) {
  if (busy.value || loading.value || editor.value || simulator.value) return
  busy.value = true
  error.value = ''
  try {
    const detail = await request<ReceiptDetail>(`/api/v1/wms/receipts/${id}`)
    current.value = detail
    lines.value = detail.items.map((item) => ({ ...item }))
    ledger.value =
      detail.receipt.status === 'SUBMITTED'
        ? await request<Ledger[]>(`/api/v1/wms/receipts/${id}/transactions`)
        : []
    formError.value = ''
    confirmSubmit.value = false
    editor.value = true
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function saveDraft() {
  if (!selected.value) return
  busy.value = true
  formError.value = ''
  try {
    const items = lines.value.map((l) => ({
      arrivalItemId: l.arrivalItemId,
      locationId: l.locationId,
      quantity: l.quantity,
      batchNo: l.batchNo,
      dateCode: l.dateCode,
      productionDate: l.productionDate || null,
      expiryDate: l.expiryDate || null,
    }))
    current.value = await request<ReceiptDetail>(
      current.value
        ? `/api/v1/wms/receipts/${current.value.receipt.id}`
        : `/api/v1/wms/arrival-notices/${selected.value.arrival.id}/receipts`,
      {
        method: current.value ? 'PUT' : 'POST',
        body: JSON.stringify(
          current.value
            ? { version: current.value.receipt.version, items }
            : { idempotencyKey: creationKey.value, items },
        ),
      },
    )
    lines.value = current.value.items.map((item) => ({ ...item }))
    message.value = '草稿已保存，库存尚未变化'
    editor.value = false
    await selectArrival(selected.value.arrival.id)
  } catch (e) {
    formError.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function submit() {
  if (!current.value) return
  busy.value = true
  formError.value = ''
  try {
    const id = current.value.receipt.id
    current.value = await request<ReceiptDetail>(
      `/api/v1/wms/receipts/${id}/submit`,
      {
        method: 'POST',
        body: JSON.stringify({
          version: current.value.receipt.version,
          idempotencyKey: `receipt-submit-${id}`,
        }),
      },
    )
    message.value = '收货已提交，待检库存与流水已生成；可用库存仍为 0。'
    editor.value = false
    if (selected.value) await selectArrival(selected.value.arrival.id)
    await load()
  } catch (e) {
    formError.value = (e as Error).message
    if (e instanceof ApiError && e.status === 409)
      formError.value += '。请关闭并重新打开草稿核对。'
  } finally {
    busy.value = false
    confirmSubmit.value = false
  }
}
function openSimulator() {
  Object.assign(notice, {
    externalNoticeNo: '',
    purchaseOrderNo: '',
    supplierId: suppliers.value[0]?.id || 0,
    items: [{ materialId: materials.value[0]?.id || 0, quantity: '1' }],
  })
  formError.value = ''
  simulator.value = true
}
let arrivalEvent:
  { body: string; messageId: string; occurredAt: string } | undefined
async function importNotice() {
  busy.value = true
  formError.value = ''
  try {
    const body = JSON.stringify({ ...notice, warehouseId: warehouseId.value })
    if (!arrivalEvent || arrivalEvent.body !== body)
      arrivalEvent = {
        body,
        messageId: crypto.randomUUID(),
        occurredAt: new Date().toISOString(),
      }
    await request('/api/local/erp-messages', {
      method: 'POST',
      body: JSON.stringify({
        messageId: arrivalEvent.messageId,
        eventType: 'ArrivalNoticeCreated',
        eventVersion: 1,
        sourceSystem: 'ERP',
        occurredAt: arrivalEvent.occurredAt,
        traceId: arrivalEvent.messageId,
        aggregateType: 'ArrivalNotice',
        aggregateId: notice.externalNoticeNo,
        payload: JSON.parse(body),
      }),
    })
    simulator.value = false
    message.value =
      '到货消息已发送，稍后刷新查看通知；处理结果可在消息管理中查询。'
    await load()
  } catch (e) {
    formError.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(loadFoundation)
</script>
<template>
  <main class="page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">PURCHASE RECEIVING</p>
        <h1>采购收货</h1>
        <p class="muted">从到货通知登记收货，提交后进入待检库存。</p>
      </div>
      <button
        v-if="simEnabled"
        :disabled="!warehouseId || busy || loading || editor || simulator"
        @click="openSimulator"
      >
        本地模拟 ERP 到货
      </button>
    </div>
    <p v-if="error" class="error" role="alert">
      {{ error }}
      <button
        :disabled="busy || loading || editor || simulator"
        @click="loadFoundation"
      >
        重新加载
      </button>
    </p>
    <p v-if="message" class="success" role="status">{{ message }}</p>
    <p class="hint">
      收货后进入待检库存；质检、上架、冲正和退货请前往对应页面。库存数量通过业务单据更新，保留完整流水。
    </p>
    <section class="panel">
      <div class="filters">
        <label
          >查看仓库<select
            v-model="warehouseId"
            :disabled="busy || loading || editor || simulator"
            @change="switchWarehouse"
          >
            <option :value="0" disabled>请先建立仓库</option>
            <option v-for="w in warehouses" :key="w.id" :value="w.id">
              {{ w.name }}{{ w.status === 'DISABLED' ? '（停用）' : '' }}
            </option>
          </select></label
        ><button
          :disabled="loading || busy || editor || simulator"
          @click="load"
        >
          刷新</button
        ><span class="hint">最近 100 条到货通知</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>到货通知 / 采购订单</th>
              <th>供应商</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in arrivals" :key="a.id">
              <td>
                {{ a.externalNoticeNo }}<small>{{ a.purchaseOrderNo }}</small>
              </td>
              <td>
                {{
                  suppliers.find((s) => s.id === a.supplierId)?.name ||
                  a.supplierId
                }}
              </td>
              <td>
                <span class="badge">{{ arrivalStatuses[a.status] }}</span>
              </td>
              <td>
                <button
                  :disabled="busy || loading || editor || simulator"
                  @click="selectArrival(a.id)"
                >
                  查看与收货
                </button>
              </td>
            </tr>
            <tr v-if="!arrivals.length">
              <td colspan="4" class="empty">
                {{ loading ? '正在加载…' : '暂无到货通知' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-if="selected" class="panel">
      <div class="panel-body">
        <div class="page-heading">
          <div>
            <h2>{{ selected.arrival.externalNoticeNo }}</h2>
            <span class="hint"
              >每次分批收货保存为独立记录，提交前可编辑草稿。</span
            >
          </div>
          <button
            v-if="canDraft"
            class="primary"
            :disabled="
              busy ||
              loading ||
              editor ||
              simulator ||
              selected.arrival.status === 'RECEIVED' ||
              !eligibleLocations.length
            "
            @click="newDraft"
          >
            登记本次收货
          </button>
        </div>
        <p v-if="!eligibleLocations.length" class="error">
          请先在基础资料中建立收货暂存区或待检区库位。
        </p>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>通知数量</th>
              <th>累计已收</th>
              <th>剩余待收</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="i in selected.items" :key="i.id">
              <td>
                {{ i.materialName }}<small>{{ i.materialCode }}</small>
              </td>
              <td>{{ i.noticeQty }} {{ i.unit }}</td>
              <td>{{ i.receivedQty }}</td>
              <td>{{ remaining(i.noticeQty, i.receivedQty) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="panel-body">
        <h3>收货记录</h3>
        <div v-if="!history.length" class="hint">尚未登记收货</div>
        <div v-for="r in history" :key="r.id" class="record-row">
          <span
            >{{ r.receiptNo }}
            <span class="badge" :class="{ disabled: r.status === 'DRAFT' }">{{
              r.correctionStatus === 'REVERSED'
                ? '已冲正'
                : r.status === 'DRAFT'
                  ? '草稿'
                  : '已提交'
            }}</span></span
          ><button
            :disabled="busy || loading || editor || simulator"
            @click="openReceipt(r.id)"
          >
            {{ r.status === 'DRAFT' ? '查看 / 编辑草稿' : '查看流水' }}
          </button>
        </div>
      </div>
    </section>
    <section class="panel">
      <div class="panel-body">
        <h2>库存余额</h2>
        <p class="hint">
          最近 100
          条库存维度；待检与不合格库存不可用，合格库存上架后才计入可用数量。
        </p>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>库位</th>
              <th>批次</th>
              <th>质量状态</th>
              <th>现有数量</th>
              <th>可用数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in balances" :key="b.id">
              <td>
                {{
                  materials.find((m) => m.id === b.materialId)?.name ||
                  b.materialId
                }}
              </td>
              <td>
                {{
                  locations.find((l) => l.id === b.locationId)?.name ||
                  b.locationId
                }}
              </td>
              <td>{{ b.batchNo || '—' }}</td>
              <td>
                {{
                  b.qualityStatus === 'QUALIFIED'
                    ? '合格'
                    : b.qualityStatus === 'REJECTED'
                      ? '不合格'
                      : '待检'
                }}
              </td>
              <td>{{ b.onHandQty }}</td>
              <td>{{ b.availableQty }}</td>
            </tr>
            <tr v-if="!balances.length">
              <td colspan="6" class="empty">暂无库存</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <div v-if="editor" class="overlay">
      <section
        class="modal wide"
        role="dialog"
        aria-modal="true"
        aria-labelledby="receipt-title"
      >
        <h2 id="receipt-title">
          {{
            readonly ? '收货记录' : current ? '编辑收货草稿' : '登记本次收货'
          }}
        </h2>
        <p v-if="formError" role="alert" class="error">{{ formError }}</p>
        <form @submit.prevent="saveDraft">
          <fieldset :disabled="busy || readonly || !canDraft">
            <div v-for="(item, index) in lines" :key="index" class="line-card">
              <div class="form-grid">
                <label
                  >通知物料<select v-model="item.arrivalItemId">
                    <option
                      v-for="source in selected?.items"
                      :key="source.id"
                      :value="source.id"
                    >
                      {{ source.materialCode }} · {{ source.materialName }}
                    </option>
                  </select></label
                ><label
                  >实收数量<input
                    v-model="item.quantity"
                    required
                    type="text"
                    inputmode="decimal"
                    pattern="[0-9]{1,12}([.][0-9]{1,6})?"
                    min="0.000001"
                    max="999999999999.999999"
                    step="0.000001" /></label
                ><label
                  >收货库位<select v-model="item.locationId" required>
                    <option :value="0" disabled>请选择</option>
                    <option
                      v-for="l in eligibleLocations"
                      :key="l.id"
                      :value="l.id"
                    >
                      {{ l.code }} · {{ l.name }}
                    </option>
                  </select></label
                ><label
                  >供应商批次<input
                    v-model="item.batchNo"
                    maxlength="64"
                    placeholder="按批次管理的物料必填" /></label
                ><label
                  >Date Code<input
                    v-model="item.dateCode"
                    maxlength="32" /></label
                ><label
                  >生产日期<input
                    v-model="item.productionDate"
                    type="date" /></label
                ><label
                  >有效期<input v-model="item.expiryDate" type="date"
                /></label>
              </div>
              <button
                v-if="lines.length > 1"
                type="button"
                @click="lines.splice(index, 1)"
              >
                移除此明细
              </button>
            </div>
            <button
              v-if="!readonly"
              type="button"
              :disabled="lines.length >= 100"
              @click="addLine"
            >
              ＋ 增加批次 / 物料
            </button>
          </fieldset>
          <div v-if="ledger.length" class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>流水</th>
                  <th>变更前</th>
                  <th>本次增加</th>
                  <th>变更后</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="entry in ledger" :key="entry.id">
                  <td>{{ entry.id }}</td>
                  <td>{{ entry.beforeQty }}</td>
                  <td>{{ entry.changeQty }}</td>
                  <td>{{ entry.afterQty }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="modal-actions">
            <button type="button" :disabled="busy" @click="editor = false">
              关闭</button
            ><button
              v-if="!readonly && canDraft"
              class="primary"
              :disabled="
                busy || !lines.length || lines.some((l) => !l.locationId)
              "
            >
              保存草稿
            </button>
          </div>
        </form>
        <div v-if="current && !readonly && canSubmit" class="submit-box">
          <p class="hint">
            提交将使用服务端已保存的草稿。修改了表单时请先保存，再打开草稿提交。
          </p>
          <button
            :disabled="
              busy || JSON.stringify(lines) !== JSON.stringify(current.items)
            "
            @click="confirmSubmit = true"
          >
            提交已保存草稿
          </button>
        </div>
        <div v-if="confirmSubmit" class="submit-box">
          <strong>确认提交收货？</strong>
          <p>提交后不可修改；将增加待检库存，可用数量仍为 0。</p>
          <button type="button" :disabled="busy" @click="confirmSubmit = false">
            取消
          </button>
          <button class="primary" :disabled="busy" @click="submit">
            确认提交
          </button>
        </div>
      </section>
    </div>
    <div v-if="simulator" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="sim-title"
      >
        <h2 id="sim-title">本地模拟 ERP 到货</h2>
        <p class="hint">仅用于本地开发验证，生产环境不开放此入口。</p>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <form @submit.prevent="importNotice">
          <fieldset :disabled="busy">
            <div class="form-grid">
              <label
                >ERP 到货通知号<input
                  v-model="notice.externalNoticeNo"
                  required
                  maxlength="64" /></label
              ><label
                >采购订单号<input
                  v-model="notice.purchaseOrderNo"
                  required
                  maxlength="64" /></label
              ><label
                >供应商<select v-model="notice.supplierId">
                  <option :value="0" disabled>请选择供应商</option>
                  <option v-for="s in suppliers" :key="s.id" :value="s.id">
                    {{ s.name }}
                  </option>
                </select></label
              >
            </div>
            <div
              v-for="(item, index) in notice.items"
              :key="index"
              class="line-card form-grid"
            >
              <label
                >物料<select v-model="item.materialId">
                  <option :value="0" disabled>请选择物料</option>
                  <option v-for="m in materials" :key="m.id" :value="m.id">
                    {{ m.code }} · {{ m.name }}
                  </option>
                </select></label
              ><label
                >通知数量<input
                  v-model="item.quantity"
                  type="text"
                  inputmode="decimal"
                  pattern="[0-9]{1,12}([.][0-9]{1,6})?"
                  min="0.000001"
                  step="0.000001"
                  required /></label
              ><button
                v-if="notice.items.length > 1"
                type="button"
                @click="notice.items.splice(index, 1)"
              >
                移除
              </button>
            </div>
            <button
              type="button"
              :disabled="notice.items.length >= 100"
              @click="notice.items.push({ materialId: 0, quantity: '1' })"
            >
              ＋ 增加物料
            </button>
          </fieldset>
          <div class="modal-actions">
            <button type="button" :disabled="busy" @click="simulator = false">
              取消</button
            ><button
              class="primary"
              :disabled="
                busy ||
                !notice.supplierId ||
                notice.items.some((i) => !i.materialId)
              "
            >
              创建模拟通知
            </button>
          </div>
        </form>
      </section>
    </div>
  </main>
</template>
