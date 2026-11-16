<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ApiError, request, type Page } from '@/shared/api'
import { type Warehouse } from '@/modules/masterdata/api'
import {
  CATALOG_LABELS,
  type CatalogAudit,
  type CatalogDraft,
  type CatalogKind,
  type CatalogRow,
} from '../catalogTypes'
import CatalogTable from '../components/CatalogTable.vue'
import CatalogFormModal from '../components/CatalogFormModal.vue'
import CatalogHistoryModal from '../components/CatalogHistoryModal.vue'

const props = defineProps<{ authorities: string[] }>()

const editable = computed(
  () =>
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes('masterdata:write'),
)
const tab = ref<CatalogKind>('suppliers')
const directory = ref<Partial<Record<CatalogKind, CatalogRow[]>>>({})
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
const current = ref<CatalogRow>()
const historyRow = ref<CatalogRow>(),
  historyRows = ref<CatalogAudit[]>([]),
  historyError = ref(''),
  historyLoading = ref(false)
let generation = 0,
  historyGeneration = 0
const draft = reactive<CatalogDraft>({
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
    const kinds = Object.keys(CATALOG_LABELS) as CatalogKind[]
    const [lists, w] = await Promise.all([
      Promise.all(
        kinds.map((k) => request<CatalogRow[]>('/api/master-data/' + k)),
      ),
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

function selectTab(key: CatalogKind) {
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
  const keys = Object.keys(CATALOG_LABELS) as CatalogKind[]
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

function open(row?: CatalogRow) {
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

async function save(submittedDraft: CatalogDraft) {
  if (!editable.value || !ready.value || busy.value || !dialog.value) return
  Object.assign(draft, submittedDraft)
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
  message.value = CATALOG_LABELS[kind] + '已保存'
  await load()
}

async function history(row: CatalogRow) {
  if (!editable.value || !ready.value || blocked.value) return
  historyRow.value = row
  historyRows.value = []
  historyError.value = ''
  historyLoading.value = true
  const token = ++historyGeneration
  try {
    const result = await request<CatalogAudit[]>(
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
          v-for="(label, key) in CATALOG_LABELS"
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
            <span class="sr-only">搜索{{ CATALOG_LABELS[tab] }}</span
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
            新增{{ CATALOG_LABELS[tab] }}
          </button>
        </div>
        <CatalogTable
          :tab="tab"
          :rows="rows"
          :warehouses="warehouses"
          :editable="editable"
          :blocked="blocked"
          :ready="ready"
          :loading="loading"
          :keyword="keyword"
          :status-filter="statusFilter"
          @edit="open"
          @history="history"
        />
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
    <CatalogFormModal
      v-if="dialog"
      :tab="tab"
      :current="current"
      :initial-draft="draft"
      :units="directory.units"
      :warehouses="warehouses"
      :form-error="formError"
      :busy="busy"
      :ready="ready"
      @close="dialog = false"
      @save="save"
    />
    <CatalogHistoryModal
      v-if="historyRow"
      :history-row="historyRow"
      :history-rows="historyRows"
      :history-error="historyError"
      :history-loading="historyLoading"
      @close="closeHistory"
    />
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
.text-action {
  border-color: transparent;
  background: transparent;
  color: #6941b5;
  padding: 6px 8px;
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
