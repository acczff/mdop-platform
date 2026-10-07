<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { request, type Page, type Warehouse } from '../api'

const props = defineProps<{ authorities: string[]; username: string }>()
const route = useRoute()
interface Stock {
  id: number
  warehouse_id: number
  warehouse_purpose: string
  material_name: string
  material_code: string
  location_name: string
  batch_no: string
  quality_status: string
  unit: string
  on_hand_qty: string
  available_qty: string
  reserved_qty: string
  production_qty: string
  active_freeze_id: number | null
}
interface Freeze extends Omit<Stock, 'warehouse_purpose' | 'production_qty'> {
  balance_id: number
  status: 'FROZEN' | 'RELEASED'
  reason: string
  created_by: string
  created_at: string
  release_reason: string | null
  released_by: string | null
  released_at: string | null
  frozen_on_hand: string
  frozen_available: string
  frozen_reserved: string
  released_on_hand: string | null
  released_available: string | null
  released_reserved: string | null
}
const warehouses = ref<Warehouse[]>([]),
  warehouse = ref(0)
const ready = ref(false),
  busy = ref(false),
  error = ref(''),
  notice = ref('')
const result = ref<Page<Freeze>>(),
  stocks = ref<Stock[]>([]),
  row = ref<Freeze>()
const modal = ref<'create' | 'release' | 'detail'>(),
  balance = ref(0),
  reason = ref(''),
  acknowledged = ref(false)
const pending = ref<{ url: string; body: string }>()
const qualityNames: Record<string, string> = {
  QUALIFIED: '合格',
  REJECTED: '不合格',
  PENDING_INSPECTION: '待检',
}
function allowed(permission: string) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:freeze:${permission}`)
  )
}
function scoped(id: number) {
  return (
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes(`wms:warehouse:${id}`)
  )
}
function canRelease(r: Freeze) {
  return (
    allowed('review') &&
    scoped(r.warehouse_id) &&
    r.status === 'FROZEN' &&
    r.created_by !== props.username
  )
}
async function perform(action: () => Promise<void>) {
  if (busy.value) return
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
async function load(page = 0) {
  result.value = undefined
  if (!ready.value) {
    const all: Warehouse[] = []
    for (let p = 1; ; p++) {
      const data = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${p}&size=100`,
      )
      all.push(
        ...data.items.filter((w) => scoped(w.id) && w.purpose !== 'LINE_SIDE'),
      )
      if (p >= data.totalPages) break
    }
    warehouses.value = all
    warehouse.value =
      all.find((w) => w.id === Number(route?.query.warehouseId))?.id ||
      all[0]?.id ||
      0
    ready.value = true
  }
  if (warehouse.value)
    result.value = await request(
      `/api/v1/wms/freezes?warehouseId=${warehouse.value}&page=${page}&size=20`,
    )
}
async function open(action: 'create' | 'release' | 'detail', item?: Freeze) {
  if (modal.value) return
  await perform(async () => {
    stocks.value = []
    row.value = undefined
    if (action === 'create') {
      const all: Stock[] = []
      for (let p = 0; ; p++) {
        const data = await request<Page<Stock>>(
          `/api/v1/wms/stock?warehouseId=${warehouse.value}&page=${p}&size=100`,
        )
        all.push(
          ...data.items.filter(
            (s) =>
              !s.active_freeze_id &&
              s.warehouse_purpose !== 'LINE_SIDE' &&
              Number(s.on_hand_qty) > 0 &&
              Number(s.production_qty) === 0,
          ),
        )
        if (p + 1 >= data.totalPages) break
      }
      stocks.value = all
    }
    if (item) {
      row.value = await request(`/api/v1/wms/freezes/${item.id}`)
      if (action === 'release' && !canRelease(row.value!))
        throw new Error('当前状态或身份不允许审批解冻，请刷新记录')
    }
    balance.value =
      stocks.value.find((s) => s.id === Number(route?.query.balanceId))?.id || 0
    reason.value = ''
    acknowledged.value = false
    pending.value = undefined
    modal.value = action
  })
}
async function submit() {
  await perform(async () => {
    if (!modal.value || modal.value === 'detail' || !acknowledged.value) return
    if (!pending.value) {
      if (!reason.value.trim()) throw new Error('请填写原因')
      if (modal.value === 'create' && !balance.value)
        throw new Error('请选择库存')
      pending.value = {
        url:
          modal.value === 'create'
            ? '/api/v1/wms/freezes'
            : `/api/v1/wms/freezes/${row.value!.id}/release`,
        body: JSON.stringify({
          idempotencyKey: crypto.randomUUID(),
          reason: reason.value.trim(),
          ...(modal.value === 'create' ? { balanceId: balance.value } : {}),
        }),
      }
    }
    const saved = await request<Freeze>(pending.value.url, {
      method: 'POST',
      body: pending.value.body,
    })
    notice.value = `FR-${saved.id} 已${saved.status === 'FROZEN' ? '冻结' : '解冻'}，在手数量未因本操作改变。`
    modal.value = undefined
    pending.value = undefined
    await load(result.value?.page || 0)
  })
}
onMounted(() => void perform(() => load()))
</script>

