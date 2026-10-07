<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request, type Page, type Warehouse } from '../api'
const props = defineProps<{ authorities: string[] }>()
interface Issue {
  id: number
  demand_no: string
  work_order_no: string
  status: string
  material_id: number
  material_name: string
  unit: string
  quantity: string
  batch_no: string | null
  source_location: string | null
  target_location: string | null
  target_warehouse_name: string
  cancel_reason: string | null
  closed_by: string | null
}
interface Stock {
  id: number
  material_id: number
  location_name: string
  batch_no: string
  available_qty: string
}
interface Location {
  id: number
  warehouseId: number
  areaType: string
  name: string
}
interface Event {
  id: number
  event_type: string
  quantity: string
  before_reserved: string
  after_reserved: string
  created_by: string
}
const warehouses = ref<Warehouse[]>([]),
  warehouseId = ref(0),
  targetWarehouseId = ref(0)
const result = ref<Page<Issue>>(),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  simulator = ref(false)
const materials = ref<{ id: number; name: string; code: string }[]>([])
const locations = ref<Location[]>([]),
  stocks = ref<Stock[]>([])
const sourceWarehouses = computed(() =>
  warehouses.value.filter((w) => w.purpose === 'RAW_MATERIAL'),
)
const targetWarehouses = computed(() =>
  warehouses.value.filter((w) => w.purpose === 'LINE_SIDE'),
)
const targetLocations = computed(() =>
  locations.value.filter(
    (l) =>
      l.warehouseId === targetWarehouseId.value && l.areaType === 'STORAGE',
  ),
)
const names: Record<string, string> = {
  OPEN: '待预占',
  RESERVED: '已预占',
  ISSUED: '已发料',
  CANCELLED: '已取消',
}
const eventNames: Record<string, string> = {
  RESERVE: '预占',
  RELEASE: '取消释放',
  CONSUME: '发料核销预占',
}
const action = ref<{ row: Issue; type: 'reserve' | 'cancel' | 'confirm' }>()
const selectedBalance = ref(0),
  selectedLocation = ref(0),
  reason = ref(''),
  acknowledged = ref(false),
  frozenBody = ref<string>()
const creating = ref(false),
  demandNo = ref(''),
  workOrder = ref(''),
  materialId = ref(0),
  quantity = ref(''),
  createBody = ref<string>()
