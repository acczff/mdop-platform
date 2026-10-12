<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
import { quantity } from '../presentation'
import OperationRecord from './OperationRecord.vue'
import OperationTimeNote from './OperationTimeNote.vue'
const props = defineProps<{
  orderId: number
  username: string
  authorities: string[]
}>()
const emit = defineEmits<{ closed: [] }>()
interface Data {
  orderNo: string
  orderStatus: string
  orderVersion: number
  factHash: string
  canClose: boolean
  selfReview: boolean
  closureMatches: boolean
  costStatus: string
  facts: {
    authorized: string
    approved: string
    stored: string
    unit: string
    hasVariance: boolean
    blockers: string[]
    materials: {
      materialId: number
      code: string
      name: string
      unit: string
      theoretical: string
      consumed: string
      returned: string
      issued: string
      variance: string
    }[]
  }
  closure: {
    reason: string
    variance_reason: string
    closed_by: string
    closed_at: string
  } | null
}
const base = `/api/v1/manufacturing/orders/${props.orderId}/closure`
const storageKey = `mdop-production-closure:${props.username}:${props.orderId}`
const data = ref<Data>(),
  pending = ref<string>(),
  busy = ref(false),
  loading = ref(false),
  locked = ref(false)
const error = ref(''),
  reason = ref(''),
  varianceReason = ref(''),
  ack = ref(false)
const review = computed(() =>
  props.authorities.includes('manufacturing:review'),
)
const blocked = computed(
  () =>
    busy.value ||
    loading.value ||
    locked.value ||
    !!pending.value ||
    !data.value,
)
let alive = true,
  generation = 0
async function load() {
  if (busy.value) return
  const token = ++generation
  data.value = undefined
  loading.value = true
  error.value = ''
  ack.value = false
  try {
    const d = await request<Data>(base)
    if (alive && token === generation) data.value = d
  } catch (e) {
    if (alive && token === generation)
      error.value = `读取结束核对失败：${(e as Error).message}`
  } finally {
    if (alive && token === generation) loading.value = false
  }
}
function remember(value?: string) {
  if (value) sessionStorage.setItem(storageKey, value)
  else sessionStorage.removeItem(storageKey)
  pending.value = value
}
async function close() {
  if (
    blocked.value ||
    !review.value ||
    !data.value?.canClose ||
    data.value.selfReview ||
    !ack.value
  )
    return
  if (
    !reason.value.trim() ||
    (data.value.facts.hasVariance && !varianceReason.value.trim())
  ) {
    error.value = '请填写结束依据；存在材料差异时还需填写差异说明'
    return
  }
  try {
    remember(
      JSON.stringify({
        idempotencyKey: crypto.randomUUID(),
        version: data.value.orderVersion,
        factHash: data.value.factHash,
        reason: reason.value.trim(),
        varianceReason: varianceReason.value.trim(),
      }),
    )
  } catch {
    locked.value = true
    error.value = '无法保存重试凭据，停止新操作'
    return
  }
  await retry()
}
async function retry() {
  if (!pending.value || busy.value || locked.value || !review.value) return
  ++generation
  loading.value = false
  busy.value = true
  error.value = ''
  try {
    const d = await request<Data>(base, { method: 'POST', body: pending.value })
    if (!alive) return
    remember()
    data.value = d
    ack.value = false
    emit('closed')
  } catch (e) {
    if (!alive) return
    if (
      e instanceof ApiError &&
      e.status >= 400 &&
      e.status < 500 &&
      ![401, 403].includes(e.status)
    ) {
      try {
        remember()
      } catch {
        locked.value = true
      }
      data.value = undefined
      error.value = `操作被拒绝：${e.message}。请刷新核对。`
    } else
      error.value = `结果待核对：${(e as Error).message}。请保留原请求重试。`
  } finally {
    if (alive) busy.value = false
  }
}
onMounted(async () => {
  try {
    const saved = sessionStorage.getItem(storageKey)
    if (saved) {
      const p = JSON.parse(saved)
      if (
        typeof p.idempotencyKey !== 'string' ||
        typeof p.factHash !== 'string' ||
        !Number.isInteger(p.version) ||
        typeof p.reason !== 'string' ||
        typeof p.varianceReason !== 'string'
      )
        throw Error('invalid')
      pending.value = saved
    }
  } catch {
    locked.value = true
    error.value = '重试凭据无法读取，停止新操作，请核对原记录'
    return
  }
  await load()
})
onBeforeUnmount(() => {
  alive = false
  ++generation
})
</script>

