<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
}
interface Ledger {
  id: number
  transaction_type: string
  receipt_no: string
  external_notice_no: string
  purchase_order_no: string
  before_qty: string
  change_qty: string
  after_qty: string
  reversed_transaction_id: number | null
  purchase_return_id: number | null
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
  RECEIPT: '收货入库',
  REVERSAL: '收货冲正',
  QUALITY_OUT: '质检转出',
  QUALITY_PASS: '合格转入',
  QUALITY_REJECT: '不合格转入',
  PUTAWAY_OUT: '上架转出',
  PUTAWAY_IN: '上架转入',
  RETURN_OUT: '采购退货',
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
    warehouseId.value = warehouses.value[0]?.id || 0
    await search(0, true)
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
        >供应商批次（精确匹配）<input
          v-model="batch"
          maxlength="64"
          :disabled="busy"
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
              <th>供应商 / 批次</th>
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
              <td>{{ s.available_qty }}</td>
              <td>
                <button :disabled="busy" @click="trace(s)">查看流水</button>
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
                {{ t.receipt_no }}<small>到货：{{ t.external_notice_no }}</small
                ><small>采购：{{ t.purchase_order_no }}</small>
              </td>
              <td>{{ t.before_qty }}</td>
              <td>{{ t.change_qty }}</td>
              <td>{{ t.after_qty }}</td>
              <td>
                <span v-if="t.reversed_transaction_id"
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
.inventory-table {
  overflow-x: auto;
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
