<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request, type Page, type Warehouse } from '../api'
const props = defineProps<{ authorities: string[]; username: string }>()
interface Sale {
  id: number
  demand_no: string
  sales_order_no: string
  customer_reference: string
  material_id: number
  material_name: string
  amount: string
  unit: string
  status: string
  batch_no: string
  location_name: string
  source_balance_id: number
  pick_id: number
  picked_by: string
  reviewed_by: string
  cancel_reason: string
}
interface Stock {
  id: number
  material_id: number
  origin_type: string
  batch_no: string
  location_name: string
  available_qty: string
  expiry_date: string | null
}
const warehouses = ref<Warehouse[]>([]),
  warehouse = ref(0),
  result = ref<Page<Sale>>(),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  simulator = ref(false),
  materials = ref<{ id: number; code: string; name: string }[]>([])
const modal = ref<
    'create' | 'reserve' | 'pick' | 'review' | 'ship' | 'cancel' | 'history'
  >(),
  row = ref<Sale>(),
  stocks = ref<Stock[]>([]),
  balance = ref(0),
  demandNo = ref(''),
  salesOrder = ref(''),
  customer = ref(''),
  material = ref(0),
  amount = ref(''),
  batch = ref(''),
  reason = ref(''),
  approved = ref(true),
  ack = ref(false),
  frozen = ref<string>(),
  key = ref('')
const history = ref<
    {
      id: number
      action_type: string
      pick_id: number
      reason: string
      created_by: string
      created_at: string
    }[]
  >([]),
  reservations = ref<
    {
      event_type: string
      quantity: string
      before_reserved: string
      after_reserved: string
      created_by: string
    }[]
  >([]),
  feedback = ref<
    { message_id: string; status: string; received_at: string | null }[]
  >([])