<template>
  <section class="closure-panel" aria-label="工单结束核对">
    <div class="closure-header">
      <h3>结束核对</h3>
      <button :disabled="busy || loading" @click="load">刷新核对</button>
    </div>
    <p v-if="loading" role="status">正在核对材料、采购和成品事实…</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="pending" role="status">
      <p>有结果待核对的结束请求，请保留原请求。</p>
      <button :disabled="busy || locked || !review" @click="retry">
        原样重试结束请求
      </button>
    </div>
    <template v-if="data">
      <p v-if="data.closure && data.closureMatches" class="notice">
        工单已完成，结束记录与当前业务事实一致。
      </p>
      <p v-else-if="data.closure" role="alert" class="error">
        结束后事实发生变化，待核对；原结束记录已保留。
      </p>
      <p v-else-if="data.canClose" class="notice">
        数量流程已收尾，可核对材料差异并确认结束。
      </p>
      <dl class="closure-metrics">
        <div>
          <dt>授权生产</dt>
          <dd>{{ quantity(data.facts.authorized) }} {{ data.facts.unit }}</dd>
        </div>
        <div>
          <dt>复核完工</dt>
          <dd>{{ quantity(data.facts.approved) }} {{ data.facts.unit }}</dd>
        </div>
        <div>
          <dt>合格上架</dt>
          <dd>{{ quantity(data.facts.stored) }} {{ data.facts.unit }}</dd>
        </div>
      </dl>
      <div v-if="data.facts.blockers.length" class="notice">
        <strong>尚不能结束</strong>
        <ul>
          <li v-for="item in data.facts.blockers" :key="item">{{ item }}</li>
        </ul>
      </div>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>材料</th>
              <th>理论用量</th>
              <th>实际领料</th>
              <th>净消耗</th>
              <th>净退回</th>
              <th>消耗差异</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="line in data.facts.materials" :key="line.materialId">
              <td>
                {{ line.code }} · {{ line.name }}<small>{{ line.unit }}</small>
              </td>
              <td>{{ quantity(line.theoretical) }}</td>
              <td>{{ quantity(line.issued) }}</td>
              <td>{{ quantity(line.consumed) }}</td>
              <td>{{ quantity(line.returned) }}</td>
              <td>{{ quantity(line.variance) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted">
        差异 = 净消耗 − 理论用量。结束确认不会补扣材料或改变库存。成本：{{
          data.costStatus
        }}。
      </p>
      <template v-if="data.closure">
        <OperationRecord
          title="确认结束"
          :account="data.closure.closed_by"
          :at="data.closure.closed_at"
          :note="data.closure.reason"
          note-label="结束依据"
        />
        <p v-if="data.closure.variance_reason">
          材料差异说明：{{ data.closure.variance_reason }}
        </p>
        <OperationTimeNote />
      </template>
      <p v-else-if="data.selfReview && review" class="muted">
        本人经办业务须由另一名生产审核员确认结束。
      </p>
      <form v-else-if="review && data.canClose" @submit.prevent="close">
        <fieldset :disabled="blocked">
          <label
            >结束依据<textarea
              v-model="reason"
              aria-label="结束依据"
              maxlength="500"
              required
            />
          </label>
          <label v-if="data.facts.hasVariance"
            >材料差异说明<textarea
              v-model="varianceReason"
              aria-label="材料差异说明"
              maxlength="500"
              required
            />
          </label>
          <label class="ack"
            ><input
              v-model="ack"
              type="checkbox"
            />已核对真实材料与成品事实，确认结束后不能追加生产作业</label
          >
          <button class="primary" :disabled="!ack">确认结束工单</button>
        </fieldset>
      </form>
    </template>
  </section>
</template>

<style scoped>
.closure-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.closure-panel {
  min-width: 0;
  border-top: 1px solid #e4e7ec;
  padding-top: 16px;
}
.closure-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 16px 0;
}
.closure-metrics div {
  padding: 12px;
  background: #f8fafc;
  border-radius: 6px;
}
.closure-metrics dt {
  color: #667085;
  font-size: 13px;
}
.closure-metrics dd {
  margin: 6px 0 0;
  font-weight: 600;
}
small {
  display: block;
}
fieldset {
  display: grid;
  gap: 12px;
  border: 0;
  padding: 0;
}
label {
  display: grid;
  gap: 6px;
}
textarea {
  width: 100%;
  min-height: 64px;
  box-sizing: border-box;
}
.ack {
  display: flex;
  align-items: flex-start;
}
td {
  white-space: nowrap;
}
</style>
