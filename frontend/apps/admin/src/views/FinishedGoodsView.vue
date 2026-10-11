<script setup lang="ts">
import ResultPager from '../components/ResultPager.vue'
import {
  quantity as displayQuantity,
  identity,
  businessDate,
  timestamp,
} from '../presentation'
import RecordHeading from '../components/RecordHeading.vue'
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { request, type Page, type Warehouse } from '../api'
const props = defineProps<{ authorities: string[] }>()
interface Receipt {
  warehouse_id: number
  material_code: string
  date_code: string | null
  production_date: string | null
  expiry_date: string | null
  created_by: string
  created_at: string
  received_by: string | null
  received_at: string | null
  quality_by: string | null
  quality_at: string | null
  stored_by: string | null
  stored_at: string | null
  production_order_id?: number | null
  completion_report_id?: number | null
  completion_report_no?: string | null
  received_location_code?: string | null
  stored_location_code?: string | null
  id: number
  demand_no: string
  work_order_no: string
  material_name: string
  unit: string
  amount: string
  batch_no: string
  status: string
  received_location: string | null
  stored_location: string | null
  stored_balance_id: number | null
  quality_reason: string | null
}
interface Location {
  id: number
  warehouseId: number
  areaType: string
  name: string
}
const warehouses = ref<Warehouse[]>([]),
  warehouse = ref(0),
  materials = ref<{ id: number; code: string; name: string }[]>([]),
  locations = ref<Location[]>([]),
  result = ref<Page<Receipt>>(),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  simulator = ref(false)
const modal = ref<'create' | 'receive' | 'quality' | 'putaway' | 'feedback'>(),
  row = ref<Receipt>(),
  demandNo = ref(''),
  workOrder = ref(''),
  material = ref(0),
  amount = ref(''),
  batch = ref(''),
  dateCode = ref(''),
  productionDate = ref(''),
  expiry = ref(''),
  location = ref(0),
  quality = ref('QUALIFIED'),
  eventNo = ref(''),
  reason = ref(''),
  ack = ref(false),
  frozen = ref<string>(),
  feedback = ref<
    {
      message_id: string
      event_type: string
      status: string
      received_at: string | null
      target_system: string | null
    }[]
  >([])
