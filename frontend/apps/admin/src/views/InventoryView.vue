<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
const route = useRoute()
import { request, type Page, type Warehouse } from '../api'

const props = defineProps<{ authorities: string[] }>()
interface Stock {
  id: number
  warehouse_name: string
  material_code: string
  material_name: string
  unit: string
  location_code: string
  location_name: string
  supplier_name: string
  batch_no: string
  date_code: string
  production_date: string | null
  expiry_date: string | null
  quality_status: string
  on_hand_qty: string
  available_qty: string
  reserved_qty: string
  production_qty: string
}
interface Ledger {
  id: number
  sales_order_id?: number | null
  finished_receipt_id?: number | null
  transaction_type: string
  receipt_no: string
  external_notice_no: string
  purchase_order_no: string
  before_qty: string
  change_qty: string
  after_qty: string
  reversed_transaction_id: number | null
  purchase_return_id: number | null
  consumption_id: number | null
  production_reversal_id?: number | null
  production_return_id: number | null
  issue_id: number | null
  count_id: number | null
  transfer_id: number | null
  source_balance_id: number | null
  target_balance_id: number | null
  created_by: string
  created_at: string
}
const warehouses = ref<Warehouse[]>([])
const warehouseId = ref(0),
  keyword = ref(''),
  batch = ref(''),
  quality = ref('')
const includeZero = ref(false),
  busy = ref(false),
  error = ref('')
const stocks = ref<Page<Stock>>(),
  ledger = ref<Page<Ledger>>(),
  selected = ref<Stock>()
const qualityNames: Record<string, string> = {
  PENDING_INSPECTION: '待检',
  QUALIFIED: '合格',
  REJECTED: '不合格',
}
const types: Record<string, string> = {
  SALES_OUT: '销售出库',
  FG_RECEIPT: '成品实物收货',
  FG_QC_OUT: '成品质检转出',
  FG_QC_IN: '成品质检转入',
  FG_PUT_OUT: '成品上架转出',
  FG_PUT_IN: '成品上架入库',
  PRC_RESTORE: '消耗冲正恢复',
  PRR_REMOVE: '退料冲正扣回',
  PRR_RESTORE: '退料冲正恢复',
  CONSUMPTION: '生产消耗',
  PROD_RETURN_OUT: '线边退料发出',
  PROD_RETURN_IN: '生产退料入库',
  ISSUE_OUT: '领料发出',
  ISSUE_IN: '线边入库',
  COUNT_GAIN: '盘盈',
  COUNT_LOSS: '盘亏',
  RECEIPT: '收货入库',
  REVERSAL: '收货冲正',
  QUALITY_OUT: '质检转出',
  QUALITY_PASS: '合格转入',
  QUALITY_REJECT: '不合格转入',
  PUTAWAY_OUT: '上架转出',
  PUTAWAY_IN: '上架转入',
  RETURN_OUT: '采购退货',
  TRANSFER_OUT: '移库移出',
  TRANSFER_IN: '移库移入',
}
const canTransfer = computed(
  () =>
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes('wms:transfer:confirm'),
)
const moving = ref<Stock>(),
  destination = ref(0),
  moveQuantity = ref(''),
  moveReason = ref(''),
  acknowledged = ref(false),
  notice = ref('')
const locations = ref<
  {
    id: number
    name: string
    code: string
    warehouseId: number
    areaType: string
  }[]
