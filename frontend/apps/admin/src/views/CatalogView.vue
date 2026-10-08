<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { request, type Warehouse, type Page } from '../api'
import {
  type Supplier,
  type Material,
  type Location,
  areaNames,
} from '../receiving'
const props = defineProps<{ authorities: string[] }>()
const editable = computed(
  () =>
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes('masterdata:write'),
)
const tab = ref<'suppliers' | 'materials' | 'locations'>('suppliers')
const labels = { suppliers: '供应商', materials: '物料', locations: '库位' }
const suppliers = ref<Supplier[]>([]),
  materials = ref<Material[]>([]),
  locations = ref<Location[]>([]),
  warehouses = ref<Warehouse[]>([])
const error = ref(''),
  formError = ref(''),
  message = ref(''),
  keyword = ref('')
const busy = ref(false),
  dialog = ref(false),
  loading = ref(false)
const draft = reactive({
  code: '',
  name: '',
  unit: '件',
  trackingMode: 'BATCH',
  requireDateCode: false,
  requireExpiry: false,
  warehouseId: 0,
  areaType: 'RECEIVING',
})
const rows = computed(() =>
  (
    ({
      suppliers: suppliers.value,
      materials: materials.value,
      locations: locations.value,
    })[tab.value] as (Supplier & Partial<Material & Location>)[]
  ).filter((item) =>
    `${item.code} ${item.name}`
      .toLowerCase()
      .includes(keyword.value.toLowerCase()),
  ),
)
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [s, m, l, w] = await Promise.all([
      request<Supplier[]>('/api/master-data/suppliers'),
      request<Material[]>('/api/master-data/materials'),
      request<Location[]>('/api/master-data/locations'),
      request<Page<Warehouse>>('/api/master-data/warehouses?size=100'),
    ])
    suppliers.value = s
    materials.value = m
    locations.value = l
    warehouses.value = w.items
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
function open() {
  Object.assign(draft, {
    code: '',
    name: '',
    unit: '件',
    trackingMode: 'BATCH',
    requireDateCode: false,
    requireExpiry: false,
    warehouseId: warehouses.value.find((w) => w.status === 'ENABLED')?.id || 0,
    areaType: 'RECEIVING',
  })
  formError.value = ''
  dialog.value = true
}
function selectTab(key: typeof tab.value) {
  tab.value = key
  keyword.value = ''
}
async function save() {
  busy.value = true
  formError.value = ''
  message.value = ''
  const body =
    tab.value === 'suppliers'
      ? { code: draft.code, name: draft.name }
      : tab.value === 'materials'
        ? {
            code: draft.code,
            name: draft.name,
            unit: draft.unit,
            trackingMode: draft.trackingMode,
            requireDateCode: draft.requireDateCode,
            requireExpiry: draft.requireExpiry,
          }
        : {
            code: draft.code,
            name: draft.name,
            warehouseId: draft.warehouseId,
            areaType: draft.areaType,
          }
  try {
    await request(`/api/master-data/${tab.value}`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
    dialog.value = false
    message.value = `${labels[tab.value]}已创建`
    await load()
  } catch (e) {
    formError.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <main class="page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">RECEIVING FOUNDATION</p>
        <h1>收货基础资料</h1>
        <p class="muted">
          准备供应商、物料和收货库位。当前支持数量与批次管理。
        </p>
      </div>
      <button v-if="editable" class="primary" @click="open">
        ＋ 新增{{ labels[tab] }}
      </button>
    </div>
    <p v-if="error" class="error" role="alert">
      {{ error }} <button @click="load">重新加载</button>
    </p>
    <p v-if="message" class="success" role="status">{{ message }}</p>
    <div class="tabs">
      <button
        v-for="(label, key) in labels"
        :key="key"
        :class="{ active: tab === key }"
        @click="selectTab(key)"
      >
        {{ label }}
      </button>
    </div>
    <section class="panel">
      <div class="filters">
        <label class="grow"
          >搜索{{ labels[tab]
          }}<input v-model="keyword" placeholder="编码或名称" /></label
        ><span class="hint">当前显示最多 1000 条基础资料</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>编码</th>
              <th>名称</th>
              <th v-if="tab === 'materials'">单位 / 管理方式</th>
              <th v-if="tab === 'locations'">所属仓库 / 区域</th>
              <th v-if="tab === 'materials'">收货要求</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.code }}</td>
              <td>{{ row.name }}</td>
              <td v-if="tab === 'materials'">
                {{ row.unit }} /
                {{ row.trackingMode === 'BATCH' ? '按批次' : '按数量' }}
              </td>
              <td v-if="tab === 'locations'">
                {{
                  warehouses.find((w) => w.id === row.warehouseId)?.name ||
                  row.warehouseId
                }}<small>{{ areaNames[row.areaType || ''] }}</small>
              </td>
              <td v-if="tab === 'materials'">
                {{ row.requireDateCode ? 'Date Code 必填；' : ''
                }}{{ row.requireExpiry ? '有效期必填' : ''
                }}{{
                  !row.requireDateCode && !row.requireExpiry
                    ? '无额外日期要求'
                    : ''
                }}
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="4" class="empty">
                {{ loading ? '正在加载…' : '暂无匹配资料' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <p class="hint">
      本阶段提供基础资料新建与查询。编码及管理策略创建后保留，避免历史收货依据发生变化。
    </p>
    <div v-if="dialog" class="overlay">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="catalog-title"
      >
        <h2 id="catalog-title">新增{{ labels[tab] }}</h2>
        <p v-if="formError" class="error" role="alert">{{ formError }}</p>
        <form @submit.prevent="save">
          <fieldset class="form-grid" :disabled="busy">
            <label
              >编码<input
                v-model="draft.code"
                required
                maxlength="32"
                pattern="[A-Za-z][A-Za-z0-9-]{1,31}" /></label
            ><label
              >名称<input v-model="draft.name" required maxlength="100"
            /></label>
            <template v-if="tab === 'materials'"
              ><label
                >计量单位<input
                  v-model="draft.unit"
                  required
                  maxlength="16" /></label
              ><label
                >管理方式<select v-model="draft.trackingMode">
                  <option value="BATCH">按批次</option>
                  <option value="QUANTITY">按数量</option>
                </select></label
              ><label class="check"
                ><input v-model="draft.requireDateCode" type="checkbox" />Date
                Code 必填</label
              ><label class="check"
                ><input
                  v-model="draft.requireExpiry"
                  type="checkbox"
                />有效期必填</label
              ></template
            >
            <template v-if="tab === 'locations'"
              ><label
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
              ><label
                >区域类型<select v-model="draft.areaType">
                  <option
                    v-for="(name, key) in areaNames"
                    :key="key"
                    :value="key"
                  >
                    {{ name }}
                  </option>
                </select></label
              ></template
            >
          </fieldset>
          <div class="modal-actions">
            <button type="button" :disabled="busy" @click="dialog = false">
              取消</button
            ><button
              class="primary"
              :disabled="busy || (tab === 'locations' && !draft.warehouseId)"
            >
              {{ busy ? '保存中…' : '保存' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </main>
</template>
