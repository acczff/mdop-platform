<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { request, type Page } from '../api'
const props = defineProps<{
  issueId: number
  username?: string
  authorities: string[]
  consumptions: { id: number; quantity: string }[]
  returns: { id: number; quantity: string; status?: string }[]
}>()
const emit = defineEmits<{ changed: [] }>()
interface Reversal {
  id: number
  consumption_id: number | null
  production_return_id: number | null
  amount: string
  status: string
  reason: string
  external_reference: string
  created_by: string
  reviewed_by: string | null
  decision_reason: string | null
}
const result = ref<Page<Reversal>>(),
  busy = ref(false),
  error = ref(''),
  notice = ref(''),
  modal = ref<'create' | 'approve' | 'reject' | 'cancel'>(),
  row = ref<Reversal>(),
  kind = ref('CONSUMPTION'),
  source = ref(0),
  reason = ref(''),
  reference = ref(''),
  ack = ref(false),
  body = ref<string>()
const options = computed(() =>
  kind.value === 'CONSUMPTION'
    ? props.consumptions
    : props.returns.filter((r) => r.status === 'RETURNED'),
)
const names: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已冲正',
  REJECTED: '已驳回',
  CANCELLED: '已撤销',
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
async function load(page = 0) {
  result.value = undefined
  result.value = await request(
    `/api/v1/wms/production-reversals?issueId=${props.issueId}&page=${page}&size=20`,
  )
}
function open(
  action: 'create' | 'approve' | 'reject' | 'cancel',
  item?: Reversal,
) {
  modal.value = action
  notice.value = ''
  row.value = item
  reason.value = ''
  reference.value = ''
  ack.value = false
  body.value = undefined
  kind.value = 'CONSUMPTION'
  source.value = options.value[0]?.id || 0
}
async function submit() {
  if (
    !modal.value ||
    ((modal.value === 'approve' || modal.value === 'create') && !ack.value)
  )
    return
  body.value ??= JSON.stringify(
    modal.value === 'create'
      ? {
          kind: kind.value,
          sourceId: source.value,
          requestKey: crypto.randomUUID(),
          externalReference: reference.value,
          reason: reason.value,
        }
      : { reason: reason.value },
  )
  await perform(async () => {
    await request(
      modal.value === 'create'
        ? '/api/v1/wms/production-reversals'
        : `/api/v1/wms/production-reversals/${row.value!.id}/${modal.value}`,
      { method: 'POST', body: body.value },
    )
    modal.value = undefined
    notice.value = '冲正处理已保存。'
    try {
      await load()
    } finally {
      // A failed list refresh must not hide a completed write from the parent summary.
      emit('changed')
    }
  })
}
onMounted(() => perform(() => load()))
</script>
<template>
  <section class="panel panel-body">
    <div class="row-actions">
      <h2>生产冲正</h2>
      <button
        v-if="allowed('reverse')"
        :disabled="busy"
        @click="open('create')"
      >
        申请生产冲正</button
      ><button :disabled="busy" @click="perform(() => load())">
        刷新冲正记录
      </button>
    </div>
    <p class="muted">
      整笔纠正已记录消耗或已确认退料，由另一人审批。原单与流水保留，已被后续业务使用的退料库存不能直接冲正。
    </p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>冲正 / 原记录</th>
            <th>数量 / 状态</th>
            <th>纠错依据 / 原因</th>
            <th>申请 / 审批人</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in result?.items" :key="r.id">
            <td>
              PCR-{{ r.id
              }}<small>{{
                r.consumption_id
                  ? '消耗 PC-' + r.consumption_id
                  : '退料 PR-' + r.production_return_id
              }}</small>
            </td>
            <td>
              {{ r.amount }}<small>{{ names[r.status] }}</small>
            </td>
            <td>
              {{ r.external_reference }}<small>{{ r.reason }}</small
              ><small>{{ r.decision_reason }}</small>
            </td>
            <td>
              {{ r.created_by }}<small>{{ r.reviewed_by }}</small>
            </td>
            <td>
              <template v-if="r.status === 'PENDING'"
                ><button
                  v-if="allowed('review') && r.created_by !== username"
                  :disabled="busy"
                  @click="open('approve', r)"
                >
                  审批冲正</button
                ><button
                  v-if="allowed('review') && r.created_by !== username"
                  :disabled="busy"
                  @click="open('reject', r)"
                >
                  驳回</button
                ><button
                  v-if="allowed('reverse') && r.created_by === username"
                  :disabled="busy"
                  @click="open('cancel', r)"
                >
                  撤销申请</button
                ><small v-if="r.created_by === username"
                  >需另一人审批</small
                ></template
              >
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="5">暂无生产冲正</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="result" class="row-actions">
      <span>共 {{ result.totalElements }} 条</span
      ><button
        :disabled="busy || result.page === 0"
        @click="perform(() => load(result!.page - 1))"
      >
        上一页冲正</button
      ><button
        :disabled="busy || result.page + 1 >= result.totalPages"
        @click="perform(() => load(result!.page + 1))"
      >
        下一页冲正
      </button>
    </div>
    <div v-if="modal" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-label="生产冲正处理"
      >
        <h2>
          {{
            modal === 'create'
              ? '申请生产冲正'
              : modal === 'approve'
                ? '审批生产冲正'
                : modal === 'reject'
                  ? '驳回生产冲正'
                  : '撤销冲正申请'
          }}
        </h2>
        <p>
          领料 MI-{{ issueId
          }}<span v-if="row"> · PCR-{{ row.id }} · {{ row.amount }}</span>
        </p>
        <form @submit.prevent="submit">
          <fieldset :disabled="busy || !!body">
            <template v-if="modal === 'create'"
              ><label
                >原业务类型<select
                  v-model="kind"
                  @change="source = options[0]?.id || 0"
                >
                  <option value="CONSUMPTION">实际消耗</option>
                  <option value="RETURN">已确认退料</option>
                </select></label
              ><label
                >原记录<select v-model="source" required>
                  <option v-for="o in options" :key="o.id" :value="o.id">
                    {{ kind === 'CONSUMPTION' ? 'PC-' : 'PR-' }}{{ o.id }} ·
                    {{ o.quantity }}
                  </option>
                </select></label
              >
              <p class="muted">可选记录来自上方当前页，请先翻页定位原记录。</p>
              <label
                >MES 纠错参考号<input
                  v-model="reference"
                  required
                  maxlength="64" /></label></template
            ><label
              >处理原因<textarea
                v-model="reason"
                required
                maxlength="500"
              /></label
            ><label
              v-if="modal === 'create' || modal === 'approve'"
              class="check"
              ><input
                v-model="ack"
                type="checkbox"
                required
              />已核对原单、实物归属与 MES 纠错依据，确认整笔反向记账</label
            >
          </fieldset>
          <p v-if="body">请求内容已锁定，重试将保留原请求。</p>
          <p v-if="error" class="error">{{ error }}</p>
          <div class="row-actions">
            <button type="button" :disabled="busy" @click="modal = undefined">
              关闭</button
            ><button
              :disabled="
                busy ||
                ((modal === 'create' || modal === 'approve') && !ack) ||
                (modal === 'create' && !source)
              "
            >
              提交冲正处理
            </button>
          </div>
        </form>
      </section>
    </div>
  </section>
</template>
<style scoped>
small {
  display: block;
  margin-top: 5px;
}
.row-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
fieldset {
  border: 0;
  padding: 0;
  display: grid;
  gap: 14px;
}
table {
  min-width: 800px;
}
.panel {
  margin-bottom: 20px;
}
</style>