>([])
let moveKey = crypto.randomUUID()
let movePayload: string | undefined
async function openMove(stock: Stock) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const detail = await request<
      Stock & { warehouse_id: number; location_id: number }
    >(`/api/v1/wms/stock/${stock.id}`)
    const all = await request<typeof locations.value>(
      '/api/master-data/locations',
    )
    locations.value = all.filter(
      (l) =>
        l.warehouseId === detail.warehouse_id &&
        l.areaType === 'STORAGE' &&
        l.id !== detail.location_id,
    )
    moving.value = detail
    destination.value = 0
    moveQuantity.value = ''
    moveReason.value = ''
    acknowledged.value = false
    moveKey = crypto.randomUUID()
    movePayload = undefined
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function confirmMove() {
  if (!moving.value || !acknowledged.value) return
  busy.value = true
  error.value = ''
  movePayload ??= JSON.stringify({
    idempotencyKey: moveKey,
    sourceBalanceId: moving.value.id,
    targetLocationId: destination.value,
    quantity: moveQuantity.value,
    reason: moveReason.value,
  })
  try {
    const result = await request<{ id: number }>('/api/v1/wms/transfers', {
      method: 'POST',
      body: movePayload,
    })
    moving.value = undefined
    notice.value = `移库 TR-${result.id} 已完成，已生成移出和移入流水。`
    busy.value = false
    await search(0, true)
    if (Number(route?.query.balanceId) > 0)
      await followBalance(Number(route.query.balanceId))
  } catch (e) {
    error.value = `${(e as Error).message}。重试将使用首次确认的原请求；调整内容前请先核对移库记录。`
  } finally {
    busy.value = false
  }
}
async function followBalance(id: number) {
  busy.value = true
  error.value = ''
  try {
    await trace(await request<Stock>(`/api/v1/wms/stock/${id}`))
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
let applied = new URLSearchParams()
async function search(page = 0, apply = false) {
  if (!warehouseId.value || busy.value) return
  busy.value = true
  error.value = ''
  selected.value = undefined
  ledger.value = undefined
  if (apply) {
    applied = new URLSearchParams({
      warehouseId: String(warehouseId.value),
      keyword: keyword.value,
      batch: batch.value,
      quality: quality.value,
      includeZero: String(includeZero.value),
    })
  }
  applied.set('page', String(page))
  applied.set('size', '20')
  try {
    stocks.value = await request<Page<Stock>>(`/api/v1/wms/stock?${applied}`)
  } catch (e) {
    stocks.value = undefined
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function trace(stock: Stock, page = 0) {
  busy.value = true
  error.value = ''
  ledger.value = undefined
  selected.value = stock
  try {
    ledger.value = await request<Page<Ledger>>(
      `/api/v1/wms/stock/${stock.id}/transactions?page=${page}&size=20`,
    )
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  try {
    let page = 1
    while (true) {
      const result = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?size=100&page=${page}`,
      )
      warehouses.value.push(
        ...result.items.filter(
          (w) =>
            props.authorities.includes('ROLE_ADMIN') ||
            props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (page++ >= result.totalPages) break
    }
    warehouseId.value =
      warehouses.value.find((w) => w.id === Number(route?.query.warehouseId))
        ?.id ||
      warehouses.value[0]?.id ||
      0
    await search(0, true)
    if (Number(route?.query.balanceId) > 0)
      await followBalance(Number(route.query.balanceId))
  } catch (e) {
    error.value = (e as Error).message
  }
})
</script>

<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>库存与追溯</h1>
        <p class="muted">
          按仓库、物料、批次和质量状态查询库存，追溯每次数量变化的业务来源。
        </p>
      </div>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>
    <section v-if="moving" class="panel move-panel" aria-label="确认仓内移库">
      <h2>确认仓内移库 · {{ moving.material_name }}</h2>
      <p>
        来源：{{ moving.location_name }} · 批次 {{ moving.batch_no || '无' }} ·
        可用 {{ moving.available_qty }} {{ moving.unit }}
      </p>
      <p class="hint">
        仅同仓正式存储库位之间移动合格可用库存。请在实物移动完成后确认，原始流水不会覆盖。
      </p>
      <form @submit.prevent="confirmMove">
        <fieldset :disabled="busy || !!movePayload">
          <label
            >目标库位<select v-model="destination" required>
              <option :value="0" disabled>请选择目标库位</option>
              <option v-for="l in locations" :key="l.id" :value="l.id">
                {{ l.code }} · {{ l.name }}
              </option>
            </select></label
          >
          <label
            >移库数量<input
              v-model="moveQuantity"
              inputmode="decimal"
              required
              pattern="[0-9]+(\.[0-9]{1,6})?"
          /></label>
          <label
            >移库原因<textarea v-model="moveReason" required maxlength="500" />
          </label>
          <label
            ><input
              v-model="acknowledged"
              type="checkbox"
            />我确认实物已经移动至目标库位</label
          >
        </fieldset>
        <p v-if="movePayload" class="hint">
          请求已锁定，重试保持首次确认内容。修改前请先在移库记录中核对结果。
        </p>
        <button
          class="primary"
          :disabled="busy || !destination || !acknowledged"
        >
          {{ movePayload ? '按原请求重试' : '确认移库并记账' }}
        </button>
        <button type="button" :disabled="busy" @click="moving = undefined">
          关闭
        </button>
        <RouterLink to="/transfers">查看移库记录</RouterLink>
      </form>
    </section>
    <form class="panel filters" @submit.prevent="search(0, true)">
      <label
        >仓库<select v-model="warehouseId" :disabled="busy" required>
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.name }}
          </option>
        </select></label
      >
      <label
        >物料编码 / 名称<input
          v-model="keyword"
          maxlength="100"
          :disabled="busy"
      /></label>
      <label
        >批次（精确匹配）<input v-model="batch" maxlength="64" :disabled="busy"
      /></label>
      <label
        >质量状态<select v-model="quality" :disabled="busy">
          <option value="">全部</option>
          <option
            v-for="(name, value) in qualityNames"
            :key="value"
            :value="value"
          >
            {{ name }}
          </option>
        </select></label
      >
      <label class="zero-option"
        ><input
          v-model="includeZero"
          type="checkbox"
          :disabled="busy"
        />包含零库存历史</label
      >
      <button class="primary" :disabled="busy || !warehouseId">
        {{ busy ? '查询中…' : '查询库存' }}
      </button>
    </form>
    <p class="hint">
      待检和不合格库存不可用；合格品上架后才计入可用数量。不同物料和单位不合并汇总。
    </p>
    <section class="panel">
      <div class="inventory-table">
        <table>
          <thead>
            <tr>
              <th>物料 / 单位</th>
              <th>库位</th>
              <th>来源 / 批次</th>
              <th>质量状态</th>
              <th>现有数量</th>
              <th>可用数量</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in stocks?.items" :key="s.id">
              <td>
                {{ s.material_name
                }}<small>{{ s.material_code }} · {{ s.unit }}</small>
              </td>
              <td>
                {{ s.location_name }}<small>{{ s.location_code }}</small>
              </td>
              <td>
                {{ s.supplier_name }}<small>{{ s.batch_no || '无批次' }}</small>
              </td>
              <td>{{ qualityNames[s.quality_status] || s.quality_status }}</td>
              <td>{{ s.on_hand_qty }}</td>
              <td>
                {{ s.available_qty
                }}<small class="muted"
                  >预占 {{ s.reserved_qty || '0' }} · 生产占用
                  {{ s.production_qty || '0' }}</small
                >
              </td>
              <td>
                <button :disabled="busy" @click="trace(s)">查看流水</button>
                <button
                  v-if="
                    canTransfer &&
                    s.quality_status === 'QUALIFIED' &&
                    Number(s.available_qty) > 0
                  "
                  :disabled="busy"
                  @click="openMove(s)"
                >
                  移库
                </button>
                <RouterLink
                  v-if="
                    (props.authorities.includes('ROLE_ADMIN') ||
                      props.authorities.includes('wms:count:create')) &&
                    s.quality_status === 'QUALIFIED'
                  "
                  :to="`/counts?balanceId=${s.id}`"
                  >盘点</RouterLink
                >
              </td>
            </tr>
            <tr v-if="!stocks?.items.length">
              <td colspan="7">
                {{ busy ? '正在加载…' : '没有符合条件的库存记录' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="stocks" class="inventory-pagination">
        <span
          >共 {{ stocks.totalElements }} 条 · 第 {{ stocks.page + 1 }} /
          {{ Math.max(1, stocks.totalPages) }} 页</span
        >
        <button
          :disabled="busy || stocks.page === 0"
          @click="search(stocks.page - 1)"
        >
          上一页
        </button>
        <button
          :disabled="busy || stocks.page + 1 >= stocks.totalPages"
          @click="search(stocks.page + 1)"
        >
          下一页
        </button>
      </div>
    </section>
    <section v-if="selected" class="panel" aria-label="库存流水">
      <h2>{{ selected.material_name }} · 库存流水</h2>
      <p>
        {{ selected.warehouse_name }} / {{ selected.location_name }} · 批次
        {{ selected.batch_no || '无' }} ·
        {{ qualityNames[selected.quality_status] }}
      </p>
      <p class="hint">
        Date Code：{{ selected.date_code || '无' }} · 生产日期：{{
          selected.production_date || '无'
        }}
        · 有效期：{{
          selected.expiry_date || '无'
        }}。流水按最新在前排列，原始记录不覆盖。
      </p>
      <div class="inventory-table">
        <table>
          <thead>
            <tr>
              <th>流水 / 类型</th>
              <th>业务来源</th>
              <th>变动前</th>
              <th>变动数量</th>
              <th>变动后</th>
              <th>关联记录</th>
              <th>操作人 / 时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in ledger?.items" :key="t.id">
              <td>
                #{{ t.id
                }}<small>{{
                  types[t.transaction_type] || t.transaction_type
                }}</small>
              </td>
              <td>
                <template v-if="t.sales_order_id"
                  >销售 SO-{{ t.sales_order_id }}</template
                ><template v-else-if="t.finished_receipt_id"
                  >成品 FG-{{ t.finished_receipt_id }}</template
                ><template v-else-if="t.issue_id"
                  >领料 MI-{{ t.issue_id
                  }}<small v-if="t.consumption_id"
                    >消耗 PC-{{ t.consumption_id }}</small
                  ><small v-if="t.production_return_id"
                    >退料 PR-{{ t.production_return_id }}</small
                  ></template
                >
                <template v-else-if="t.count_id"
                  >盘点 CT-{{ t.count_id }}</template
                ><template v-else-if="t.transfer_id"
                  >移库 TR-{{ t.transfer_id
                  }}<small
                    >来源可能包含多次收货，沿库存维度追溯。</small
                  ></template
                >
                <template v-else
                  >{{ t.receipt_no
                  }}<small>到货：{{ t.external_notice_no }}</small
                  ><small>采购：{{ t.purchase_order_no }}</small></template
                >
              </td>
              <td>{{ t.before_qty }}</td>
              <td>{{ t.change_qty }}</td>
              <td>{{ t.after_qty }}</td>
              <td>
                <span v-if="t.transfer_id || t.issue_id"
                  ><button
                    v-if="t.source_balance_id"
                    :disabled="busy"
                    @click="followBalance(t.source_balance_id)"
                  >
                    来源库存 #{{ t.source_balance_id }}</button
                  ><button
                    v-if="t.target_balance_id"
                    :disabled="busy"
                    @click="followBalance(t.target_balance_id)"
                  >
                    目标库存 #{{ t.target_balance_id }}
                  </button></span
                >
                <span v-else-if="t.reversed_transaction_id"
                  >冲正原流水 #{{ t.reversed_transaction_id }}</span
                ><span v-else-if="t.purchase_return_id"
                  >退货 RT-{{ t.purchase_return_id }}</span
                ><span v-else>—</span>
              </td>
              <td>
                {{ t.created_by }}<small>{{ t.created_at }}</small>
              </td>
            </tr>
            <tr v-if="!ledger?.items.length">
              <td colspan="7">{{ busy ? '正在加载…' : '暂无流水' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="ledger" class="inventory-pagination">
        <span
          >共 {{ ledger.totalElements }} 条 · 第 {{ ledger.page + 1 }} /
          {{ Math.max(1, ledger.totalPages) }} 页</span
        >
        <button
          :disabled="busy || ledger.page === 0"
          @click="trace(selected, ledger.page - 1)"
        >
          较新流水
        </button>
        <button
          :disabled="busy || ledger.page + 1 >= ledger.totalPages"
          @click="trace(selected, ledger.page + 1)"
        >
          较早流水
        </button>
      </div>
    </section>
  </main>
</template>
<style scoped>
.move-panel {
  padding: 20px;
}
.move-panel fieldset {
  border: 0;
  display: grid;
  gap: 12px;
  padding: 0;
  margin-bottom: 12px;
}
.inventory-table {
  overflow-x: auto;
}
.inventory-table table {
  min-width: 1000px;
}
.inventory-table button {
  white-space: nowrap;
}
td small {
  display: block;
  color: #6e6a80;
  margin-top: 4px;
}
.inventory-pagination {
  display: flex;
  gap: 12px;
  align-items: center;
  padding-top: 16px;
}
.zero-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
.zero-option input {
  width: auto;
}
</style>
