<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request } from '../api'
defineProps<{ authorities: string[] }>()
interface Delivery {
  message_id: string
  status: string
  attempts: number
  last_error: string | null
  created_at: string
}
interface History {
  action: string
  actor: string
  detail: string | null
  occurred_at: string
}
const rows = ref<Delivery[]>([]),
  history = ref<History[]>([])
const results = ref<
  {
    message_id: string
    event_type: string
    aggregate_id: number
    aggregate_type: string
    status: string
    target_system: string | null
    received_at: string | null
  }[]
>([])
const rejections = ref<
  {
    fingerprint: string
    reason: string
    occurrences: number
    received_at: string
  }[]
>([])
const direction = ref('INBOX'),
  status = ref(''),
  page = ref(0)
const busy = ref(false),
  error = ref(''),
  message = ref(''),
  reason = ref(''),
  selected = ref<Delivery>()
const counts = ref<{
  INBOX: { status: string; total: number }[]
  OUTBOX: { status: string; total: number }[]
  unconfirmedResults: number
  stalled: number
  rejected: number
}>()
const names: Record<string, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  PROCESSED: '已处理',
  PUBLISHING: '发布中',
  PUBLISHED: '已发布',
  FAILED: '失败待重试',
  DEAD: '死信',
}
const eventNames: Record<string, string> = {
  ProductionConsumptionReversed: 'MES消耗冲正反馈',
  ProductionReturnReversed: 'MES退料冲正反馈',
  SalesOutboundConfirmed: 'ERP销售出库反馈',
  MaterialIssued: 'MES发料反馈',
  FinishedGoodsReceived: '成品收货反馈',
  FinishedInspectionRequested: '成品检验请求',
  FinishedGoodsPutaway: '成品上架反馈',
  ProductionConsumed: 'MES消耗记账结果',
  ProductionMaterialReturned: 'MES退料反馈',
  PurchaseReturnConfirmed: 'ERP采购退货反馈',
  PurchaseReceiptConfirmed: 'ERP收货反馈',
  IncomingInspectionRequested: 'QMS检验请求',
  PurchaseReceiptReversed: 'ERP收货冲正',
  IncomingInspectionCancelled: 'QMS检验取消',
}
let sequence = 0
async function load() {
  const current = ++sequence
  busy.value = true
  error.value = ''
  selected.value = undefined
  try {
    const [list, summary, feedback, rejected] = await Promise.all([
      request<Delivery[]>(
        `/api/integration/messages?direction=${direction.value}&status=${status.value}&page=${page.value}`,
      ),
      request<typeof counts.value>('/api/integration/messages/summary'),
      request<typeof results.value>('/api/integration/messages/results'),
      request<typeof rejections.value>('/api/integration/messages/rejections'),
    ])
    if (current === sequence) {
      rows.value = list
      counts.value = summary
      results.value = feedback
      rejections.value = rejected
    }
  } catch (e) {
    if (current === sequence) error.value = (e as Error).message
  } finally {
    if (current === sequence) busy.value = false
  }
}
function changeDirection() {
  status.value = ''
  filter()
}
function turnPage(delta: number) {
  page.value += delta
  void load()
}
function filter() {
  page.value = 0
  void load()
}
async function inspect(row: Delivery) {
  selected.value = row
  reason.value = ''
  history.value = []
  error.value = ''
  try {
    const result = await request<History[]>(
      `/api/integration/messages/${direction.value}/${row.message_id}/history`,
    )
    if (selected.value === row) history.value = result
  } catch (e) {
    error.value = (e as Error).message
  }
}
async function replay() {
  if (!selected.value || !reason.value.trim()) return
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await request(
      `/api/integration/messages/${direction.value}/${selected.value.message_id}/replay`,
      { method: 'POST', body: JSON.stringify({ reason: reason.value }) },
    )
    message.value = '已安排重放，沿用原消息编号。请稍后刷新查看结果。'
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>消息管理</h1>
        <p class="muted">
          查看到货接入与收货反馈，处理失败消息并追踪恢复结果。
        </p>
      </div>
      <button :disabled="busy" @click="load">刷新</button>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="counts" class="hint">
      接入消息 {{ counts.INBOX.reduce((n, r) => n + r.total, 0) }} 条 · 收货反馈
      {{ counts.OUTBOX.reduce((n, r) => n + r.total, 0) }} 条 ·
      尚无模拟端接收记录 {{ counts.unconfirmedResults }} 条
    </p>
    <p
      v-if="counts && (counts.stalled || counts.rejected)"
      role="status"
      class="error"
    >
      超过5分钟未完成 {{ counts.stalled }} 条 · 已隔离无效消息
      {{ counts.rejected }} 次。请核对处理记录。
    </p>
    <p class="hint">
      发布成功表示消息已到达队列；ERP/QMS
      模拟接收记录不代表真实系统业务完成，也不代表质检放行。
    </p>
    <div class="toolbar">
      <label
        >方向<select
          v-model="direction"
          :disabled="busy"
          @change="changeDirection"
        >
          <option value="INBOX">ERP 到货接入</option>
          <option value="OUTBOX">收货结果反馈</option>
        </select></label
      >
      <label
        >状态<select v-model="status" :disabled="busy" @change="filter">
          <option value="">全部</option>
          <option
            v-for="s in [
              'PENDING',
              'FAILED',
              'DEAD',
              direction === 'INBOX' ? 'PROCESSING' : 'PUBLISHING',
              direction === 'INBOX' ? 'PROCESSED' : 'PUBLISHED',
            ]"
            :key="s"
            :value="s"
          >
            {{ names[s] }}
          </option>
        </select></label
      >
    </div>
    <p v-if="busy" role="status">正在处理…</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>消息编号</th>
            <th>状态</th>
            <th>尝试次数</th>
            <th>最近错误</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.message_id">
            <td>{{ row.message_id }}</td>
            <td>{{ names[row.status] || row.status }}</td>
            <td>{{ row.attempts }}</td>
            <td>{{ row.last_error || '—' }}</td>
            <td>
              <button :disabled="busy" @click="inspect(row)">查看记录</button>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="5">暂无符合条件的消息</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="toolbar">
      <button :disabled="busy || page === 0" @click="turnPage(-1)">
        上一页</button
      ><span>第 {{ page + 1 }} 页 · 每页最多50条</span
      ><button :disabled="busy || rows.length < 50" @click="turnPage(1)">
        下一页
      </button>
    </div>
    <section v-if="selected" class="panel">
      <h2>处理记录</h2>
      <p>{{ selected.message_id }}</p>
      <form
        v-if="['FAILED', 'DEAD'].includes(selected.status)"
        @submit.prevent="replay"
      >
        <label
          >重放原因<input
            v-model="reason"
            required
            maxlength="200"
            placeholder="修复原因及核对结果" /></label
        ><button class="primary" :disabled="busy || !reason.trim()">
          确认重放
        </button>
      </form>
      <p v-if="!history.length" class="muted">暂无处理记录</p>
      <ul>
        <li v-for="(item, index) in history" :key="index">
          {{ item.occurred_at }} · {{ item.action }} · {{ item.actor }} ·
          {{ item.detail || '—' }}
        </li>
      </ul>
    </section>
    <section class="panel">
      <h2>ERP / QMS 模拟接收对账</h2>
      <p class="hint">
        最近100条收货反馈；尚未接收时先检查发布状态与消费者。检验请求的接收不会改变库存质量状态。
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>业务单据</th>
              <th>反馈类型</th>
              <th>发布状态</th>
              <th>模拟端接收</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in results" :key="item.message_id">
              <td>
                {{
                  item.aggregate_type === 'SalesOrder'
                    ? '销售 SO-'
                    : item.aggregate_type === 'FinishedReceipt'
                      ? '成品 FG-'
                      : item.aggregate_type === 'MaterialIssue'
                        ? '领料 MI-'
                        : '收货 #'
                }}{{ item.aggregate_id }}
              </td>
              <td>
                {{ eventNames[item.event_type] || item.event_type }}
              </td>
              <td>{{ names[item.status] }}</td>
              <td>
                {{
                  item.received_at
                    ? `${item.target_system} · ${item.received_at}`
                    : '尚未接收'
                }}
              </td>
            </tr>
            <tr v-if="!results.length">
              <td colspan="4">暂无业务反馈</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-if="rejections.length" class="panel">
      <h2>已隔离消息</h2>
      <p class="hint">
        无效契约或同一编号内容冲突的消息保留在对应业务死信队列。核对源系统后修正并重新发送，不能直接重放无效内容。
      </p>
      <ul>
        <li v-for="item in rejections" :key="item.fingerprint">
          {{ item.received_at }} · {{ item.reason }} · {{ item.occurrences }} 次
          · 指纹 {{ item.fingerprint }}
        </li>
      </ul>
    </section>
  </main>
</template>