const history = ref<{
  row: Issue
  events: Event[]
  feedback: {
    message_id: string
    event_type: string
    status: string
    received_at: string | null
  }[]
}>()
const blocked = computed(
  () => busy.value || !!action.value || creating.value || !!history.value,
)
function allowed(permission: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:issue:${permission}`)
  )
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
  if (!warehouseId.value || !targetWarehouseId.value) return
  result.value = await request(
    `/api/v1/wms/issues?warehouseId=${warehouseId.value}&targetWarehouseId=${targetWarehouseId.value}&page=${page}&size=20`,
  )
}
function newDemand() {
  if (blocked.value) return
  demandNo.value = ''
  workOrder.value = ''
  materialId.value = materials.value[0]?.id || 0
  quantity.value = ''
  createBody.value = undefined
  creating.value = true
}
async function create() {
  createBody.value ??= JSON.stringify({
    demandNo: demandNo.value,
    workOrderNo: workOrder.value,
    materialId: materialId.value,
    quantity: quantity.value,
    warehouseId: warehouseId.value,
    targetWarehouseId: targetWarehouseId.value,
  })
  await perform(async () => {
    const row = await request<Issue>('/api/local/mes-demands', {
      method: 'POST',
      body: createBody.value,
    })
    creating.value = false
    notice.value = `领料 MI-${row.id} 已接收，等待预占。`
    await load()
  })
}
async function openAction(row: Issue, type: 'reserve' | 'cancel' | 'confirm') {
  if (blocked.value) return
  await perform(async () => {
    if (type === 'reserve') {
      stocks.value = []
      let page = 0
      while (true) {
        const data = await request<Page<Stock>>(
          `/api/v1/wms/stock?warehouseId=${warehouseId.value}&quality=QUALIFIED&page=${page}&size=100`,
        )
        stocks.value.push(
          ...data.items.filter(
            (b) =>
              b.material_id === row.material_id && Number(b.available_qty) > 0,
          ),
        )
        if (++page >= data.totalPages) break
      }
      locations.value = await request<Location[]>('/api/master-data/locations')
      selectedBalance.value = stocks.value[0]?.id || 0
      selectedLocation.value = targetLocations.value[0]?.id || 0
    }
    action.value = { row, type }
    reason.value = ''
    acknowledged.value = false
    frozenBody.value = undefined
  })
}
async function decide() {
  if (!action.value || (action.value.type === 'confirm' && !acknowledged.value))
    return
  const { row, type } = action.value
  frozenBody.value ??= JSON.stringify(
    type === 'reserve'
      ? {
          balanceId: selectedBalance.value,
          targetLocationId: selectedLocation.value,
        }
      : type === 'cancel'
        ? { reason: reason.value }
        : {},
  )
  await perform(async () => {
    await request(`/api/v1/wms/issues/${row.id}/${type}`, {
      method: 'POST',
      body: frozenBody.value,
    })
    action.value = undefined
    notice.value =
      type === 'confirm'
        ? '发料已记账，库存已转入线边仓；尚未扣除生产消耗。'
        : type === 'cancel'
          ? '领料已取消，既有预占已释放。'
          : '库存已预占，待实际发料后确认。'
    await load()
  })
}
async function events(row: Issue) {
  if (blocked.value) return
  history.value = undefined
  await perform(async () => {
    history.value = {
      row,
      events: await request(`/api/v1/wms/issues/${row.id}/events`),
      feedback:
        row.status === 'ISSUED'
          ? await request(`/api/integration/material-issues/${row.id}/feedback`)
          : [],
    }
  })
}
onMounted(() =>
  perform(async () => {
    if (!allowed('read')) throw new Error('没有领料查询权限')
    let page = 1
    while (true) {
      const data = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${page}&size=100`,
      )
      warehouses.value.push(
        ...data.items.filter(
          (w) =>
            props.authorities.includes('ROLE_ADMIN') ||
            props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (page++ >= data.totalPages) break
    }
    warehouseId.value = sourceWarehouses.value[0]?.id || 0
    targetWarehouseId.value = targetWarehouses.value[0]?.id || 0
    if (props.authorities.includes('ROLE_ADMIN')) {
      simulator.value = await request<{ enabled: boolean }>(
        '/api/local/mes-demands/capabilities',
      )
        .then((r) => r.enabled)
        .catch(() => false)
      if (simulator.value)
        materials.value = await request('/api/master-data/materials')
    }
    await load()
  }),
)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>生产领料</h1>
        <p class="muted">
          按工单预占物料，实际发料后转入线边仓。生产消耗另行确认。
        </p>
      </div>
      <button
        v-if="simulator"
        :disabled="blocked || !warehouseId || !targetWarehouseId"
        @click="newDemand"
      >
        模拟 MES 需求
      </button>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <section class="panel filters">
      <label
        >原材料仓<select
          v-model="warehouseId"
          :disabled="blocked"
          @change="perform(() => load())"
        >
          <option v-for="w in sourceWarehouses" :key="w.id" :value="w.id">
            {{ w.name }}
          </option>
        </select></label
      >
      <label
        >线边仓<select
          v-model="targetWarehouseId"
          :disabled="blocked"
          @change="perform(() => load())"
        >
          <option v-for="w in targetWarehouses" :key="w.id" :value="w.id">
            {{ w.name }}
          </option>
        </select></label
      >
      <button :disabled="blocked" @click="perform(() => load())">刷新</button>
    </section>
    <p v-if="!warehouseId || !targetWarehouseId" class="muted">
      请先维护原材料仓与线边仓，并配置两个仓库的数据权限。
    </p>
    <section class="panel">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>领料单 / 工单</th>
              <th>物料 / 数量</th>
              <th>发料批次与去向</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in result?.items" :key="row.id">
              <td>
                MI-{{ row.id }}<small>{{ row.demand_no }}</small
                ><small>{{ row.work_order_no }}</small>
              </td>
              <td>
                {{ row.material_name
                }}<small>{{ row.quantity }} {{ row.unit }}</small>
              </td>
              <td>
                {{ row.batch_no || '待选择批次'
                }}<small
                  >{{ row.source_location || '待预占' }} →
                  {{ row.target_warehouse_name }} /
                  {{ row.target_location || '待选择库位' }}</small
                >
              </td>
              <td>
                {{ names[row.status] || row.status
                }}<small>{{ row.cancel_reason || row.closed_by }}</small>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    v-if="row.status === 'OPEN' && allowed('reserve')"
                    :disabled="blocked"
                    @click="openAction(row, 'reserve')"
                  >
                    预占库存
                  </button>
                  <button
                    v-if="row.status === 'RESERVED' && allowed('confirm')"
                    :disabled="blocked"
                    @click="openAction(row, 'confirm')"
                  >
                    确认发料
                  </button>
                  <button
                    v-if="
                      ['OPEN', 'RESERVED'].includes(row.status) &&
                      allowed('cancel')
                    "
                    :disabled="blocked"
                    @click="openAction(row, 'cancel')"
                  >
                    取消领料
                  </button>
                  <button :disabled="blocked" @click="events(row)">
                    预占与反馈
                  </button>
                  <RouterLink
                    v-if="
                      row.status === 'ISSUED' &&
                      (props.authorities.includes('ROLE_ADMIN') ||
                        props.authorities.includes('wms:production:read'))
                    "
                    :to="`/production?issueId=${row.id}`"
                    >生产处理</RouterLink
                  >
                </div>
              </td>
            </tr>
            <tr v-if="result && !result.items.length">
              <td colspan="5">暂无领料需求</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pagination">
        <span>共 {{ result.totalElements }} 单</span
        ><button
          :disabled="blocked || result.page === 0"
          @click="perform(() => load(result!.page - 1))"
        >
          上一页</button
        ><button
          :disabled="blocked || result.page + 1 >= result.totalPages"
          @click="perform(() => load(result!.page + 1))"
        >
          下一页
        </button>
      </div>
    </section>
    <div v-if="creating" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="模拟 MES 需求"
      >
        <h2>模拟 MES 领料需求</h2>
        <p>单物料、整单预占与发料。重复需求号会核对内容并返回原单。</p>
        <form @submit.prevent="create">
          <fieldset :disabled="busy || !!createBody">
            <label
              >需求号<input
                v-model="demandNo"
                required
                maxlength="64"
                pattern="[A-Za-z0-9_-]+" /></label
            ><label
              >工单号<input v-model="workOrder" required maxlength="64"
            /></label>
            <label
              >物料<select v-model="materialId" required>
                <option v-for="m in materials" :key="m.id" :value="m.id">
                  {{ m.code }} · {{ m.name }}
                </option>
              </select></label
            >
            <label
              >需求数量<input
                v-model="quantity"
                required
                inputmode="decimal"
                pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
            /></label>
          </fieldset>
          <p v-if="createBody" class="muted">
            内容已锁定；失败可重试原请求，或关闭后刷新核对。
          </p>
          <p v-if="error" role="alert" class="error">{{ error }}</p>
          <div class="actions">
            <button type="button" :disabled="busy" @click="creating = false">
              关闭</button
            ><button class="primary" :disabled="busy">接收需求</button>
          </div>
        </form>
      </section>
    </div>
    <div v-if="action" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="处理领料"
      >
        <h2>
          {{
            action.type === 'reserve'
              ? '预占库存'
              : action.type === 'cancel'
                ? '取消领料'
                : '确认实际发料'
          }}
        </h2>
        <p>
          MI-{{ action.row.id }} · 工单 {{ action.row.work_order_no }} ·
          {{ action.row.material_name }} · {{ action.row.quantity }}
          {{ action.row.unit }}
        </p>
        <form @submit.prevent="decide">
          <fieldset :disabled="busy || !!frozenBody">
            <template v-if="action.type === 'reserve'"
              ><label
                >来源库存<select v-model="selectedBalance" required>
                  <option v-for="b in stocks" :key="b.id" :value="b.id">
                    {{ b.location_name }} · 批次 {{ b.batch_no }} · 可用
                    {{ b.available_qty }}
                  </option>
                </select></label
              >
              <label
                >线边库位<select v-model="selectedLocation" required>
                  <option
                    v-for="l in targetLocations"
                    :key="l.id"
                    :value="l.id"
                  >
                    {{ l.name }}
                  </option>
                </select></label
              >
              <p>预占不减少现存量，取消领料后可释放。</p></template
            >
            <label v-else-if="action.type === 'cancel'"
              >取消原因<textarea v-model="reason" required maxlength="500" />
            </label>
            <template v-else
              ><p>
                批次 {{ action.row.batch_no }} ·
                {{ action.row.source_location }} →
                {{ action.row.target_warehouse_name }} /
                {{ action.row.target_location }}
              </p>
              <p>
                确认后原料仓扣减，线边仓增加；不计生产消耗。已发料不可取消。
              </p>
              <label class="check"
                ><input
                  v-model="acknowledged"
                  type="checkbox"
                  required
                />我已核对实物、批次和数量，物料已交付线边仓</label
              ></template
            >
          </fieldset>
          <p v-if="frozenBody" class="muted">
            保留原内容重试，或关闭后刷新核对单据。
          </p>
          <p v-if="error" role="alert" class="error">{{ error }}</p>
          <div class="actions">
            <button type="button" :disabled="busy" @click="action = undefined">
              关闭</button
            ><button
              class="primary"
              :disabled="
                busy ||
                (action.type === 'reserve' &&
                  (!selectedBalance || !selectedLocation)) ||
                (action.type === 'confirm' && !acknowledged)
              "
            >
              {{
                action.type === 'reserve'
                  ? '确认预占'
                  : action.type === 'cancel'
                    ? '确认取消'
                    : '确认记账'
              }}
            </button>
          </div>
        </form>
      </section>
    </div>
    <div v-if="history" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="预占记录"
      >
        <h2>MI-{{ history.row.id }} 预占记录</h2>
        <p v-if="!history.events.length">尚无预占记录</p>
        <ol>
          <li v-for="e in history.events" :key="e.id">
            {{ eventNames[e.event_type] }} {{ e.quantity }} · 库存预占量
            {{ e.before_reserved }} → {{ e.after_reserved }} ·
            {{ e.created_by }}
          </li>
        </ol>
        <h3 v-if="history.feedback.length">MES 反馈</h3>
        <p v-for="f in history.feedback" :key="f.message_id">
          {{
            f.event_type === 'MaterialIssued'
              ? '发料结果'
              : f.event_type === 'ProductionConsumed'
                ? '消耗结果'
                : '退料结果'
          }}
          ·
          {{
            f.status === 'PUBLISHED'
              ? '已发布'
              : f.status === 'FAILED' || f.status === 'DEAD'
                ? '发送失败，请查看消息管理'
                : '等待发布'
          }}
          · {{ f.received_at ? '模拟 MES 已接收' : '模拟 MES 尚未接收' }}
        </p>
        <button @click="history = undefined">关闭</button>
      </section>
    </div>
  </main>
</template>
<style scoped>
table {
  min-width: 1050px;
}
td small {
  display: block;
  margin-top: 6px;
}
.row-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
button {
  white-space: nowrap;
}
fieldset {
  border: 0;
  padding: 0;
  display: grid;
  gap: 14px;
}
.modal {
  max-height: 90vh;
  overflow: auto;
}
.pagination {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 16px;
}
</style>
