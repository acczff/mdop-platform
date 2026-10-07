<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { request, type Page } from '../api'
import ProductionReversals from '../components/ProductionReversals.vue'
const props = defineProps<{ authorities: string[]; username?: string }>()
const route = useRoute()
interface Issue {
  id: number
  warehouse_id: number
  work_order_no: string
  material_name: string
  unit: string
  status: string
  quantity: string
  batch_no: string
  target_warehouse_name: string
  target_location: string
  consumed_qty: string
  returned_qty: string
  remaining_qty: string
  pending_return_qty: string
  consumable_qty: string
}
interface RecordRow {
  id: number
  event_no: string
  quantity: string
  status?: string
  quality_status?: string
  target_location_id?: number
  reason?: string
  cancel_reason?: string
  reported_by?: string
  closed_by?: string
}
interface Location {
  id: number
  warehouseId: number
  areaType: string
  name: string
}
interface Feedback {
  message_id: string
  event_type: string
  status: string
  received_at: string | null
  attempts: number
  last_error: string | null
}
const id = ref(''),
  issue = ref<Issue>(),
  consumptions = ref<Page<RecordRow>>(),
  returns = ref<Page<RecordRow>>(),
  feedback = ref<Feedback[]>([]),
  locations = ref<Location[]>([])
const busy = ref(false),
  error = ref(''),
  notice = ref(''),
  simulator = ref(false)
const action = ref<'consume' | 'request' | 'confirm' | 'cancel'>(),
  selected = ref<RecordRow>(),
  eventNo = ref(''),
  quantity = ref(''),
  quality = ref('QUALIFIED'),
  locationId = ref(0),
  reason = ref(''),
  ack = ref(false),
  frozen = ref<string>()
