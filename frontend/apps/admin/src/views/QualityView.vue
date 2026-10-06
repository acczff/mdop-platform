<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request, type Warehouse, type Page } from '../api'
const props = defineProps<{ authorities: string[] }>()
const admin = computed(() => props.authorities.includes('ROLE_ADMIN'))
const canPutaway = computed(
  () => admin.value || props.authorities.includes('wms:putaway:confirm'),
)
interface Receipt {
  id: number
  receipt_no: string
  version: number
  reference_no: string | null
  downstream_stage: string
}
interface Line {
  id: number
  material_code: string
  material_name: string
  quantity: string
  batch_no: string
  qualified_qty: string | null
  rejected_qty: string | null
  putaway_location_id: number | null
  good: string
  bad: string
  destination: number
  key: string
}
interface Location {
  id: number
  warehouseId: number
  code: string
  name: string
  areaType: string
}
const warehouses = ref<Warehouse[]>([]),
  warehouseId = ref(0),
  receipts = ref<Receipt[]>([])
const locations = ref<Location[]>([]),
  selected = ref<Receipt>(),
  lines = ref<Line[]>([])
const busy = ref(false),
  error = ref(''),
  message = ref(''),
  reference = ref(''),
  reason = ref(''),
  resultKey = ref(crypto.randomUUID())
const targets = computed(() =>
  locations.value.filter(
    (l) => l.warehouseId === warehouseId.value && l.areaType === 'STORAGE',
  ),
)
async function load() {
  selected.value = undefined
  lines.value = []
  receipts.value = []
  if (!warehouseId.value) return
  busy.value = true
  error.value = ''
  try {
    receipts.value = await request<Receipt[]>(
      `/api/v1/wms/quality?warehouseId=${warehouseId.value}`,
    )
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function select(receipt: Receipt) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const items = await request<Line[]>(
      `/api/v1/wms/quality/${receipt.id}/items`,
    )
    selected.value = receipt
    lines.value = items.map((l) => ({
      ...l,
      good: l.quantity,
      bad: '0',
      destination: 0,
      key: crypto.randomUUID(),
    }))
    resultKey.value = crypto.randomUUID()
    reference.value = ''
    reason.value = ''
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function inspect() {
  if (!selected.value) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await request('/api/local/qms-results', {
      method: 'POST',
      body: JSON.stringify({
        idempotencyKey: resultKey.value,
        receiptId: selected.value.id,
        version: selected.value.version,
        referenceNo: reference.value,
        reason: reason.value,
        items: lines.value.map((l) => ({
          receiptItemId: l.id,
          qualifiedQty: l.good,
          rejectedQty: l.bad,
        })),
      }),
    })
    await load()
    message.value = '质检结果已接收。合格品上架后才增加可用库存。'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function putaway(line: Line) {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await request(`/api/v1/wms/quality/items/${line.id}/putaway`, {
      method: 'POST',
      body: JSON.stringify({
        idempotencyKey: line.key,
        locationId: line.destination,
      }),
    })
    if (selected.value) await select(selected.value)
    message.value = '上架成功，合格数量已转为可用库存。'
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
      (w) => admin.value || props.authorities.includes(`wms:warehouse:${w.id}`),
    )
    locations.value = await request<Location[]>('/api/master-data/locations')
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
        <h1>质检与上架</h1>
        <p class="muted">接收质量结果，确认合格物料的实际存放位置。</p>
      </div>
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
    <p v-if="!receipts.length" class="hint">当前仓库暂无待检或待上架收货单。</p>
    <table v-else>
      <thead>
        <tr>
          <th>收货单</th>
          <th>进度</th>
          <th>检验单号</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in receipts" :key="r.id">
          <td>{{ r.receipt_no }}</td>
          <td>
            {{
              r.reference_no
                ? r.downstream_stage === 'PUTAWAY'
                  ? '已开始上架'
                  : '已接收质检'
                : '待检'
            }}
          </td>
          <td>{{ r.reference_no || '—' }}</td>
          <td>
            <button :disabled="busy" @click="select(r)">查看 / 处理</button>
          </td>
        </tr>
      </tbody>
    </table>
    <section v-if="selected" class="panel">
      <h2>{{ selected.receipt_no }}</h2>
      <p class="hint">
        不合格品保留原位置并保持不可用；每条明细的合格数量整行上架。
      </p>
      <form v-if="!selected.reference_no && admin" @submit.prevent="inspect">
        <h3>模拟 QMS 结果</h3>
        <p class="hint">仅供本地模拟，不代表真实 QMS 已完成检验。</p>
        <label
          >检验单号<input
            v-model="reference"
            required
            maxlength="64"
            :disabled="busy"
        /></label>
        <label
          >判定说明<input
            v-model="reason"
            required
            maxlength="500"
            :disabled="busy"
        /></label>
        <div v-for="line in lines" :key="line.id" class="quality-line">
          <strong>{{ line.material_code }} · {{ line.material_name }}</strong
          ><span
            >收货 {{ line.quantity }} · 批次 {{ line.batch_no || '无' }}</span
          >
          <label
            >合格数量<input
              v-model="line.good"
              type="number"
              min="0"
              step="0.000001"
              required
              :disabled="busy"
          /></label>
          <label
            >不合格数量<input
              v-model="line.bad"
              type="number"
              min="0"
              step="0.000001"
              required
              :disabled="busy"
          /></label>
        </div>
        <button class="primary" :disabled="busy">接收模拟质检结果</button>
      </form>
      <div v-else v-for="line in lines" :key="line.id" class="quality-line">
        <strong>{{ line.material_code }} · {{ line.material_name }}</strong>
        <span
          >合格 {{ line.qualified_qty ?? '待检' }} / 不合格
          {{ line.rejected_qty ?? '待检' }}</span
        >
        <span v-if="line.putaway_location_id"
          >已上架至
          {{
            locations.find((l) => l.id === line.putaway_location_id)?.name ||
            line.putaway_location_id
          }}</span
        >
        <template v-else-if="canPutaway && Number(line.qualified_qty) > 0">
          <label
            >目标库位<select v-model="line.destination" :disabled="busy">
              <option :value="0">请选择正式存储库位</option>
              <option v-for="l in targets" :key="l.id" :value="l.id">
                {{ l.code }} · {{ l.name }}
              </option>
            </select></label
          >
          <button :disabled="busy || !line.destination" @click="putaway(line)">
            确认整行上架
          </button>
        </template>
      </div>
    </section>
  </main>
</template>
<style scoped>
.quality-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 1rem;
  padding: 1rem 0;
  border-bottom: 1px solid #e8e5ed;
}
.quality-line label {
  max-width: 18rem;
}
</style>
