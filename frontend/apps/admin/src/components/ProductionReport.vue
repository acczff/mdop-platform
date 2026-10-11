<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
const props = defineProps<{
  issueId: number
  workOrderNo: string
  warehouseId: number
  username: string
  authorities: string[]
}>()
const emit = defineEmits<{ changed: [] }>()
type Pending = { kind: 'consumptions' | 'returns'; body: string }
type Location = {
  id: number
  warehouseId: number
  areaType: string
  name: string
}
const kind = ref<'consumptions' | 'returns'>('consumptions'),
  quantity = ref(''),
  reason = ref(''),
  quality = ref('QUALIFIED'),
  location = ref(0),
  ack = ref(false)
const locations = ref<Location[]>([]),
  pending = ref<Pending>(),
  busy = ref(false),
  locked = ref(false),
  error = ref(''),
  notice = ref('')
const writable = computed(() =>
  props.authorities.includes('wms:production:report'),
)
const blocked = computed(() => busy.value || locked.value || !!pending.value)
const options = computed(() =>
  locations.value.filter(
    (l) =>
      l.warehouseId === props.warehouseId &&
      l.areaType === (quality.value === 'QUALIFIED' ? 'STORAGE' : 'INSPECTION'),
  ),
)
const storageKey = `mdop-production-report:${props.username}:${props.issueId}`
let generation = 0
function remember(p?: Pending) {
  if (p) sessionStorage.setItem(storageKey, JSON.stringify(p))
  else sessionStorage.removeItem(storageKey)
  pending.value = p
}
async function save() {
  if (blocked.value || !writable.value || !ack.value) return
  if (
    !reason.value.trim() ||
    !/^[0-9]{1,12}(\.[0-9]{1,6})?$/.test(quantity.value) ||
    !/[1-9]/.test(quantity.value)
  ) {
    error.value = '请填写正数数量及登记原因'
    return
  }
  if (
    kind.value === 'returns' &&
    !options.value.some((l) => l.id === location.value)
  ) {
    error.value = '请选择符合质量要求的退回库位'
    return
  }
  const input = {
    eventNo: crypto.randomUUID(),
    issueId: props.issueId,
    workOrderNo: props.workOrderNo,
    quantity: quantity.value,
  }
  const body =
    kind.value === 'consumptions'
      ? { consumption: input, reason: reason.value.trim() }
      : {
          ...input,
          reason: reason.value.trim(),
          qualityStatus: quality.value,
          targetLocationId: location.value,
        }
  try {
    remember({ kind: kind.value, body: JSON.stringify(body) })
  } catch {
    locked.value = true
    error.value = '无法保存重试凭据，请核对浏览器存储后再操作'
    return
  }
  await retry()
}
async function retry() {
  if (busy.value || locked.value || !pending.value || !writable.value) return
  const token = ++generation,
    p = pending.value
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await request(`/api/v1/wms/production/${p.kind}`, {
      method: 'POST',
      body: p.body,
    })
    remember()
    if (token !== generation) return
    quantity.value = ''
    reason.value = ''
    ack.value = false
    notice.value =
      p.kind === 'consumptions'
        ? '实际消耗已登记'
        : '退料已申请，等待仓管确认实物'
    emit('changed')
  } catch (e) {
    if (token !== generation) return
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
      error.value = `登记被拒绝：${e.message}。请核对当前领料记录。`
    } else
      error.value = `结果待核对：${(e as Error).message}。请保留原请求重试。`
  } finally {
    if (token === generation) busy.value = false
  }
}
onMounted(async () => {
  if (!writable.value) return
  const token = ++generation
  try {
    const raw = sessionStorage.getItem(storageKey)
    if (raw) {
      const p = JSON.parse(raw) as Pending
      const body = JSON.parse(p.body)
      const event = p.kind === 'consumptions' ? body.consumption : body
      if (
        !['consumptions', 'returns'].includes(p.kind) ||
        event.issueId !== props.issueId ||
        event.workOrderNo !== props.workOrderNo ||
        typeof event.eventNo !== 'string'
      )
        throw Error('invalid')
      pending.value = p
      error.value = '有结果待核对的登记，请原样重试'
    }
  } catch {
    locked.value = true
    error.value = '重试凭据无法读取，停止新登记，请联系管理员核对'
    return
  }
  try {
    const rows = await request<Location[]>('/api/master-data/locations')
    if (token === generation) locations.value = rows
  } catch (e) {
    if (token === generation)
      error.value = `库位读取失败：${(e as Error).message}，退料前请重新打开本页。`
  }
})
onBeforeUnmount(() => {
  generation++
})
</script>
<template>
  <section v-if="writable" class="production-report" aria-label="正式生产登记">
    <h3>生产登记</h3>
    <p>登记实际消耗或申请余料退回；退料由仓管接收实物后过账。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="pending">
      <p>
        原请求已保存，编号：{{
          JSON.parse(pending.body).consumption?.eventNo ||
          JSON.parse(pending.body).eventNo
        }}
      </p>
      <button :disabled="busy || locked" @click="retry">
        原样重试生产登记
      </button>
    </div>
    <form v-else @submit.prevent="save">
      <fieldset :disabled="blocked">
        <label
          >登记类型<select v-model="kind">
            <option value="consumptions">实际消耗</option>
            <option value="returns">退料申请</option>
          </select></label
        >
        <label
          >数量<input v-model="quantity" required inputmode="decimal"
        /></label>
        <template v-if="kind === 'returns'"
          ><label
            >质量<select v-model="quality" @change="location = 0">
              <option value="QUALIFIED">合格余料</option>
              <option value="REJECTED">不合格 / 过期隔离</option>
            </select></label
          ><label
            >退回库位<select v-model="location" required>
              <option :value="0">请选择</option>
              <option v-for="l in options" :key="l.id" :value="l.id">
                {{ l.name }}
              </option>
            </select></label
          ></template
        >
        <label
          >登记原因<textarea v-model="reason" required maxlength="500" />
        </label>
        <label class="ack"
          ><input
            v-model="ack"
            type="checkbox"
            required
          />已核对工单、材料及实际数量</label
        >
        <button :disabled="!ack">
          {{ kind === 'consumptions' ? '登记实际消耗' : '申请实物退回' }}
        </button>
      </fieldset>
    </form>
  </section>
</template>
<style scoped>
.production-report {
  border-top: 1px solid var(--line);
  margin-top: 20px;
  padding-top: 16px;
}
fieldset {
  border: 0;
  padding: 0;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: end;
}
label {
  display: grid;
  gap: 6px;
  min-width: 140px;
}
input,
select,
textarea {
  min-height: 38px;
}
.ack {
  display: flex;
  align-items: center;
}
[role='alert'] {
  color: #a02632;
}
</style>
