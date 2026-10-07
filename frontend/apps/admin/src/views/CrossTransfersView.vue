<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request, type Page, type Warehouse } from '../api'
const props = defineProps<{ authorities: string[]; username: string }>()
interface Transfer {
  id: number
  source_warehouse_id: number
  target_warehouse_id: number
  source_balance_id: number
  target_balance_id: number | null
  source_warehouse: string
  target_warehouse: string
  target_location: string
  material_name: string
  material_code: string
  batch_no: string
  unit: string
  quantity: string
  in_transit_qty: string
  status: string
  created_by: string
  reason: string
}
interface Stock {
  id: number
  location_id: number
  location_name: string
  expiry_date: string | null
  material_name: string
  batch_no: string
  available_qty: string
  production_qty: string
  owner_type: string
  owner_id: number
}
interface Location {
  id: number
  warehouseId: number
  name: string
  areaType: string
}
interface Event {
  id: number
  action_type: string
  reason: string
  created_by: string
  created_at: string
}
type Action =
  'create' | 'approve' | 'reject' | 'cancel' | 'ship' | 'receive' | 'history'
const warehouses = ref<Warehouse[]>([]),
  warehouse = ref(0),
  result = ref<Page<Transfer>>()
const busy = ref(false),
  error = ref(''),
  notice = ref(''),
  modal = ref<Action>(),
  row = ref<Transfer>()
const stocks = ref<Stock[]>([]),
  locations = ref<Location[]>([]),
  balance = ref(0),
  targetWarehouse = ref(0),
  targetLocation = ref(0)
const quantity = ref(''),
  reason = ref(''),
  ack = ref(false),
  key = ref(''),
  frozen = ref<{ url: string; body: string }>(),
  history = ref<Event[]>([])
