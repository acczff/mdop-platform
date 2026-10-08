<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { ApiError, request, statuses, type Warehouse, type Page } from '../api'
import { areaNames } from '../receiving'

const labels = {
  suppliers: '供应商',
  customers: '客户',
  materials: '物料',
  units: '基本单位',
  organizations: '组织',
  locations: '库位',
}
type Kind = keyof typeof labels
interface Row {
  id: number
  code: string
  name: string
  status?: string
  version?: number
  unitId?: number
  unit?: string
  trackingMode?: string
  requireDateCode?: boolean
  requireExpiry?: boolean
  identityLocked?: boolean
  warehouseId?: number
  areaType?: string
}
interface Audit {
  id: number
  action_type: string
  reason: string
  created_by: string
  created_at: string
  before_state: unknown
  after_state: unknown
}
const props = defineProps<{ authorities: string[] }>()
const editable = computed(
  () =>
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes('masterdata:write'),
)
const tab = ref<Kind>('suppliers')
const directory = ref<Partial<Record<Kind, Row[]>>>({})
const warehouses = ref<Warehouse[]>([])
const keyword = ref(''),
  error = ref(''),
  message = ref(''),
  formError = ref('')
const loading = ref(false),
  ready = ref(false),
  busy = ref(false),
  dialog = ref(false)
const current = ref<Row>()
const historyRow = ref<Row>(),
  historyRows = ref<Audit[]>([]),
  historyError = ref(''),
  historyLoading = ref(false)
let generation = 0,
  historyGeneration = 0
