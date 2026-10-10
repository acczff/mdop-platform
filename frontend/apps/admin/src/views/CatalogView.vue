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
  'production-sites': '生产地点',
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
const statusFilter = ref('')
const hasStatus = computed(
  () => !['locations', 'organizations'].includes(tab.value),
)
const loadedCount = computed(() => directory.value[tab.value]?.length || 0)
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
  (directory.value[tab.value] || []).filter(
    (r) =>
      (r.code + ' ' + r.name)
        .toLowerCase()
        .includes(keyword.value.trim().toLowerCase()) &&
      (!hasStatus.value ||
        !statusFilter.value ||
        r.status === statusFilter.value),
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
  statusFilter.value = ''
}
function clearFilters() {
  keyword.value = ''
  statusFilter.value = ''
}
function navigateTabs(event: KeyboardEvent) {
  if (blocked.value) return
  const keys = Object.keys(labels) as Kind[]
  const index = keys.indexOf(tab.value)
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? keys.length - 1
        : event.key === 'ArrowRight'
          ? (index + 1) % keys.length
          : event.key === 'ArrowLeft'
            ? (index - 1 + keys.length) % keys.length
            : -1
  if (next < 0) return
  event.preventDefault()
  selectTab(keys[next]!)
  const list = event.currentTarget as HTMLElement
  list.querySelector<HTMLButtonElement>('#catalog-tab-' + keys[next])?.focus()
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
  <main class="page catalog-page">
    <div class="page-heading">
      <div class="catalog-heading">
        <h1>基础资料</h1>
        <span v-if="!editable" class="badge disabled">只读</span>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="message" class="success" role="status">
      {{ message }}{{ error ? '；列表刷新未成功，请先重新加载核对。' : '' }}
    </p>
    <section class="panel">
      <div
        class="tabs catalog-tabs"
        role="tablist"
        aria-label="资料分类"
        @keydown="navigateTabs"
      >
        <button
          v-for="(label, key) in labels"
          :key="key"
          :id="'catalog-tab-' + key"
          role="tab"
          :aria-selected="tab === key"
          aria-controls="catalog-panel"
          :tabindex="tab === key ? 0 : -1"
          :disabled="blocked"
          :class="{ active: tab === key }"
          @click="selectTab(key)"
        >
          {{ label }}
        </button>
      </div>
      <div
        id="catalog-panel"
        role="tabpanel"
        :aria-labelledby="'catalog-tab-' + tab"
        :aria-busy="loading"
      >
        <div class="filters catalog-filters">
          <label class="grow">
            <span class="sr-only">搜索{{ labels[tab] }}</span
            ><input
              v-model="keyword"
              :disabled="blocked"
              placeholder="搜索编码或名称"
          /></label>
          <label v-if="hasStatus">
            <span class="sr-only">启用状态</span
            ><select v-model="statusFilter" :disabled="blocked">
              <option value="">全部状态</option>
              <option value="ENABLED">启用</option>
              <option value="DISABLED">停用</option>
            </select></label
          >
          <button
            v-if="keyword || statusFilter"
            class="text-action"
            :disabled="blocked"
            @click="clearFilters"
          >
            清空筛选
          </button>
          <button
            class="text-action"
            :disabled="blocked || loading"
            @click="load"
          >
            {{ loading ? '正在加载…' : '刷新' }}
          </button>
          <button
            v-if="editable"
            class="primary catalog-create"
            :disabled="!canCreate"
            @click="open()"
          >
            新增{{ labels[tab] }}
          </button>
        </div>
        <div
          class="table-wrap"
          role="region"
          :aria-label="labels[tab] + '列表，可横向滚动'"
          tabindex="0"
        >
          <table>
            <thead>
              <tr>
                <th>编码</th>
                <th>名称</th>
                <th v-if="tab === 'materials'">单位 / 管理方式</th>
                <th v-if="tab === 'locations'">所属仓库 / 区域</th>
                <th v-if="!['locations', 'organizations'].includes(tab)">
                  状态
                </th>
                <th v-if="editable && tab !== 'locations'">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>{{ row.code }}</td>
                <td>{{ row.name }}</td>
                <td v-if="tab === 'materials'">
                  {{ row.unit }}
                  <span class="material-tracking">{{
                    row.trackingMode === 'BATCH' ? '按批次' : '按数量'
                  }}</span>
                  <small v-if="row.requireDateCode || row.requireExpiry">
                    {{ row.requireDateCode ? 'Date Code 必填' : ''
                    }}{{ row.requireDateCode && row.requireExpiry ? ' · ' : ''
                    }}{{ row.requireExpiry ? '有效期必填' : '' }}
                  </small>
                </td>
                <td v-if="tab === 'locations'">
                  {{
                    warehouses.find((w) => w.id === row.warehouseId)?.name ||
                    row.warehouseId
                  }}<small>{{ areaNames[row.areaType || ''] }}</small>
                </td>
                <td v-if="!['locations', 'organizations'].includes(tab)">
                  <span
                    class="catalog-status"
                    :class="{ disabled: row.status === 'DISABLED' }"
                    >{{ statuses[row.status || ''] }}</span
                  >
                </td>
                <td
                  v-if="editable && tab !== 'locations'"
                  class="catalog-actions"
                >
                  <button
                    class="text-action"
                    :disabled="blocked || !ready"
                    @click="open(row)"
                  >
                    编辑
                  </button>
                  <button
                    class="text-action"
                    :disabled="blocked || !ready"
                    @click="history(row)"
                  >
                    变更历史
                  </button>
                </td>
              </tr>
              <tr v-if="!rows.length">
                <td colspan="6" class="empty">
                  {{
                    loading
                      ? '正在加载…'
                      : ready
                        ? keyword || statusFilter
                          ? '暂无匹配资料'
                          : '暂无' + labels[tab]
                        : '资料未就绪，请刷新后操作'
                  }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer class="catalog-footer">
          <span role="status">{{
            ready
              ? keyword || statusFilter
                ? `匹配 ${rows.length} 条 / 已加载 ${loadedCount} 条`
                : `已加载 ${loadedCount} 条`
              : loading
                ? '正在加载资料…'
                : '资料未就绪'
          }}</span>
          <span v-if="ready && loadedCount >= 1000" class="hint"
            >已达到 1000
            条加载上限，筛选仅覆盖已加载资料，可能未包含全部记录。</span
          >
        </footer>
      </div>
    </section>
    <div v-if="dialog" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="catalog-title"
      >
        <h2 id="catalog-title">
          {{ current ? '编辑' : '新增' }}{{ labels[tab] }}
        </h2>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <p v-if="tab === 'organizations'" class="hint">
          维护当前工厂的组织标识，全部资料共用此归属。
        </p>
        <p v-if="tab === 'units'" class="hint">
          单位必须先登记，再供物料选择。不提供换算；已被物料引用的单位名称不可改义。
        </p>
        <p v-if="tab === 'locations'" class="hint">
          仓库在“仓库管理”中维护；本页新增库位沿用现有仓库规则。
        </p>
        <p v-if="current?.identityLocked" class="hint">
          已被业务引用，仅可修改名称和启停状态。
        </p>
        <p v-if="tab === 'locations' && warehouses.length >= 100" class="hint">
          仓库选项已达到 100 个加载上限，可能未包含全部仓库。
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
              >状态<select
                v-model="draft.status"
                name="status"
                aria-label="状态"
                aria-describedby="catalog-status-help"
              >
                <option value="ENABLED">启用</option>
                <option value="DISABLED">停用</option></select
              ><small id="catalog-status-help" class="hint"
                >停用后不能用于新业务授权；已有单据仍可按原流程收尾，不会冻结库存。</small
              ></label
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

<style scoped>
.catalog-page {
  padding-top: 24px;
}
.catalog-page .page-heading {
  margin-bottom: 16px;
}
.catalog-heading {
  display: flex;
  gap: 12px;
  align-items: center;
}
.catalog-heading h1 {
  font-size: 24px;
  margin: 0;
}
.catalog-tabs {
  flex-wrap: nowrap;
  overflow-x: auto;
  gap: 8px;
  border-bottom: 1px solid #e9e7ee;
  padding: 0 16px;
  margin: 0;
}
.catalog-tabs button {
  flex-shrink: 0;
  border: 0;
  border-bottom: 3px solid transparent;
  border-radius: 0;
  background: transparent;
  padding: 14px 12px;
  color: #60586e;
  font-size: 14px;
}
.catalog-tabs .active {
  color: #6237a0;
  border-bottom-color: #6941b5;
  font-weight: 600;
}
.catalog-filters {
  padding: 16px;
  gap: 8px;
  align-items: center;
  border-bottom: 0;
}
.catalog-filters .grow {
  flex: 1 1 180px;
  max-width: 300px;
}
.catalog-filters label {
  min-width: 116px;
  font-size: 13px;
  font-weight: 400;
}
.catalog-filters button {
  font-size: 13px;
  min-height: 40px;
}
.catalog-create {
  margin-left: auto;
}
.catalog-page table {
  min-width: 560px;
}
.catalog-page th,
.catalog-page td {
  padding: 12px 16px;
}
.catalog-page td {
  overflow-wrap: anywhere;
}
.catalog-page td:first-child {
  white-space: nowrap;
}
.catalog-page th {
  color: #60586e;
  font-size: 13px;
}
.catalog-page td small {
  color: #6c687d;
}
.catalog-status {
  color: #26734a;
  white-space: nowrap;
  font-size: 13px;
}
.catalog-status.disabled {
  color: #78717f;
}
.material-tracking {
  display: inline-block;
  margin-left: 6px;
  color: #6c687d;
  font-size: 12px;
}
.catalog-actions {
  white-space: nowrap;
}
.text-action {
  border-color: transparent;
  background: transparent;
  color: #6941b5;
  padding: 6px 8px;
}
.catalog-actions button {
  font-size: 13px;
  padding: 6px 8px;
  margin: 2px 4px 2px 0;
}
.catalog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 16px;
  color: #60586e;
  font-size: 12px;
}
.catalog-footer .hint {
  font-size: 12px;
}
.catalog-page label .hint {
  font-weight: 400;
  line-height: 1.6;
}
@media (max-width: 640px) {
  .catalog-page {
    padding: 16px;
  }
  .catalog-heading {
    gap: 8px;
    flex-wrap: wrap;
  }
  .catalog-heading h1 {
    font-size: 22px;
  }
  .catalog-tabs {
    gap: 0;
    padding: 0 8px;
  }
  .catalog-tabs button {
    padding: 10px;
  }
  .catalog-filters .grow {
    flex-basis: 100%;
    max-width: none;
  }
}
</style>
