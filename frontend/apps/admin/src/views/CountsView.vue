<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { request, type Page, type Warehouse } from '../api'
const props = defineProps<{ authorities: string[]; username: string }>()
const route = useRoute()
interface Count {
  id: number
  balance_id: number
  status: string
  material_name: string
  location_name: string
  batch_no: string
  unit: string
  snapshot_qty: string
  counted_qty: string | null
  difference: string | null
  created_by: string
  reviewed_by: string | null
  reason: string | null
  decision_reason: string | null
}
const warehouses = ref<Warehouse[]>([]),
  warehouseId = ref(0),
  result = ref<Page<Count>>(),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const source = ref<{
  id: number
  warehouse_id: number
  material_name: string
  location_name: string
  on_hand_qty: string
}>()
const editing = ref<Count>(),
  quantity = ref(''),
  reason = ref(''),
  decision = ref<{ row: Count; action: string }>(),
  decisionReason = ref('')
const createKey = crypto.randomUUID()
const submittedBody = ref<string>()
const names: Record<string, string> = {
  DRAFT: '待录入',
  PENDING: '待审核',
  APPROVED: '已过账',
  REJECTED: '已驳回',
  CANCELLED: '已取消',
}
function allowed(action: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:count:${action}`)
  )
}
async function load(page = 0) {
  if (!warehouseId.value) return
  result.value = await request<Page<Count>>(
    `/api/v1/wms/counts?warehouseId=${warehouseId.value}&page=${page}&size=20`,
  )
}
async function perform(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function create() {
  if (!source.value) return
  await perform(async () => {
    const row = await request<Count>('/api/v1/wms/counts', {
      method: 'POST',
      body: JSON.stringify({
        balanceId: source.value!.id,
        idempotencyKey: createKey,
      }),
    })
    source.value = undefined
    notice.value = `盘点 CT-${row.id} 已保存账面快照，请录入实盘数。`
    await load()
  })
}
function edit(row: Count) {
  editing.value = row
  quantity.value = ''
  reason.value = ''
  submittedBody.value = undefined
}
async function submit() {
  if (!editing.value) return
  submittedBody.value ??= JSON.stringify({
    quantity: quantity.value,
    reason: reason.value,
  })
  await perform(async () => {
    await request(`/api/v1/wms/counts/${editing.value!.id}/submit`, {
      method: 'POST',
      body: submittedBody.value,
    })
    editing.value = undefined
    notice.value = '已提交，请由另一人审核。'
    await load()
  })
}
function openDecision(row: Count, action: string) {
  decision.value = { row, action }
  decisionReason.value = ''
}
async function decide() {
  if (!decision.value) return
  const { row, action } = decision.value
  await perform(async () => {
    await request(`/api/v1/wms/counts/${row.id}/${action}`, {
      method: 'POST',
      body:
        action === 'approve'
          ? undefined
          : JSON.stringify({ reason: decisionReason.value }),
    })
    decision.value = undefined
    notice.value =
      action === 'approve'
        ? '审核完成，库存已按实盘数记账。'
        : '盘点状态已更新。'
    await load()
  })
}
onMounted(() =>
  perform(async () => {
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
    warehouseId.value = warehouses.value[0]?.id || 0
    if (route.query.balanceId && allowed('create')) {
      source.value = await request(
        `/api/v1/wms/stock/${encodeURIComponent(String(route.query.balanceId))}`,
      )
      warehouseId.value = source.value!.warehouse_id
    }
    await load()
  }),
)
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>库存盘点</h1>
        <p class="muted">
          保存账面快照、录入实盘数，由另一人审核过账。期间库存变化需重新盘点。
        </p>
      </div>
      <RouterLink to="/inventory">选择库存发起盘点</RouterLink>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <section v-if="source" class="panel">
      <h2>创建盘点快照</h2>
      <p>
        {{ source.material_name }} · {{ source.location_name }} · 当前账面
        {{ source.on_hand_qty }}
      </p>
      <p>首版仅支持已有的合格、未过期、无预占的正式库位库存维度。</p>
      <button :disabled="busy" @click="create">保存账面快照</button>
    </section>
    <label
      >仓库<select
        v-model="warehouseId"
        :disabled="busy || !!source || !!editing || !!decision"
        @change="perform(() => load())"
      >
        <option v-for="w in warehouses" :key="w.id" :value="w.id">
          {{ w.name }}
        </option>
      </select></label
    ><button :disabled="busy" @click="perform(() => load())">刷新记录</button>
    <section v-if="editing" class="panel" aria-label="录入实盘">
      <h2>录入 CT-{{ editing.id }}</h2>
      <form @submit.prevent="submit">
        <fieldset :disabled="busy || !!submittedBody">
          <label
            >实盘数量<input
              v-model="quantity"
              inputmode="decimal"
              required /></label
          ><label
            >差异原因<textarea v-model="reason" required maxlength="500" />
          </label>
        </fieldset>
        <p v-if="submittedBody">重试保持原内容，修改前请先刷新核对单据。</p>
        <button :disabled="busy">
          {{ submittedBody ? '按原内容重试' : '提交审核' }}</button
        ><button type="button" :disabled="busy" @click="editing = undefined">
          关闭录入
        </button>
      </form>
    </section>
    <section v-if="decision" class="panel" aria-label="确认盘点处理">
      <h2>确认处理 CT-{{ decision.row.id }}</h2>
      <p>
        账面 {{ decision.row.snapshot_qty }} → 实盘
        {{ decision.row.counted_qty }}，差异 {{ decision.row.difference }}
      </p>
      <p>
        {{
          decision.action === 'approve'
            ? '确认审核后立即调整库存并留下流水。'
            : '取消或驳回不会调整库存。'
        }}
      </p>
      <form @submit.prevent="decide">
        <label v-if="decision.action !== 'approve'"
          >处理原因<textarea
            v-model="decisionReason"
            required
            maxlength="500"
          /></label
        ><button :disabled="busy">
          确认{{
            decision.action === 'approve'
              ? '审核过账'
              : decision.action === 'reject'
                ? '驳回'
                : '取消盘点'
          }}</button
        ><button type="button" :disabled="busy" @click="decision = undefined">
          关闭确认
        </button>
      </form>
    </section>
    <section class="panel table-scroll">
      <table>
        <thead>
          <tr>
            <th>单号 / 状态</th>
            <th>物料 / 库位 / 批次</th>
            <th>账面 → 实盘 / 差异</th>
            <th>原因</th>
            <th>创建人 / 审核人</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in result?.items" :key="c.id">
            <td>
              CT-{{ c.id }}<small>{{ names[c.status] }}</small>
            </td>
            <td>
              {{ c.material_name
              }}<small
                >{{ c.location_name }} / {{ c.batch_no || '无批次' }}</small
              >
            </td>
            <td>
              {{ c.snapshot_qty }} → {{ c.counted_qty ?? '待录入' }} {{ c.unit
              }}<small>差异 {{ c.difference ?? '—' }}</small>
            </td>
            <td>
              {{ c.reason || '—' }}<small>{{ c.decision_reason }}</small>
            </td>
            <td>
              {{ c.created_by }}<small>{{ c.reviewed_by || '未审核' }}</small>
            </td>
            <td>
              <button
                v-if="
                  c.status === 'DRAFT' &&
                  c.created_by === username &&
                  allowed('submit')
                "
                :disabled="busy"
                @click="edit(c)"
              >
                录入实盘</button
              ><template
                v-if="
                  c.status === 'PENDING' &&
                  c.created_by !== username &&
                  allowed('review')
                "
                ><button :disabled="busy" @click="openDecision(c, 'approve')">
                  审核过账</button
                ><button :disabled="busy" @click="openDecision(c, 'reject')">
                  驳回
                </button></template
              ><button
                v-if="
                  ['DRAFT', 'PENDING'].includes(c.status) &&
                  c.created_by === username &&
                  allowed('cancel')
                "
                :disabled="busy"
                @click="openDecision(c, 'cancel')"
              >
                取消盘点
              </button>
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="6">{{ busy ? '正在加载…' : '暂无盘点记录' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="result" class="pagination">
        <span
          >共 {{ result.totalElements }} 条 · 第 {{ result.page + 1 }} /
          {{ Math.max(1, result.totalPages) }} 页</span
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
  </main>
</template>
<style scoped>
.table-scroll {
  overflow-x: auto;
}
small {
  display: block;
  color: #6e6a80;
}
.pagination {
  display: flex;
  gap: 12px;
  padding: 16px;
}
.panel {
  padding: 16px;
  margin-bottom: 16px;
}
</style>