const draft = reactive({
  code: '',
  name: '',
  status: 'ENABLED',
  unitId: 0,
  trackingMode: 'BATCH',
  requireDateCode: false,
  requireExpiry: false,
  warehouseId: 0,
  areaType: 'RECEIVING',
  reason: '',
})
const blocked = computed(() => busy.value || dialog.value || !!historyRow.value)
const rows = computed(() =>
  (directory.value[tab.value] || []).filter((r) =>
    (r.code + ' ' + r.name).toLowerCase().includes(keyword.value.toLowerCase()),
  ),
)
const canCreate = computed(
  () =>
    editable.value &&
    ready.value &&
    !blocked.value &&
    !loading.value &&
    (tab.value !== 'organizations' || !directory.value.organizations?.length),
)
function invalidate() {
  ready.value = false
  directory.value = {}
  warehouses.value = []
}
async function load() {
  if (blocked.value) return
  const token = ++generation
  loading.value = true
  error.value = ''
  invalidate()
  try {
    const kinds = Object.keys(labels) as Kind[]
    const [lists, w] = await Promise.all([
      Promise.all(kinds.map((k) => request<Row[]>('/api/master-data/' + k))),
      request<Page<Warehouse>>('/api/master-data/warehouses?size=100'),
    ])
    if (token !== generation) return
    directory.value = Object.fromEntries(kinds.map((k, i) => [k, lists[i]]))
    warehouses.value = w.items
    ready.value = true
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}
function selectTab(key: Kind) {
  if (blocked.value) return
  tab.value = key
  keyword.value = ''
}
function open(row?: Row) {
  if (
    !editable.value ||
    !ready.value ||
    blocked.value ||
    loading.value ||
    (!row && !canCreate.value)
  )
    return
  current.value = row
  Object.assign(
    draft,
    {
      code: '',
      name: '',
      status: 'ENABLED',
      unitId:
        directory.value.units?.find((u) => u.status === 'ENABLED')?.id || 0,
      trackingMode: 'BATCH',
      requireDateCode: false,
      requireExpiry: false,
      warehouseId:
        warehouses.value.find((w) => w.status === 'ENABLED')?.id || 0,
      areaType: 'RECEIVING',
      reason: '',
    },
    row || {},
  )
  formError.value = ''
  dialog.value = true
}
async function save() {
  if (!editable.value || !ready.value || busy.value || !dialog.value) return
  if (!draft.name.trim() || (current.value && !draft.reason.trim())) {
    formError.value = '请填写名称和变更原因'
    return
  }
  busy.value = true
  formError.value = ''
  message.value = ''
  const kind = tab.value,
    editing = current.value
  const body = editing
    ? {
        version: editing.version,
        name: draft.name,
        status: draft.status,
        unitId: draft.unitId,
        trackingMode: draft.trackingMode,
        requireDateCode: draft.requireDateCode,
        requireExpiry: draft.requireExpiry,
        reason: draft.reason,
      }
    : {
        code: draft.code,
        name: draft.name,
        ...(kind === 'materials'
          ? {
              unitId: draft.unitId,
              trackingMode: draft.trackingMode,
              requireDateCode: draft.requireDateCode,
              requireExpiry: draft.requireExpiry,
            }
          : kind === 'locations'
            ? { warehouseId: draft.warehouseId, areaType: draft.areaType }
            : {}),
      }
  try {
    await request(
      '/api/master-data/' + kind + (editing ? '/' + editing.id : ''),
      { method: editing ? 'PUT' : 'POST', body: JSON.stringify(body) },
    )
  } catch (e) {
    formError.value = (e as Error).message
    if (
      !(e instanceof ApiError) ||
      e.status === 0 ||
      e.status === 409 ||
      e.status >= 500
    ) {
      invalidate()
      error.value = '结果待核对：请关闭窗口并刷新，核对最新状态后再操作。'
      formError.value += '；请关闭窗口并刷新，核对最新状态。'
    }
    busy.value = false
    return
  }
  dialog.value = false
  busy.value = false
  message.value = labels[kind] + '已保存'
  await load()
}
async function history(row: Row) {
  if (!editable.value || !ready.value || blocked.value) return
  historyRow.value = row
  historyRows.value = []
  historyError.value = ''
  historyLoading.value = true
  const token = ++historyGeneration
  try {
    const result = await request<Audit[]>(
      '/api/master-data/' + tab.value + '/' + row.id + '/history',
    )
    if (token === historyGeneration) historyRows.value = result
  } catch (e) {
    if (token === historyGeneration) historyError.value = (e as Error).message
  } finally {
    if (token === historyGeneration) historyLoading.value = false
  }
}
function closeHistory() {
  historyGeneration++
  historyRow.value = undefined
  historyRows.value = []
}
function describe(value: unknown) {
  if (!value) return '—'
  try {
    const v = (typeof value === 'string' ? JSON.parse(value) : value) as Record<
      string,
      unknown
    >
    const tracking = v.trackingMode || v.tracking_mode
    const result = [
      String(v.name || ''),
      v.status ? statuses[String(v.status)] : '',
      String(v.unit || ''),
      tracking === 'BATCH' ? '按批次' : tracking === 'QUANTITY' ? '按数量' : '',
    ]
    if ('requireDateCode' in v || 'require_date_code' in v)
      result.push(
        (v.requireDateCode ?? v.require_date_code)
          ? 'Date Code 必填'
          : 'Date Code 非必填',
      )
    if ('requireExpiry' in v || 'require_expiry' in v)
      result.push(
        (v.requireExpiry ?? v.require_expiry) ? '有效期必填' : '有效期非必填',
      )
    return result.filter(Boolean).join(' / ')
  } catch {
    return '记录无法解析，请联系管理员核对'
  }
}
onMounted(load)
onBeforeUnmount(() => {
  generation++
  historyGeneration++
})
</script>
<template>
  <main class="page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">MASTER DATA</p>
        <h1>基础资料</h1>
        <p class="muted">
          统一维护业务引用。停用阻止新的业务授权，已有单据按原流程收尾。
        </p>
      </div>
      <button
        v-if="editable"
        class="primary"
        :disabled="!canCreate"
        @click="open()"
      >
        ＋ 新增{{ labels[tab] }}
      </button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="success" role="status">
      {{ message }}{{ error ? '；列表刷新未成功，请先重新加载核对。' : '' }}
    </p>
    <div class="tabs">
      <button
        v-for="(label, key) in labels"
        :key="key"
        :disabled="blocked"
        :class="{ active: tab === key }"
        @click="selectTab(key)"
      >
        {{ label }}
      </button>
    </div>
    <p v-if="tab === 'organizations'" class="hint">
      当前使用单一组织标识，全部资料共用此归属。组织名称不会改变仓库权限；本页不提供多租户隔离。
    </p>
    <p v-if="tab === 'units'" class="hint">
      单位必须先登记，再供物料选择。不提供换算；已被物料引用的单位名称不可改义。
    </p>
    <p v-if="tab === 'locations'" class="hint">
      仓库在“仓库管理”中维护；本页新增库位沿用现有仓库规则。
    </p>
    <section class="panel">
      <div class="filters">
        <label class="grow"
          >搜索{{ labels[tab]
          }}<input
            v-model="keyword"
            :disabled="blocked"
            placeholder="编码或名称" /></label
        ><button :disabled="blocked || loading" @click="load">
          {{ loading ? '正在加载…' : '刷新' }}</button
        ><span class="hint">每类最多显示 1000 条；仓库选项最多 100 个</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th v-if="tab === 'materials'">单位 / 管理方式</th>
              <th v-if="tab === 'locations'">所属仓库 / 区域</th>
              <th v-if="!['locations', 'organizations'].includes(tab)">状态</th>
              <th v-if="editable && tab !== 'locations'">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td v-if="tab === 'materials'">
                {{ row.unit }} /
                {{ row.trackingMode === 'BATCH' ? '按批次' : '按数量'
                }}<small>{{
                  row.identityLocked
                    ? '已引用：单位及追踪策略已锁定'
                    : '尚未业务引用'
                }}</small
                ><small
                  >{{ row.requireDateCode ? 'Date Code 必填；' : ''
                  }}{{ row.requireExpiry ? '有效期必填' : '' }}</small
                >
              </td>
              <td v-if="tab === 'locations'">
                {{
                  warehouses.find((w) => w.id === row.warehouseId)?.name ||
                  row.warehouseId
                }}<small>{{ areaNames[row.areaType || ''] }}</small>
              </td>
              <td v-if="!['locations', 'organizations'].includes(tab)">
                {{ statuses[row.status || ''] }}
              </td>
              <td v-if="editable && tab !== 'locations'">
                <button :disabled="blocked || !ready" @click="open(row)">
                  维护
                </button>
                <button :disabled="blocked || !ready" @click="history(row)">
                  历史
                </button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="6" class="empty">
                {{
                  loading
                    ? '正在加载…'
                    : ready
                      ? '暂无匹配资料'
                      : '资料未就绪，请刷新后操作'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <p class="hint">
      编码创建后保留。资料改名保留原业务单据快照；资料启停不替代库存冻结。客户订单将在后续销售模块接入。
    </p>
    <div v-if="dialog" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="catalog-title"
      >
        <h2 id="catalog-title">
          {{ current ? '维护' : '新增' }}{{ labels[tab] }}
        </h2>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <p v-if="current?.identityLocked" class="hint">
          已被业务引用，仅可修改名称和启停状态。
        </p>
        <form @submit.prevent="save">
          <fieldset class="form-grid" :disabled="busy || !ready">
            <label
              >编码<input
                v-model="draft.code"
                name="code"
                :disabled="!!current"
                required
                maxlength="32"
                pattern="[A-Za-z][A-Za-z0-9-]{1,31}"
            /></label>
            <label
              >名称<input
                v-model="draft.name"
                name="name"
                required
                :maxlength="tab === 'units' ? 16 : 100"
            /></label>
            <label v-if="current && tab !== 'organizations'"
              >状态<select v-model="draft.status" name="status">
                <option value="ENABLED">启用</option>
                <option value="DISABLED">停用</option>
              </select></label
            >
            <template v-if="tab === 'materials'">
              <label
                >基本单位<select
                  v-model="draft.unitId"
                  name="unitId"
                  required
                  :disabled="!!current?.identityLocked"
                >
                  <option :value="0" disabled>请先登记单位</option>
                  <option
                    v-for="u in directory.units"
                    :key="u.id"
                    :value="u.id"
                    :disabled="u.status !== 'ENABLED'"
                  >
                    {{ u.name }}{{ u.status !== 'ENABLED' ? '（停用）' : '' }}
                  </option>
                </select></label
              >
              <label
                >管理方式<select
                  v-model="draft.trackingMode"
                  name="trackingMode"
                  :disabled="!!current?.identityLocked"
                >
                  <option value="BATCH">按批次</option>
                  <option value="QUANTITY">按数量</option>
                </select></label
              >
              <label class="check"
                ><input
                  v-model="draft.requireDateCode"
                  name="requireDateCode"
                  type="checkbox"
                  :disabled="!!current?.identityLocked"
                />Date Code 必填</label
              >
              <label class="check"
                ><input
                  v-model="draft.requireExpiry"
                  name="requireExpiry"
                  type="checkbox"
                  :disabled="!!current?.identityLocked"
                />有效期必填</label
              >
            </template>
            <template v-if="tab === 'locations'">
              <label
                >所属仓库<select v-model="draft.warehouseId" required>
                  <option :value="0" disabled>请选择仓库</option>
                  <option
                    v-for="w in warehouses.filter(
                      (w) => w.status === 'ENABLED',
                    )"
                    :key="w.id"
                    :value="w.id"
                  >
                    {{ w.name }}
                  </option>
                </select></label
              >
              <label
                >区域类型<select v-model="draft.areaType">
                  <option
                    v-for="(name, key) in areaNames"
                    :key="key"
                    :value="key"
                  >
                    {{ name }}
                  </option>
                </select></label
              >
            </template>
            <label v-if="current"
              >变更原因<textarea
                v-model="draft.reason"
                required
                maxlength="500"
              />
            </label>
          </fieldset>
          <div class="modal-actions">
            <button type="button" :disabled="busy" @click="dialog = false">
              取消</button
            ><button
              class="primary"
              :disabled="
                busy ||
                !ready ||
                (tab === 'materials' && !draft.unitId) ||
                (tab === 'locations' && !draft.warehouseId)
              "
            >
              {{ busy ? '保存中…' : '保存' }}
            </button>
          </div>
        </form>
      </section>
    </div>
    <div v-if="historyRow" class="overlay">
      <section
        class="modal wide"
        role="dialog"
        aria-modal="true"
        aria-labelledby="history-title"
      >
        <h2 id="history-title">{{ historyRow.code }} · 变更记录</h2>
        <p v-if="historyError" class="error" role="alert">{{ historyError }}</p>
        <p v-if="historyLoading">正在加载…</p>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>动作 / 时间</th>
                <th>操作人 / 原因</th>
                <th>变更前</th>
                <th>变更后</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="a in historyRows" :key="a.id">
                <td>
                  {{ a.action_type === 'CREATE' ? '创建' : '维护'
                  }}<small>{{ a.created_at }}</small>
                </td>
                <td>
                  {{ a.created_by }}<small>{{ a.reason }}</small>
                </td>
                <td>{{ describe(a.before_state) }}</td>
                <td>{{ describe(a.after_state) }}</td>
              </tr>
              <tr
                v-if="!historyRows.length && !historyLoading && !historyError"
              >
                <td colspan="4">暂无变更记录。升级前的资料不补造创建记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="modal-actions">
          <button @click="closeHistory">关闭</button>
        </div>
      </section>
    </div>
  </main>
</template>