const destinationOptions = computed(() =>
  locations.value.filter(
    (l) =>
      l.warehouseId === issue.value?.warehouse_id &&
      l.areaType === (quality.value === 'QUALIFIED' ? 'STORAGE' : 'INSPECTION'),
  ),
)
const statuses: Record<string, string> = {
  PENDING: '待确认实物',
  RETURNED: '已退回',
  CANCELLED: '已取消',
  PUBLISHED: '已发布',
  PUBLISHING: '发布中',
  FAILED: '失败待重试',
  DEAD: '死信',
}
const eventNames: Record<string, string> = {
  ProductionConsumptionReversed: '消耗冲正反馈',
  ProductionReturnReversed: '退料冲正反馈',
  MaterialIssued: '发料结果',
  ProductionConsumed: '消耗记账结果',
  ProductionMaterialReturned: '退料结果',
}
function allowed(p: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:production:${p}`)
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
async function records(isReturn: boolean, page = 0) {
  const value = await request<Page<RecordRow>>(
    `/api/v1/wms/production/issues/${issue.value!.id}/records?returns=${isReturn}&page=${page}&size=20`,
  )
  if (isReturn) returns.value = value
  else consumptions.value = value
}
async function load() {
  issue.value = undefined
  consumptions.value = undefined
  returns.value = undefined
  feedback.value = []
  if (!/^[1-9][0-9]*$/.test(id.value)) throw new Error('请输入有效的领料单号')
  const current = await request<Issue>(
    `/api/v1/wms/production/issues/${id.value}`,
  )
  if (current.status !== 'ISSUED')
    throw new Error('仅已发料单可进入生产消耗与退料')
  const [currentConsumptions, currentReturns, currentFeedback] =
    await Promise.all([
      request<Page<RecordRow>>(
        `/api/v1/wms/production/issues/${current.id}/records?returns=false&page=0&size=20`,
      ),
      request<Page<RecordRow>>(
        `/api/v1/wms/production/issues/${current.id}/records?returns=true&page=0&size=20`,
      ),
      request<Feedback[]>(
        `/api/integration/material-issues/${current.id}/feedback`,
      ),
    ])
  // Publish one complete issue snapshot; late results from failed loads cannot mutate it.
  issue.value = current
  consumptions.value = currentConsumptions
  returns.value = currentReturns
  feedback.value = currentFeedback
}
async function open(
  type: 'consume' | 'request' | 'confirm' | 'cancel',
  row?: RecordRow,
) {
  await perform(async () => {
    if (type === 'request' || type === 'confirm')
      locations.value = await request('/api/master-data/locations')
    action.value = type
    selected.value = row
    eventNo.value = ''
    quantity.value = ''
    quality.value = 'QUALIFIED'
    locationId.value = destinationOptions.value[0]?.id || 0
    reason.value = ''
    ack.value = false
    frozen.value = undefined
  })
}
function chooseQuality() {
  locationId.value = destinationOptions.value[0]?.id || 0
}
async function submit() {
  if (!issue.value || !action.value) return
  const type = action.value
  if (['consume', 'confirm'].includes(type) && !ack.value) return
  frozen.value ??= JSON.stringify(
    type === 'consume'
      ? {
          eventNo: eventNo.value,
          issueId: issue.value.id,
          workOrderNo: issue.value.work_order_no,
          quantity: quantity.value,
        }
      : type === 'request'
        ? {
            eventNo: eventNo.value,
            issueId: issue.value.id,
            workOrderNo: issue.value.work_order_no,
            quantity: quantity.value,
            qualityStatus: quality.value,
            targetLocationId: locationId.value,
            reason: reason.value,
          }
        : type === 'cancel'
          ? { reason: reason.value }
          : {},
  )
  const path =
    type === 'consume'
      ? '/api/local/mes-production/consumptions'
      : type === 'request'
        ? '/api/local/mes-production/returns'
        : `/api/v1/wms/production/returns/${selected.value!.id}/${type}`
  await perform(async () => {
    await request(path, { method: 'POST', body: frozen.value })
    action.value = undefined
    notice.value =
      type === 'consume'
        ? '消耗已记账，线边库存已扣减。'
        : type === 'request'
          ? '退料需求已接收，额度已保留，等待仓管确认实物。'
          : type === 'cancel'
            ? '退料已取消，待退额度已释放。'
            : '退料已记账，已生成反馈。'
    await load()
  })
}
onMounted(() =>
  perform(async () => {
    if (!allowed('read')) throw new Error('没有生产库存查询权限')
    if (props.authorities.includes('ROLE_ADMIN'))
      simulator.value = await request<{ enabled: boolean }>(
        '/api/local/mes-production/capabilities',
      )
        .then((r) => r.enabled)
        .catch(() => false)
    if (route.query.issueId) {
      id.value = String(route.query.issueId)
      await load()
    }
  }),
)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>生产消耗与退料</h1>
        <p class="muted">按领料单核对已消耗、待退和剩余线边物料。</p>
      </div>
      <RouterLink to="/issues">选择领料单</RouterLink>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <form class="panel filters" @submit.prevent="perform(load)">
      <label
        >领料单号<input
          v-model="id"
          required
          inputmode="numeric"
          pattern="[1-9][0-9]*"
          :disabled="busy"
          placeholder="输入 MI- 后的编号" /></label
      ><button :disabled="busy">查询</button>
    </form>
    <template v-if="issue">
      <section class="panel panel-body">
        <h2>MI-{{ issue.id }} · 工单 {{ issue.work_order_no }}</h2>
        <p>
          {{ issue.material_name }} · 批次 {{ issue.batch_no || '无' }} ·
          {{ issue.target_warehouse_name }} / {{ issue.target_location }}
        </p>
        <dl class="metrics">
          <div>
            <dt>已领用</dt>
            <dd>{{ issue.quantity }} {{ issue.unit }}</dd>
          </div>
          <div>
            <dt>已消耗</dt>
            <dd>{{ issue.consumed_qty }}</dd>
          </div>
          <div>
            <dt>已退回</dt>
            <dd>{{ issue.returned_qty }}</dd>
          </div>
          <div>
            <dt>线边剩余</dt>
            <dd>{{ issue.remaining_qty }}</dd>
          </div>
          <div>
            <dt>待退占用</dt>
            <dd>{{ issue.pending_return_qty }}</dd>
          </div>
          <div>
            <dt>可消耗 / 可申请退料</dt>
            <dd>{{ issue.consumable_qty }}</dd>
          </div>
        </dl>
        <p class="muted">
          线边剩余保留工单归属，普通移库和盘点不能动用；已消耗数量不能退回。
        </p>
        <div v-if="simulator" class="row-actions">
          <button :disabled="busy" @click="open('consume')">
            模拟 MES 消耗</button
          ><button :disabled="busy" @click="open('request')">
            模拟 MES 退料需求
          </button>
        </div>
      </section>
      <section class="panel panel-body">
        <h2>退料记录</h2>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>单据 / 状态</th>
                <th>数量 / 质量</th>
                <th>目标库位 / 原因</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in returns?.items" :key="row.id">
                <td>
                  PR-{{ row.id }}<small>{{ row.event_no }}</small
                  ><small>{{ statuses[row.status!] }}</small>
                </td>
                <td>
                  {{ row.quantity }} {{ issue.unit
                  }}<small>{{
                    row.quality_status === 'QUALIFIED'
                      ? '合格余料'
                      : '不合格隔离'
                  }}</small>
                </td>
                <td>
                  #{{ row.target_location_id }}<small>{{ row.reason }}</small
                  ><small>{{ row.cancel_reason }}</small>
                </td>
                <td>
                  <div class="row-actions">
                    <button
                      v-if="row.status === 'PENDING' && allowed('confirm')"
                      :disabled="busy"
                      @click="open('confirm', row)"
                    >
                      确认实物退回</button
                    ><button
                      v-if="row.status === 'PENDING' && allowed('cancel')"
                      :disabled="busy"
                      @click="open('cancel', row)"
                    >
                      取消退料</button
                    ><span v-if="row.status !== 'PENDING'">{{
                      row.closed_by
                    }}</span>
                  </div>
                </td>
              </tr>
              <tr v-if="!returns?.items.length">
                <td colspan="4">暂无退料记录</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="returns" class="pager">
          <span>共 {{ returns.totalElements }} 条</span
          ><button
            :disabled="busy || returns.page === 0"
            @click="perform(() => records(true, returns!.page - 1))"
          >
            较新退料</button
          ><button
            :disabled="busy || returns.page + 1 >= returns.totalPages"
            @click="perform(() => records(true, returns!.page + 1))"
          >
            较早退料
          </button>
        </div>
      </section>
      <section class="panel panel-body">
        <h2>实际消耗记录</h2>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>记录 / MES 事件</th>
                <th>消耗数量</th>
                <th>记录人</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in consumptions?.items" :key="row.id">
                <td>
                  PC-{{ row.id }}<small>{{ row.event_no }}</small>
                </td>
                <td>{{ row.quantity }} {{ issue.unit }}</td>
                <td>{{ row.reported_by }}</td>
              </tr>
              <tr v-if="!consumptions?.items.length">
                <td colspan="3">暂无消耗记录</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="consumptions" class="pager">
          <span>共 {{ consumptions.totalElements }} 条</span
          ><button
            :disabled="busy || consumptions.page === 0"
            @click="perform(() => records(false, consumptions!.page - 1))"
          >
            较新消耗</button
          ><button
            :disabled="busy || consumptions.page + 1 >= consumptions.totalPages"
            @click="perform(() => records(false, consumptions!.page + 1))"
          >
            较早消耗
          </button>
        </div>
      </section>
      <section class="panel panel-body">
        <h2>MES 反馈状态</h2>
        <p class="muted">
          已发布表示消息已交给消息服务；显示接收时间后，才代表模拟 MES 已接收。
        </p>
        <ul>
          <li v-for="f in feedback" :key="f.message_id">
            {{ eventNames[f.event_type] || f.event_type }} ·
            {{
              f.status === 'PENDING' ? '待发布' : statuses[f.status] || f.status
            }}
            ·
            {{
              f.received_at
                ? '模拟 MES 已接收：' + f.received_at
                : '模拟 MES 尚未接收'
            }}<small v-if="f.last_error"
              >失败类型：{{ f.last_error }} · 尝试 {{ f.attempts }} 次</small
            >
          </li>
        </ul>
        <RouterLink
          v-if="props.authorities.includes('ROLE_ADMIN')"
          to="/messages"
          >查看消息记录与失败重放</RouterLink
        >
      </section>
      <ProductionReversals
        :key="issue.id"
        :issue-id="issue.id"
        :username="username"
        :authorities="authorities"
        :consumptions="consumptions?.items || []"
        :returns="returns?.items || []"
        @changed="perform(load)"
      />
    </template>
    <p v-else class="muted">从生产领料的已发料单进入，或输入领料单号查询。</p>
    <div v-if="action && issue" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="处理生产业务"
      >
        <h2>
          {{
            action === 'consume'
              ? '模拟 MES 实际消耗'
              : action === 'request'
                ? '模拟 MES 退料需求'
                : action === 'confirm'
                  ? '确认实物退回'
                  : '取消退料'
          }}
        </h2>
        <p>
          MI-{{ issue.id }} · 工单 {{ issue.work_order_no }} ·
          {{ issue.material_name }} · 批次 {{ issue.batch_no || '无' }}
        </p>
        <form @submit.prevent="submit">
          <fieldset :disabled="busy || !!frozen">
            <template v-if="action === 'consume' || action === 'request'"
              ><label
                >MES 事件号<input
                  v-model="eventNo"
                  required
                  maxlength="64"
                  pattern="[A-Za-z0-9_-]+" /></label
              ><label
                >数量<input
                  v-model="quantity"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
              /></label>
              <p>
                当前可用业务额度：{{ issue.consumable_qty }} {{ issue.unit }}
              </p></template
            >
            <template v-if="action === 'request'"
              ><label
                >退料质量<select v-model="quality" @change="chooseQuality">
                  <option value="QUALIFIED">合格余料</option>
                  <option value="REJECTED">不合格 / 过期隔离</option>
                </select></label
              ><label
                >退回库位<select v-model="locationId" required>
                  <option
                    v-for="l in destinationOptions"
                    :key="l.id"
                    :value="l.id"
                  >
                    {{ l.name }}
                  </option>
                </select></label
              ><label
                >退料原因<textarea v-model="reason" required maxlength="500" />
              </label>
              <p>申请仅保留退料额度；仓管确认实物后才移动库存。</p></template
            >
            <label v-if="action === 'consume'" class="check"
              ><input v-model="ack" type="checkbox" required />确认这是模拟 MES
              回传的实际消耗结果，将扣减线边库存</label
            >
            <template v-if="action === 'confirm'"
              ><p>
                退料 PR-{{ selected!.id }} · {{ selected!.quantity }}
                {{ issue.unit }} ·
                {{
                  selected!.quality_status === 'QUALIFIED'
                    ? '合格余料'
                    : '不合格隔离'
                }}
              </p>
              <p>
                退回：{{
                  locations.find((l) => l.id === selected!.target_location_id)
                    ?.name || '#' + selected!.target_location_id
                }}。不合格退料不计可用库存。
              </p>
              <label class="check"
                ><input
                  v-model="ack"
                  type="checkbox"
                  required
                />已核对实物、批次、数量、质量与目标库位，确认退回</label
              ></template
            >
            <label v-if="action === 'cancel'"
              >取消原因<textarea v-model="reason" required maxlength="500" />
            </label>
          </fieldset>
          <p v-if="frozen" class="muted">
            内容已锁定；保留原请求重试，或关闭后刷新核对。
          </p>
          <p v-if="error" role="alert" class="error">{{ error }}</p>
          <div class="row-actions">
            <button type="button" :disabled="busy" @click="action = undefined">
              关闭</button
            ><button
              class="primary"
              :disabled="
                busy ||
                (['consume', 'confirm'].includes(action) && !ack) ||
                (action === 'request' && !locationId)
              "
            >
              {{
                action === 'consume'
                  ? '记录消耗'
                  : action === 'request'
                    ? '接收退料需求'
                    : action === 'confirm'
                      ? '确认退料记账'
                      : '确认取消'
              }}
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
.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(155px, 1fr));
  gap: 16px;
}
.metrics dt {
  color: #777083;
  font-size: 13px;
}
.metrics dd {
  margin: 8px 0;
  font-size: 18px;
  font-weight: 600;
}
.row-actions,
.pager {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.pager {
  margin-top: 14px;
}
small {
  display: block;
  margin-top: 5px;
}
table {
  min-width: 700px;
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