const names: Record<string, string> = {
  OPEN: '待预占',
  RESERVED: '待拣货',
  PICKED: '待独立复核',
  VERIFIED: '待实际出库',
  SHIPPED: '已出库',
  CANCELLED: '已取消',
  PENDING: '待发布',
  PUBLISHING: '发布中',
  PUBLISHED: '已发布',
  FAILED: '失败待重试',
  DEAD: '死信',
  PICK: '拣货确认',
  APPROVE: '复核通过',
  REJECT: '复核退回',
  RESERVE: '库存预占',
  RELEASE: '取消释放',
  CONSUME: '出库核销',
}
const titles: Record<string, string> = {
  create: '模拟 ERP 出库需求',
  reserve: '选择成品库存预占',
  pick: '确认实际拣货',
  review: '独立复核',
  ship: '确认实际出库',
  cancel: '取消并释放预占',
  history: '操作与反馈记录',
}
function allowed(p: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:sales:${p}`)
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
  modal.value = undefined
  row.value = undefined
  if (warehouse.value)
    result.value = await request(
      `/api/v1/wms/sales-orders?warehouseId=${warehouse.value}&page=${page}&size=20`,
    )
}
async function open(type: typeof modal.value, r?: Sale) {
  await perform(async () => {
    stocks.value = []
    if (type === 'reserve') {
      for (let p = 0; ; p++) {
        const data = await request<Page<Stock>>(
          `/api/v1/wms/stock?warehouseId=${warehouse.value}&quality=QUALIFIED&page=${p}&size=100`,
        )
        stocks.value.push(
          ...data.items.filter(
            (s) =>
              s.material_id === r!.material_id &&
              s.origin_type === 'PRODUCTION' &&
              Number(s.available_qty) > 0,
          ),
        )
        if (p + 1 >= data.totalPages) break
      }
    }
    if (type === 'history') {
      history.value = await request(`/api/v1/wms/sales-orders/${r!.id}/history`)
      reservations.value = await request(
        `/api/v1/wms/sales-orders/${r!.id}/reservations`,
      )
      feedback.value = await request(
        `/api/integration/sales-orders/${r!.id}/feedback`,
      )
    }
    modal.value = type
    row.value = r
    ack.value = false
    frozen.value = undefined
    key.value = crypto.randomUUID()
    demandNo.value = ''
    salesOrder.value = ''
    customer.value = ''
    material.value = materials.value[0]?.id || 0
    amount.value = ''
    batch.value = ''
    reason.value = ''
    approved.value = true
    balance.value = stocks.value[0]?.id || 0
  })
}
async function submit() {
  const type = modal.value
  if (!type || type === 'history' || (type !== 'create' && !ack.value)) return
  frozen.value ??= JSON.stringify(
    type === 'create'
      ? {
          demandNo: demandNo.value,
          salesOrderNo: salesOrder.value,
          customerReference: customer.value,
          warehouseId: warehouse.value,
          materialId: material.value,
          quantity: amount.value,
        }
      : type === 'reserve'
        ? { balanceId: balance.value }
        : type === 'pick'
          ? {
              requestKey: key.value,
              batchNo: batch.value,
              quantity: amount.value,
            }
          : type === 'review'
            ? {
                requestKey: key.value,
                pickId: row.value!.pick_id,
                approved: approved.value,
                reason: reason.value,
              }
            : type === 'cancel'
              ? { reason: reason.value }
              : {},
  )
  await perform(async () => {
    await request(
      type === 'create'
        ? '/api/local/sales-orders'
        : `/api/v1/wms/sales-orders/${row.value!.id}/${type}`,
      { method: 'POST', body: frozen.value },
    )
    modal.value = undefined
    notice.value =
      type === 'ship'
        ? '实际出库已记账，ERP 反馈已进入发送队列。'
        : type === 'cancel'
          ? '单据已取消，已释放预占。'
          : '操作已保存，请按单据状态继续处理。'
    await load()
  })
}
onMounted(() =>
  perform(async () => {
    if (!allowed('read')) throw new Error('没有销售出库查询权限')
    for (let page = 1; ; page++) {
      const data = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${page}&size=100`,
      )
      warehouses.value.push(
        ...data.items.filter(
          (w) =>
            w.purpose === 'FINISHED_GOODS' &&
            (props.authorities.includes('ROLE_ADMIN') ||
              props.authorities.includes(`wms:warehouse:${w.id}`)),
        ),
      )
      if (page >= data.totalPages) break
    }
    warehouse.value = warehouses.value[0]?.id || 0
    if (props.authorities.includes('ROLE_ADMIN')) {
      simulator.value = await request<{ enabled: boolean }>(
        '/api/local/sales-orders/capabilities',
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
        <h1>销售出库</h1>
        <p class="muted">
          预占合格成品，拣货后由另一人复核，实物出库后扣减现有库存。
        </p>
      </div>
      <button
        v-if="simulator"
        :disabled="busy || !warehouse"
        @click="open('create')"
      >
        模拟 ERP 出库需求
      </button>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <section class="panel filters">
      <label
        >成品仓<select
          v-model="warehouse"
          :disabled="busy"
          @change="perform(() => load())"
        >
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.name }}
          </option>
        </select></label
      ><button :disabled="busy" @click="perform(() => load())">刷新</button>
    </section>
    <p v-if="!warehouse">请先维护成品仓并配置仓库权限。</p>
    <section class="panel">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>出库单 / 销售单 / 客户</th>
              <th>物料 / 批次 / 数量</th>
              <th>状态 / 库位</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in result?.items" :key="r.id">
              <td>
                SO-{{ r.id
                }}<small>{{ r.demand_no }} · {{ r.sales_order_no }}</small
                ><small>{{ r.customer_reference }}</small>
              </td>
              <td>
                {{ r.material_name
                }}<small
                  >{{ r.batch_no || '待分配批次' }} · {{ r.amount }}
                  {{ r.unit }}</small
                >
              </td>
              <td>
                {{ names[r.status] }}<small>{{ r.location_name }}</small
                ><small v-if="r.picked_by">拣货 {{ r.picked_by }}</small
                ><small v-if="r.reviewed_by">复核 {{ r.reviewed_by }}</small
                ><small>{{ r.cancel_reason }}</small>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    v-if="r.status === 'OPEN' && allowed('reserve')"
                    :disabled="busy"
                    @click="open('reserve', r)"
                  >
                    预占库存</button
                  ><button
                    v-if="r.status === 'RESERVED' && allowed('pick')"
                    :disabled="busy"
                    @click="open('pick', r)"
                  >
                    确认拣货</button
                  ><button
                    v-if="
                      r.status === 'PICKED' &&
                      allowed('review') &&
                      r.picked_by !== username
                    "
                    :disabled="busy"
                    @click="open('review', r)"
                  >
                    复核拣货</button
                  ><small
                    v-if="r.status === 'PICKED' && r.picked_by === username"
                    >请由另一人复核</small
                  ><button
                    v-if="r.status === 'VERIFIED' && allowed('ship')"
                    :disabled="busy"
                    @click="open('ship', r)"
                  >
                    确认实际出库</button
                  ><button
                    v-if="
                      !['SHIPPED', 'CANCELLED'].includes(r.status) &&
                      allowed('cancel')
                    "
                    :disabled="busy"
                    @click="open('cancel', r)"
                  >
                    取消单据</button
                  ><button :disabled="busy" @click="open('history', r)">
                    操作与反馈</button
                  ><RouterLink
                    v-if="r.source_balance_id"
                    :to="`/inventory?warehouseId=${warehouse}&balanceId=${r.source_balance_id}`"
                    >库存追溯</RouterLink
                  >
                </div>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="4">暂无销售出库需求</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pager">
        <span>共 {{ result.totalElements }} 单</span
        ><button
          :disabled="busy || result.page === 0"
          @click="perform(() => load(result!.page - 1))"
        >
          上一页</button
        ><button
          :disabled="busy || result.page + 1 >= result.totalPages"
          @click="perform(() => load(result!.page + 1))"
        >
          下一页
        </button>
      </div>
    </section>
    <div v-if="modal" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="处理销售出库"
      >
        <h2>{{ titles[modal] }}</h2>
        <p v-if="row">
          SO-{{ row.id }} · {{ row.customer_reference }} ·
          {{ row.material_name }} · {{ row.batch_no || '待分配批次' }} ·
          {{ row.amount }} {{ row.unit }}
        </p>
        <template v-if="modal === 'history'"
          ><h3>预占记录</h3>
          <ul>
            <li v-for="(r, i) in reservations" :key="i">
              {{ names[r.event_type] }} {{ r.quantity }} · 预占
              {{ r.before_reserved }} → {{ r.after_reserved }} ·
              {{ r.created_by }}
            </li>
          </ul>
          <h3>拣货与复核记录</h3>
          <ul>
            <li v-for="h in history" :key="h.id">
              #{{ h.id }} {{ names[h.action_type] }} · {{ h.created_by }} ·
              {{ h.reason }} · {{ h.created_at }}
            </li>
          </ul>
          <h3>ERP 出库反馈</h3>
          <p v-if="!feedback.length">实际出库后生成反馈。</p>
          <ul>
            <li v-for="f in feedback" :key="f.message_id">
              {{ names[f.status] }} ·
              {{ f.received_at ? 'ERP 已接收 ' + f.received_at : '尚未接收' }}
            </li>
          </ul>
          <button @click="modal = undefined">关闭</button></template
        >
        <form v-else @submit.prevent="submit">
          <fieldset :disabled="busy || !!frozen">
            <template v-if="modal === 'create'"
              ><label
                >ERP 需求号<input
                  v-model="demandNo"
                  required
                  maxlength="64"
                  pattern="[A-Za-z0-9_-]+" /></label
              ><label
                >销售单号<input
                  v-model="salesOrder"
                  required
                  maxlength="64" /></label
              ><label
                >客户参考<input
                  v-model="customer"
                  required
                  maxlength="128" /></label
              ><label
                >成品物料<select v-model="material" required>
                  <option v-for="m in materials" :key="m.id" :value="m.id">
                    {{ m.code }} · {{ m.name }}
                  </option>
                </select></label
              ><label
                >需求数量<input
                  v-model="amount"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?" /></label></template
            ><template v-else-if="modal === 'reserve'"
              ><label
                >合格成品库存<select v-model="balance" required>
                  <option v-for="s in stocks" :key="s.id" :value="s.id">
                    #{{ s.id }} · {{ s.location_name }} · 批次
                    {{ s.batch_no }} · 可用 {{ s.available_qty }} · 有效期
                    {{ s.expiry_date || '无' }}
                  </option>
                </select></label
              >
              <p v-if="!stocks.length">无可用自产成品库存。</p>
              <p>预占减少可用量，现有库存保持不变。</p></template
            ><template v-else-if="modal === 'pick'"
              ><label
                >实拣批次<input
                  v-model="batch"
                  required
                  maxlength="64" /></label
              ><label
                >实拣数量<input
                  v-model="amount"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
              /></label>
              <p>按实物填写，必须与预占批次和整单数量一致。</p></template
            ><template v-else-if="modal === 'review'"
              ><label
                >复核结果<select v-model="approved">
                  <option :value="true">通过，等待实际出库</option>
                  <option :value="false">退回重新拣货</option>
                </select></label
              ><label
                >复核说明<textarea
                  v-model="reason"
                  required
                  maxlength="500"
                /></label></template
            ><label v-else-if="modal === 'cancel'"
              >取消原因<textarea v-model="reason" required maxlength="500" />
            </label>
            <p v-if="modal === 'ship'">
              确认实物已经出库。本操作扣减现有库存并发送 ERP
              反馈，完成后不可取消。
            </p>
            <p v-if="modal === 'cancel'">
              已拣货时，应先核对实物已归还原库位，再释放预占。
            </p>
            <label v-if="modal !== 'create'" class="check"
              ><input
                v-model="ack"
                type="checkbox"
                required
              />已核对客户、批次、数量及本次实际处理结果</label
            >
          </fieldset>
          <p v-if="frozen">
            请求已锁定，重试保持原内容；调整前请关闭并刷新核对结果。
          </p>
          <p v-if="error" class="error">{{ error }}</p>
          <div class="row-actions">
            <button type="button" :disabled="busy" @click="modal = undefined">
              关闭</button
            ><button
              :disabled="
                busy ||
                (modal !== 'create' && !ack) ||
                (modal === 'reserve' && !balance)
              "
            >
              提交销售处理
            </button>
          </div>
        </form>
      </section>
    </div>
  </main>
</template>
<style scoped>
.panel {
  margin-bottom: 20px;
}
.row-actions,
.pager {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.pager {
  padding: 16px;
}
small {
  display: block;
  margin-top: 5px;
}
table {
  min-width: 960px;
}
fieldset {
  border: 0;
  padding: 0;
  display: grid;
  gap: 14px;
}
button {
  white-space: nowrap;
}
</style>