const names: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '待发出',
  IN_TRANSIT: '在途',
  RECEIVED: '已收货',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
  CREATE: '提交申请',
  APPROVE: '审批通过',
  REJECT: '驳回',
  CANCEL: '取消',
  SHIP: '实际发出',
  RECEIVE: '整批收货',
}
const titles: Record<Action, string> = {
  create: '申请跨仓调拨',
  approve: '审批并预占',
  reject: '驳回申请',
  cancel: '取消调拨',
  ship: '确认源仓实际发出',
  receive: '确认目标仓整批收货',
  history: '调拨操作记录',
}
function allowed(p: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:cross-transfer:${p}`)
  )
}
function access(id: number) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:warehouse:${id}`)
  )
}
const destinations = computed(() =>
  warehouses.value.filter(
    (w) =>
      w.id !== warehouse.value &&
      w.status === 'ENABLED' &&
      w.purpose ===
        warehouses.value.find((s) => s.id === warehouse.value)?.purpose,
  ),
)
const targetLocations = computed(() =>
  locations.value.filter(
    (l) => l.warehouseId === targetWarehouse.value && l.areaType === 'STORAGE',
  ),
)
function can(r: Transfer, a: Action) {
  if (a === 'history') return true
  const source = access(r.source_warehouse_id)
  if (a === 'approve' || a === 'reject')
    return (
      source &&
      allowed('review') &&
      r.status === 'PENDING' &&
      r.created_by !== props.username
    )
  if (a === 'cancel')
    return source && allowed(a) && ['PENDING', 'APPROVED'].includes(r.status)
  if (a === 'ship') return source && allowed(a) && r.status === 'APPROVED'
  if (a === 'receive')
    return (
      access(r.target_warehouse_id) && allowed(a) && r.status === 'IN_TRANSIT'
    )
  return false
}
async function perform(fn: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function load(page = 0) {
  result.value = undefined
  modal.value = undefined
  row.value = undefined
  if (warehouse.value)
    result.value = await request(
      `/api/v1/wms/cross-transfers?warehouseId=${warehouse.value}&page=${page}&size=20`,
    )
}
async function open(action: Action, r?: Transfer) {
  await perform(async () => {
    modal.value = undefined
    row.value = undefined
    stocks.value = []
    history.value = []
    if (action === 'create') {
      locations.value = await request('/api/master-data/locations')
      for (let p = 0; ; p++) {
        const data = await request<Page<Stock>>(
          `/api/v1/wms/stock?warehouseId=${warehouse.value}&quality=QUALIFIED&page=${p}&size=100`,
        )
        stocks.value.push(
          ...data.items.filter(
            (s) =>
              Number(s.available_qty) > 0 &&
              Number(s.production_qty) === 0 &&
              s.owner_type === 'ENTERPRISE' &&
              s.owner_id === 0 &&
              locations.value.some(
                (l) => l.id === s.location_id && l.areaType === 'STORAGE',
              ),
          ),
        )
        if (p + 1 >= data.totalPages) break
      }
    }
    if (r) {
      row.value = await request(`/api/v1/wms/cross-transfers/${r.id}`)
      if (action === 'history')
        history.value = await request(
          `/api/v1/wms/cross-transfers/${r.id}/history`,
        )
    }
    balance.value = stocks.value[0]?.id || 0
    targetWarehouse.value = 0
    targetLocation.value = 0
    quantity.value = ''
    reason.value = ''
    ack.value = false
    key.value = crypto.randomUUID()
    frozen.value = undefined
    modal.value = action
  })
}
async function submit() {
  await perform(async () => {
    if (!modal.value || modal.value === 'history' || !ack.value) return
    if (!frozen.value) {
      if (!reason.value.trim()) throw new Error('请填写操作原因')
      if (
        modal.value === 'create' &&
        (!balance.value ||
          !targetLocation.value ||
          !/^\d{1,12}(\.\d{1,6})?$/.test(quantity.value) ||
          Number(quantity.value) <= 0)
      )
        throw new Error('请选择库存和目标库位，填写大于零且最多六位小数的数量')
      frozen.value = {
        url:
          modal.value === 'create'
            ? '/api/v1/wms/cross-transfers'
            : `/api/v1/wms/cross-transfers/${row.value!.id}/${modal.value}`,
        body: JSON.stringify(
          modal.value === 'create'
            ? {
                idempotencyKey: key.value,
                sourceBalanceId: balance.value,
                targetLocationId: targetLocation.value,
                quantity: quantity.value,
                reason: reason.value.trim(),
              }
            : { idempotencyKey: key.value, reason: reason.value.trim() },
        ),
      }
    }
    const saved = await request<Transfer>(frozen.value.url, {
      method: 'POST',
      body: frozen.value.body,
    })
    notice.value = `WT-${saved.id} 操作成功：${names[saved.status]}`
    await load(result.value?.page || 0)
  })
}
onMounted(
  () =>
    void perform(async () => {
      for (let page = 1; ; page++) {
        const data = await request<Page<Warehouse>>(
          `/api/master-data/warehouses?page=${page}&size=100`,
        )
        warehouses.value.push(
          ...data.items.filter(
            (w) =>
              access(w.id) &&
              ['RAW_MATERIAL', 'FINISHED_GOODS'].includes(w.purpose),
          ),
        )
        if (page >= data.totalPages) break
      }
      warehouse.value = warehouses.value[0]?.id || 0
      await load()
    }),
)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>跨仓调拨</h1>
        <p class="muted">
          双人审批后预占，源仓实际发出转在途，目标仓整批收货后入账。
        </p>
      </div>
      <button
        v-if="allowed('create')"
        class="primary"
        :disabled="
          busy ||
          !warehouse ||
          warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
        "
        @click="open('create')"
      >
        申请调拨
      </button>
    </header>
    <p v-if="notice" role="status" class="success">{{ notice }}</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div class="toolbar">
      <label
        >查看仓库<select
          v-model="warehouse"
          :disabled="busy || !!modal"
          @change="perform(() => load())"
        >
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.name }}
          </option>
        </select></label
      ><button :disabled="busy || !!modal" @click="perform(() => load())">
        刷新记录</button
      ><RouterLink to="/inventory">库存与追溯</RouterLink>
    </div>
    <section class="panel">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>单号 / 状态</th>
              <th>物料 / 批次</th>
              <th>源仓 → 目标仓</th>
              <th>调拨 / 在途</th>
              <th>申请人 / 原因</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in result?.items" :key="r.id">
              <td>
                WT-{{ r.id }}<small>{{ names[r.status] }}</small>
              </td>
              <td>
                {{ r.material_name
                }}<small>{{ r.material_code }} / {{ r.batch_no }}</small>
              </td>
              <td>
                {{ r.source_warehouse }} → {{ r.target_warehouse
                }}<small>目标库位：{{ r.target_location }}</small>
              </td>
              <td>
                {{ r.quantity }} {{ r.unit
                }}<small>在途 {{ r.in_transit_qty }}</small>
              </td>
              <td>
                {{ r.created_by }}<small>{{ r.reason }}</small>
              </td>
              <td>
                <div class="actions">
                  <template
                    v-for="a in [
                      'approve',
                      'reject',
                      'cancel',
                      'ship',
                      'receive',
                      'history',
                    ] as const"
                    :key="a"
                    ><button
                      v-if="can(r, a)"
                      :disabled="busy || !!modal"
                      @click="open(a, r)"
                    >
                      {{ titles[a] }}
                    </button></template
                  >
                </div>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="6">
                {{
                  busy
                    ? '正在加载…'
                    : error
                      ? '加载失败，请重试'
                      : '暂无跨仓调拨记录'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pagination">
        <span
          >共 {{ result.totalElements }} 条 · 第 {{ result.page + 1 }} /
          {{ Math.max(1, result.totalPages) }} 页</span
        ><button
          :disabled="busy || !!modal || result.page === 0"
          @click="perform(() => load(result!.page - 1))"
        >
          上一页</button
        ><button
          :disabled="busy || !!modal || result.page + 1 >= result.totalPages"
          @click="perform(() => load(result!.page + 1))"
        >
          下一页
        </button>
      </div>
    </section>
    <div v-if="modal" class="overlay">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="cross-title"
        class="modal cross-dialog"
      >
        <h2 id="cross-title">
          {{ titles[modal] }} <template v-if="row">· WT-{{ row.id }}</template>
        </h2>
        <p v-if="row">
          {{ row.material_name }} / {{ row.batch_no }} · {{ row.quantity }}
          {{ row.unit }}<br />{{ row.source_warehouse }} →
          {{ row.target_warehouse }} / {{ row.target_location }}
        </p>
        <template v-if="modal === 'history'"
          ><ol>
            <li v-for="e in history" :key="e.id">
              {{ names[e.action_type] }} · {{ e.created_by }} ·
              {{ e.created_at }}
              <p>{{ e.reason }}</p>
            </li>
          </ol>
          <p v-if="row">
            源库存 #{{ row.source_balance_id
            }}<template v-if="row.target_balance_id">
              → 目标库存 #{{ row.target_balance_id }}</template
            >
            · 在途 {{ row.in_transit_qty }}
          </p>
          <button @click="modal = undefined">关闭</button></template
        >
        <form v-else @submit.prevent="submit">
          <fieldset :disabled="busy || !!frozen">
            <template v-if="modal === 'create'"
              ><label
                >源库存<select v-model="balance" required>
                  <option :value="0">请选择库存</option>
                  <option v-for="s in stocks" :key="s.id" :value="s.id">
                    #{{ s.id }} {{ s.material_name }} /
                    {{ s.batch_no || '无批次' }} / {{ s.location_name }} · 可用
                    {{ s.available_qty }}
                    {{ s.expiry_date ? `· 到期 ${s.expiry_date}` : '' }}
                  </option>
                </select></label
              >
              <label
                >目标仓库<select
                  v-model="targetWarehouse"
                  required
                  @change="targetLocation = 0"
                >
                  <option :value="0">请选择同用途仓库</option>
                  <option v-for="w in destinations" :key="w.id" :value="w.id">
                    {{ w.name }}
                  </option>
                </select></label
              >
              <label
                >目标库位<select v-model="targetLocation" required>
                  <option :value="0">请选择正式存储库位</option>
                  <option
                    v-for="l in targetLocations"
                    :key="l.id"
                    :value="l.id"
                  >
                    {{ l.name }}
                  </option>
                </select></label
              >
              <label
                >数量<input
                  v-model="quantity"
                  inputmode="decimal"
                  required
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
                  placeholder="最多六位小数"
              /></label>
            </template>
            <p v-if="modal === 'ship'" class="hint">
              仅在货物实际离开源仓后确认。确认后全量转入在途，不能取消。
            </p>
            <p v-if="modal === 'receive'" class="hint">
              仅在目标仓已收到整批货物后确认。如有短少、损坏或过期，请保留在途记录等待异常处理。
            </p>
            <label
              >操作原因<textarea
                v-model="reason"
                required
                maxlength="500"
              /></label
            ><label class="check"
              ><input
                v-model="ack"
                type="checkbox"
                required
              />我已核对单据、数量和实际业务状态</label
            >
          </fieldset>
          <p v-if="frozen" class="hint">
            请求已锁定。失败时可原样重试；关闭后请先刷新核对原单，避免重复申请。
          </p>
          <p v-if="error" role="alert" class="error">{{ error }}</p>
          <div class="actions">
            <button type="button" :disabled="busy" @click="modal = undefined">
              关闭</button
            ><button class="primary" :disabled="busy || !ack">
              {{ busy ? '处理中…' : frozen ? '原样重试' : '确认' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </main>
</template>
<style scoped>
.toolbar,
.actions,
.pagination {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar,
.pagination {
  padding: 16px 0;
}
.table-scroll {
  overflow: auto;
}
small {
  display: block;
  color: #6e6a80;
}
.cross-dialog {
  width: min(650px, 95vw);
  max-height: 90vh;
  overflow: auto;
}
fieldset {
  border: 0;
  padding: 0;
  display: grid;
  gap: 14px;
}
.check {
  display: flex;
  gap: 8px;
  align-items: center;
}
.check input {
  width: auto;
}
li {
  margin-bottom: 16px;
}
</style>