const names: Record<string, string> = {
  OPEN: '待收货',
  RECEIVED: '待检验',
  QUALIFIED: '合格待上架',
  REJECTED: '不合格隔离',
  STORED: '已上架',
  PENDING: '待发布',
  PUBLISHED: '已发布',
  FAILED: '失败待重试',
  PUBLISHING: '发布中',
  DEAD: '死信',
}
const detail = ref<Receipt>()
const detailError = ref('')
const detailLoading = ref(false)
let detailGeneration = 0
async function showDetail(id: number) {
  const token = ++detailGeneration
  const selectedWarehouse = warehouse.value
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = true
  try {
    const value = await request<Receipt>(`/api/v1/wms/finished-receipts/${id}`)
    if (token !== detailGeneration) return
    if (value.warehouse_id !== selectedWarehouse)
      throw Error('该入库单不属于当前仓库，请核对来源。')
    detail.value = value
  } catch (e) {
    if (token === detailGeneration) detailError.value = (e as Error).message
  } finally {
    if (token === detailGeneration) detailLoading.value = false
  }
}
function closeDetail() {
  ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  detailLoading.value = false
}
onBeforeUnmount(closeDetail)
const events: Record<string, string> = {
  FinishedGoodsReceived: '成品收货反馈',
  FinishedInspectionRequested: '成品检验请求',
  FinishedGoodsPutaway: '成品上架反馈',
}
const options = computed(() =>
  locations.value.filter(
    (l) =>
      l.warehouseId === warehouse.value &&
      l.areaType === (modal.value === 'putaway' ? 'STORAGE' : 'INSPECTION'),
  ),
)
function allowed(p: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:finished:${p}`)
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
  closeDetail()
  result.value = undefined
  modal.value = undefined
  row.value = undefined
  if (warehouse.value)
    result.value = await request(
      `/api/v1/wms/finished-receipts?warehouseId=${warehouse.value}&page=${page}&size=20`,
    )
}
async function open(type: typeof modal.value, receipt?: Receipt) {
  await perform(async () => {
    if (type === 'receive' || type === 'putaway')
      locations.value = await request('/api/master-data/locations')
    if (type === 'feedback')
      feedback.value = await request(
        `/api/integration/finished-receipts/${receipt!.id}/feedback`,
      )
    modal.value = type
    row.value = receipt
    ack.value = false
    frozen.value = undefined
    demandNo.value = ''
    workOrder.value = ''
    material.value = materials.value[0]?.id || 0
    amount.value = ''
    batch.value = ''
    dateCode.value = ''
    productionDate.value = ''
    expiry.value = ''
    quality.value = 'QUALIFIED'
    eventNo.value = ''
    reason.value = ''
    location.value = options.value[0]?.id || 0
  })
}
async function submit() {
  const type = modal.value
  if (!type || type === 'feedback' || (type !== 'create' && !ack.value)) return
  frozen.value ??= JSON.stringify(
    type === 'create'
      ? {
          demandNo: demandNo.value,
          workOrderNo: workOrder.value,
          warehouseId: warehouse.value,
          materialId: material.value,
          quantity: amount.value,
          batchNo: batch.value,
          dateCode: dateCode.value,
          productionDate: productionDate.value,
          expiryDate: expiry.value || null,
        }
      : type === 'quality'
        ? {
            eventNo: eventNo.value,
            result: quality.value,
            reason: reason.value,
          }
        : { locationId: location.value },
  )
  await perform(async () => {
    await request(
      type === 'create'
        ? '/api/local/finished-receipts'
        : type === 'quality'
          ? `/api/local/finished-receipts/${row.value!.id}/quality`
          : `/api/v1/wms/finished-receipts/${row.value!.id}/${type}`,
      { method: 'POST', body: frozen.value },
    )
    modal.value = undefined
    notice.value =
      type === 'create'
        ? 'MES 完工需求已接收。'
        : type === 'receive'
          ? '实物收货已记账，成品等待检验。'
          : type === 'quality'
            ? 'QMS 判定已记录。'
            : '上架完成，合格成品已计入可用库存。'
    await load()
  })
}
onMounted(() =>
  perform(async () => {
    if (!allowed('read')) throw new Error('没有成品入库查询权限')
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
    const requested = Number(
      new URLSearchParams(window.location.search).get('warehouseId'),
    )
    if (requested && !warehouses.value.some((w) => w.id === requested))
      throw new Error('没有所选成品仓权限')
    warehouse.value = requested || warehouses.value[0]?.id || 0
    if (
      props.authorities.includes('ROLE_ADMIN') ||
      props.authorities.includes('integration:simulate')
    ) {
      simulator.value = await request<{ enabled: boolean }>(
        '/api/local/finished-receipts/capabilities',
      )
        .then((r) => r.enabled)
        .catch(() => false)
      if (simulator.value)
        materials.value = await request('/api/master-data/materials')
    }
    await load()
    const receiptId = new URLSearchParams(window.location.search).get(
      'receiptId',
    )
    if (receiptId) {
      if (!/^[1-9][0-9]*$/.test(receiptId)) throw Error('成品入库编号无效')
      await showDetail(Number(receiptId))
    }
  }),
)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>成品入库</h1>
        <p class="muted">
          按工单接收完工成品，经检验合格并上架后才计入可用库存。
        </p>
      </div>
      <button
        v-if="simulator"
        :disabled="busy || !warehouse"
        @click="open('create')"
      >
        模拟 MES 完工需求
      </button>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
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
    <p v-if="!busy && !error && !warehouse">
      请先维护成品仓、待检位与正式库位，并配置仓库权限。
    </p>
    <section class="panel">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>入库单 / 工单</th>
              <th>成品 / 批次 / 数量</th>
              <th>状态 / 库位</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in result?.items" :key="r.id">
              <td>
                成品入库 FG-{{ r.id }}<small>工单 {{ r.work_order_no }}</small>
                <small>{{
                  r.completion_report_id
                    ? `完工报告 ${r.completion_report_no || '编号未提供'}`
                    : '模拟 MES 来源'
                }}</small>
              </td>
              <td>
                {{ identity(r.material_name, r.material_code)
                }}<small
                  >{{ r.batch_no }} · {{ displayQuantity(r.amount) }}
                  {{ r.unit }}</small
                >
              </td>
              <td>
                {{ names[r.status]
                }}<small>{{ r.stored_location || r.received_location }}</small
                ><small>{{ r.quality_reason }}</small>
              </td>
              <td>
                <div class="row-actions">
                  <button
                    :disabled="busy || detailLoading"
                    @click="showDetail(r.id)"
                  >
                    查看详情
                  </button>
                  <button
                    v-if="r.status === 'OPEN' && allowed('receive')"
                    :disabled="busy"
                    @click="open('receive', r)"
                  >
                    确认实物收货</button
                  ><button
                    v-if="r.status === 'RECEIVED' && simulator"
                    :disabled="busy"
                    @click="open('quality', r)"
                  >
                    模拟 QMS 判定</button
                  ><button
                    v-if="r.status === 'QUALIFIED' && allowed('putaway')"
                    :disabled="busy"
                    @click="open('putaway', r)"
                  >
                    确认上架</button
                  ><button :disabled="busy" @click="open('feedback', r)">
                    {{
                      r.completion_report_id ? '模拟 QMS 检验请求' : '反馈记录'
                    }}</button
                  ><RouterLink
                    v-if="r.stored_balance_id"
                    :to="`/inventory?warehouseId=${warehouse}&balanceId=${r.stored_balance_id}`"
                    >库存追溯</RouterLink
                  >
                </div>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="4">
                {{
                  busy
                    ? '正在读取成品入库…'
                    : error
                      ? '查询失败，请重试'
                      : '暂无成品入库需求'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <ResultPager
        v-if="result"
        :total="result.totalElements"
        :page="result.page"
        :pages="result.totalPages"
        :disabled="busy"
        @change="(p) => perform(() => load(p))"
      />
    </section>
    <p v-if="detailLoading" role="status">正在读取成品入库详情…</p>
    <p v-if="detailError" class="error" role="alert">
      详情读取失败：{{ detailError }}
    </p>
    <section
      v-if="detail"
      class="panel"
      style="padding: 20px"
      aria-label="成品入库详情"
    >
      <RecordHeading
        :title="`成品入库 FG-${detail.id}`"
        :subtitle="identity(detail.material_name, detail.material_code)"
        :status="names[detail.status] || detail.status"
      >
        <button @click="closeDetail">收起详情</button>
      </RecordHeading>
      <dl class="record-facts">
        <div>
          <dt>来源工单</dt>
          <dd>{{ detail.work_order_no }}</dd>
        </div>
        <div>
          <dt>
            {{ detail.completion_report_id ? '完工报告' : '模拟 MES 需求' }}
          </dt>
          <dd>
            {{
              detail.completion_report_id
                ? detail.completion_report_no || '编号未提供'
                : detail.demand_no
            }}
          </dd>
        </div>
        <div>
          <dt>目标仓库</dt>
          <dd>
            {{
              warehouses.find((w) => w.id === detail?.warehouse_id)?.name ||
              '名称未提供'
            }}
          </dd>
        </div>
        <div>
          <dt>本单数量</dt>
          <dd>{{ displayQuantity(detail.amount) }} {{ detail.unit }}</dd>
        </div>
        <div>
          <dt>批次</dt>
          <dd>{{ detail.batch_no }}</dd>
        </div>
        <div>
          <dt>生产日期</dt>
          <dd>{{ businessDate(detail.production_date) }}</dd>
        </div>
        <div>
          <dt>有效期</dt>
          <dd>{{ businessDate(detail.expiry_date) }}</dd>
        </div>
        <div>
          <dt>Date Code</dt>
          <dd>{{ detail.date_code || '未填写' }}</dd>
        </div>
      </dl>
      <section class="record-section" aria-label="成品入库执行进度">
        <h3>执行进度</h3>
        <dl class="record-facts">
          <div>
            <dt>入库需求登记</dt>
            <dd>
              {{ detail.created_by }} · {{ timestamp(detail.created_at) }}
            </dd>
          </div>
          <div>
            <dt>实物收货</dt>
            <dd>
              {{
                detail.received_by
                  ? `${identity(detail.received_location, detail.received_location_code)} · ${detail.received_by}`
                  : '未收货'
              }}<br />{{ timestamp(detail.received_at) }}
            </dd>
          </div>
          <div>
            <dt>质检判定</dt>
            <dd>
              {{
                detail.quality_at
                  ? detail.status === 'REJECTED'
                    ? '不合格'
                    : '合格'
                  : '未判定'
              }}
              · {{ detail.quality_by || '—' }}<br />{{
                timestamp(detail.quality_at)
              }}<br />{{ detail.quality_reason || '—' }}
            </dd>
          </div>
          <div>
            <dt>上架</dt>
            <dd>
              {{
                detail.stored_by
                  ? `${identity(detail.stored_location, detail.stored_location_code)} · ${detail.stored_by}`
                  : '未上架'
              }}<br />{{ timestamp(detail.stored_at) }}
            </dd>
          </div>
        </dl>
        <p class="hint">
          本单记录的是入库过程。已上架数量不等于当前库存，后续领用和出库请查看库存流水。库位名称为当前主数据名称。
        </p>
        <details class="record-section">
          <summary>来源标识</summary>
          <p>接入需求号：{{ detail.demand_no }}</p>
          <p v-if="detail.completion_report_id">
            完工报告内部引用：{{ detail.completion_report_id }}
          </p>
        </details>
        <RouterLink
          v-if="
            detail.stored_balance_id &&
            (authorities.includes('ROLE_ADMIN') ||
              authorities.includes('wms:inventory:read'))
          "
          :to="`/inventory?warehouseId=${detail.warehouse_id}&balanceId=${detail.stored_balance_id}`"
          >查看对应库存与流水</RouterLink
        >
      </section>
    </section>
    <div v-if="modal" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="处理成品入库"
      >
        <h2>
          {{
            modal === 'create'
              ? '模拟 MES 完工需求'
              : modal === 'receive'
                ? '确认实物收货'
                : modal === 'quality'
                  ? '模拟 QMS 判定'
                  : modal === 'putaway'
                    ? '确认合格成品上架'
                    : '外部反馈记录'
          }}
        </h2>
        <p v-if="row">
          成品入库 FG-{{ row.id }} · 工单 {{ row.work_order_no }} ·
          {{ row.material_name }} · {{ row.batch_no }} ·
          {{ displayQuantity(row.amount) }}
          {{ row.unit }}
        </p>
        <template v-if="modal === 'feedback'"
          ><p>已发布表示消息已交给消息服务，接收时间表示模拟系统已接收。</p>
          <ul>
            <li v-for="f in feedback" :key="f.message_id">
              {{ events[f.event_type] }} · {{ names[f.status] }} ·
              {{
                f.received_at
                  ? f.target_system + ' 已接收 ' + f.received_at
                  : '尚未接收'
              }}
            </li>
          </ul>
          <p v-if="!feedback.length">尚未生成反馈</p>
          <button @click="modal = undefined">关闭</button></template
        >
        <form v-else @submit.prevent="submit">
          <fieldset :disabled="busy || !!frozen">
            <template v-if="modal === 'create'"
              ><label
                >MES 完工需求号<input
                  v-model="demandNo"
                  required
                  maxlength="64"
                  pattern="[A-Za-z0-9_-]+" /></label
              ><label
                >生产工单号<input
                  v-model="workOrder"
                  required
                  maxlength="64" /></label
              ><label
                >成品物料<select v-model="material" required>
                  <option v-for="m in materials" :key="m.id" :value="m.id">
                    {{ m.code }} · {{ m.name }}
                  </option>
                </select></label
              ><label
                >完工数量<input
                  v-model="amount"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?" /></label
              ><label
                >成品批次<input
                  v-model="batch"
                  required
                  maxlength="64" /></label
              ><label
                >Date Code<input v-model="dateCode" maxlength="32" /></label
              ><label
                >生产日期<input
                  v-model="productionDate"
                  type="date"
                  required /></label
              ><label>有效期<input v-model="expiry" type="date" /></label>
              <p>仅接收入库需求；仓管确认实物后才增加待检库存。</p></template
            ><template v-else-if="modal === 'quality'"
              ><label
                >QMS 事件号<input
                  v-model="eventNo"
                  required
                  maxlength="64"
                  pattern="[A-Za-z0-9_-]+" /></label
              ><label
                >整批检验结果<select v-model="quality">
                  <option value="QUALIFIED">合格</option>
                  <option value="REJECTED">不合格隔离</option>
                </select></label
              ><label
                >检验依据<textarea
                  v-model="reason"
                  required
                  maxlength="500"
                /></label></template
            ><label v-else
              >目标库位<select v-model="location" required>
                <option v-for="l in options" :key="l.id" :value="l.id">
                  {{ l.name }}
                </option>
              </select></label
            ><label v-if="modal !== 'create'" class="check"
              ><input
                v-model="ack"
                type="checkbox"
                required
              />已核对工单、批次、数量及{{
                modal === 'quality' ? '检验结果' : '实物和目标库位'
              }}，确认整单处理</label
            >
          </fieldset>
          <p v-if="frozen">
            请求内容已锁定，请保留原请求重试或关闭后刷新核对。
          </p>
          <p v-if="error" class="error">{{ error }}</p>
          <div class="row-actions">
            <button type="button" :disabled="busy" @click="modal = undefined">
              关闭</button
            ><button
              :disabled="
                busy ||
                (modal !== 'create' && !ack) ||
                (['receive', 'putaway'].includes(modal) && !location)
              "
            >
              提交成品处理
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
  min-width: 850px;
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