<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>库存冻结与解冻</h1>
        <p class="muted">
          整条库存冻结立即生效，另一人审批解冻。冻结和解冻均保留原因及数量快照。
        </p>
      </div>
      <button
        v-if="allowed('create')"
        class="primary"
        :disabled="busy || !!modal || !warehouse"
        @click="open('create')"
      >
        冻结库存
      </button>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <p v-if="notice" role="status" class="success">{{ notice }}</p>
    <div class="panel filters">
      <label
        >查看仓库<select
          v-model="warehouse"
          :disabled="busy || !!modal"
          @change="perform(() => load())"
        >
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.name }}
          </option>
        </select></label
      >
      <button :disabled="busy || !!modal" @click="perform(() => load())">
        刷新记录
      </button>
    </div>
    <p class="hint">
      冻结不扣减在手数量，可用量归零，已有预占保留；可取消预占但不能发出。入库合并后仍保持冻结。首版不含部分数量冻结及线边库存。
    </p>
    <section
      v-if="modal"
      class="panel freeze-form"
      :aria-label="
        modal === 'create'
          ? '冻结库存表单'
          : modal === 'release'
            ? '审批解冻表单'
            : '冻结审计详情'
      "
    >
      <h2>
        {{
          modal === 'create'
            ? '冻结库存'
            : modal === 'release'
              ? '审批解冻'
              : '冻结审计'
        }}<template v-if="row"> · FR-{{ row.id }}</template>
      </h2>
      <template v-if="row">
        <p>
          {{ row.material_name }} · {{ row.location_name }} · 批次
          {{ row.batch_no || '无' }} · {{ qualityNames[row.quality_status] }}
        </p>
        <p>
          当前在手 {{ row.on_hand_qty }} / 可用 {{ row.available_qty }} / 预占
          {{ row.reserved_qty }} {{ row.unit }}
        </p>
        <p>
          冻结前：在手 {{ row.frozen_on_hand }} / 可用
          {{ row.frozen_available }} / 预占 {{ row.frozen_reserved }}
        </p>
        <p>
          冻结：{{ row.created_by }} · {{ row.created_at }} · {{ row.reason }}
        </p>
        <template v-if="row.status === 'RELEASED'"
          ><p>
            解冻后：在手 {{ row.released_on_hand }} / 可用
            {{ row.released_available }} / 预占 {{ row.released_reserved }}
          </p>
          <p>
            审批解冻：{{ row.released_by }} · {{ row.released_at }} ·
            {{ row.release_reason }}
          </p></template
        >
        <RouterLink
          :to="`/inventory?warehouseId=${row.warehouse_id}&balanceId=${row.balance_id}`"
          >查看库存流水 #{{ row.balance_id }}</RouterLink
        >
      </template>
      <form v-if="modal !== 'detail'" @submit.prevent="submit">
        <fieldset :disabled="busy || !!pending">
          <label v-if="modal === 'create'"
            >库存记录<select v-model="balance" required>
              <option :value="0" disabled>请选择整条库存</option>
              <option v-for="s in stocks" :key="s.id" :value="s.id">
                #{{ s.id }} · {{ s.material_name }} · {{ s.location_name }} ·
                {{ s.batch_no || '无批次' }} ·
                {{ qualityNames[s.quality_status] }} · 在手
                {{ s.on_hand_qty }} · 预占 {{ s.reserved_qty }}
              </option>
            </select></label
          >
          <p v-if="modal === 'create' && !stocks.length" class="hint">
            本仓暂无可冻结库存。
          </p>
          <label
            >{{ modal === 'create' ? '冻结原因' : '解冻审批原因'
            }}<textarea v-model="reason" required maxlength="500" />
          </label>
          <label class="confirm"
            ><input v-model="acknowledged" type="checkbox" />{{
              modal === 'create'
                ? '确认冻结整条库存并暂停其发出'
                : '已核对冻结原因，批准解除本次冻结'
            }}</label
          >
        </fieldset>
        <p v-if="pending" class="hint">
          请求结果需核对。重试保持首次提交内容；关闭前请先核对记录，避免重复申请。
        </p>
        <button
          class="primary"
          :disabled="busy || !acknowledged || (modal === 'create' && !balance)"
        >
          {{
            pending
              ? '按原请求重试'
              : modal === 'create'
                ? '确认冻结'
                : '批准解冻'
          }}
        </button>
      </form>
      <button :disabled="busy" @click="modal = undefined">
        {{ pending ? '核对后关闭' : '关闭' }}
      </button>
    </section>
    <section class="panel">
      <div class="freeze-table">
        <table>
          <thead>
            <tr>
              <th>冻结记录 / 状态</th>
              <th>库存 / 批次</th>
              <th>冻结快照</th>
              <th>原因 / 操作人</th>
              <th>解冻审批</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in result?.items" :key="r.id">
              <td>
                FR-{{ r.id
                }}<small>{{
                  r.status === 'FROZEN' ? '冻结中' : '已解冻'
                }}</small>
              </td>
              <td>
                {{ r.material_name
                }}<small>#{{ r.balance_id }} · {{ r.location_name }}</small
                ><small
                  >{{ r.batch_no || '无批次' }} ·
                  {{ qualityNames[r.quality_status] }}</small
                >
              </td>
              <td>
                在手 {{ r.frozen_on_hand
                }}<small>冻结前可用 {{ r.frozen_available }}</small
                ><small>预占 {{ r.frozen_reserved }} {{ r.unit }}</small>
              </td>
              <td>
                {{ r.reason
                }}<small>{{ r.created_by }} · {{ r.created_at }}</small>
              </td>
              <td>
                {{ r.release_reason || '—'
                }}<small v-if="r.released_by"
                  >{{ r.released_by }} · {{ r.released_at }}</small
                >
              </td>
              <td>
                <button :disabled="busy || !!modal" @click="open('detail', r)">
                  审计详情</button
                ><button
                  v-if="canRelease(r)"
                  :disabled="busy || !!modal"
                  @click="open('release', r)"
                >
                  审批解冻
                </button>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="6">
                {{
                  busy
                    ? '正在加载…'
                    : error
                      ? '加载失败，请刷新重试'
                      : '暂无冻结记录'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pagination">
        <span
          >共 {{ result.totalElements }} 条 · 第 {{ result.page + 1 }} /
          {{ Math.max(1, result.totalPages) }} 页</span
        ><button
          :disabled="busy || !!modal || result.page === 0"
          @click="perform(() => load(result!.page - 1))"
        >
          上一页</button
        ><button
          :disabled="busy || !!modal || result.page + 1 >= result.totalPages"
          @click="perform(() => load(result!.page + 1))"
        >
          下一页
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.freeze-form {
  padding: 20px;
  margin-bottom: 16px;
}
.freeze-form fieldset {
  border: 0;
  padding: 0;
  display: grid;
  gap: 12px;
  margin: 16px 0;
}
.freeze-form button {
  margin: 8px 8px 0 0;
}
.confirm {
  display: flex;
  align-items: center;
  gap: 8px;
}
.confirm input {
  width: auto;
}
.freeze-table {
  overflow-x: auto;
}
table {
  min-width: 1050px;
}
td small {
  display: block;
  margin-top: 5px;
  color: #6e6a80;
}
.pagination {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 16px;
}
</style>
