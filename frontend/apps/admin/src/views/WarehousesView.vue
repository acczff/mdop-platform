<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ApiError,
  request,
  purposes,
  forms,
  categories,
  statuses,
  type Page,
  type Warehouse,
} from '../api'
const props = defineProps<{ authorities: string[] }>()
const canEdit = computed(
  () =>
    props.authorities.includes('ROLE_ADMIN') ||
    props.authorities.includes('masterdata:write'),
)
const data = ref<Page<Warehouse>>({
  items: [],
  page: 1,
  size: 20,
  totalElements: 0,
  totalPages: 0,
})
const filter = reactive({
  keyword: '',
  purpose: '',
  form: '',
  managementCategory: '',
  status: '',
})
const page = ref(1),
  loading = ref(false),
  busy = ref(false)
const error = ref(''),
  message = ref(''),
  formError = ref('')
const dialog = ref(false),
  editing = ref<Warehouse | null>(null)
const draft = reactive({
  code: '',
  name: '',
  purpose: 'RAW_MATERIAL',
  form: 'PHYSICAL',
  managementCategory: 'GENERAL',
  remark: '',
})
let generation = 0
async function load() {
  const current = ++generation
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ page: String(page.value), size: '20' })
    Object.entries(filter).forEach(([key, value]) => {
      if (value) query.set(key, value)
    })
    const result = await request<Page<Warehouse>>(
      `/api/master-data/warehouses?${query}`,
    )
    if (current === generation) data.value = result
  } catch (e) {
    if (current === generation) error.value = (e as Error).message
  } finally {
    if (current === generation) loading.value = false
  }
}
async function search() {
  page.value = 1
  await load()
}
async function open(item?: Warehouse) {
  formError.value = ''
  message.value = ''
  if (item) {
    try {
      const latest = await request<Warehouse>(
        `/api/master-data/warehouses/${item.id}`,
      )
      editing.value = latest
      Object.assign(draft, latest, { remark: latest.remark || '' })
    } catch (e) {
      error.value = (e as Error).message
      return
    }
  } else {
    editing.value = null
    Object.assign(draft, {
      code: '',
      name: '',
      purpose: 'RAW_MATERIAL',
      form: 'PHYSICAL',
      managementCategory: 'GENERAL',
      remark: '',
    })
  }
  dialog.value = true
}
async function save() {
  busy.value = true
  formError.value = ''
  try {
    const payload = {
      name: draft.name,
      purpose: draft.purpose,
      form: draft.form,
      managementCategory: draft.managementCategory,
      remark: draft.remark,
    }
    await request(
      editing.value
        ? `/api/master-data/warehouses/${editing.value.id}`
        : '/api/master-data/warehouses',
      {
        method: editing.value ? 'PUT' : 'POST',
        body: JSON.stringify(
          editing.value
            ? { ...payload, version: editing.value.version }
            : { ...payload, code: draft.code },
        ),
      },
    )
    dialog.value = false
    message.value = '仓库已保存'
    await load()
  } catch (e) {
    formError.value = (e as Error).message
    if (e instanceof ApiError && e.status === 409)
      formError.value += '。如为版本冲突，请关闭表单并重新打开。'
  } finally {
    busy.value = false
  }
}
const statusTarget = ref<Warehouse | null>(null)
async function changeStatus() {
  if (!statusTarget.value) return
  const item = statusTarget.value
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    await request(`/api/master-data/warehouses/${item.id}/status`, {
      method: 'PUT',
      body: JSON.stringify({
        status: item.status === 'ENABLED' ? 'DISABLED' : 'ENABLED',
        version: item.version,
      }),
    })
    message.value = '仓库状态已更新'
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    statusTarget.value = null
    busy.value = false
  }
  const failure = error.value
  await load()
  if (failure) error.value = failure
}
function turnPage(delta: number) {
  page.value += delta
  void load()
}
onMounted(load)
</script>
<template>
  <main class="page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">MASTER DATA</p>
        <h1>仓库管理</h1>
        <p class="muted">
          统一维护仓库用途、形态与状态，为收货和库存提供基础。
        </p>
      </div>
      <button v-if="canEdit" class="primary" @click="open()">
        ＋ 新增仓库
      </button>
    </div>
    <p v-if="error" role="alert" class="error">
      {{ error }} <button @click="load">重新加载</button>
    </p>
    <p v-if="message" role="status" class="success">{{ message }}</p>
    <section class="panel">
      <form class="filters" @submit.prevent="search">
        <label class="grow"
          >搜索仓库<input v-model="filter.keyword" placeholder="编码或名称"
        /></label>
        <label
          >用途<select v-model="filter.purpose">
            <option value="">全部用途</option>
            <option v-for="(text, key) in purposes" :key="key" :value="key">
              {{ text }}
            </option>
          </select></label
        >
        <label
          >形态<select v-model="filter.form">
            <option value="">全部形态</option>
            <option v-for="(text, key) in forms" :key="key" :value="key">
              {{ text }}
            </option>
          </select></label
        >
        <label
          >管理类别<select v-model="filter.managementCategory">
            <option value="">全部类别</option>
            <option v-for="(text, key) in categories" :key="key" :value="key">
              {{ text }}
            </option>
          </select></label
        >
        <label
          >状态<select v-model="filter.status">
            <option value="">全部状态</option>
            <option v-for="(text, key) in statuses" :key="key" :value="key">
              {{ text }}
            </option>
          </select></label
        ><button :disabled="loading">查询</button>
      </form>
      <div class="table-wrap" :aria-busy="loading">
        <table>
          <thead>
            <tr>
              <th>仓库</th>
              <th>用途 / 形态</th>
              <th>管理类别</th>
              <th>状态</th>
              <th>最近修改</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in data.items" :key="item.id">
              <td>
                <strong>{{ item.name }}</strong
                ><small>{{ item.code }}</small>
              </td>
              <td>
                {{ purposes[item.purpose]
                }}<small>{{ forms[item.form] }}</small>
              </td>
              <td>{{ categories[item.managementCategory] }}</td>
              <td>
                <span
                  class="badge"
                  :class="{ disabled: item.status === 'DISABLED' }"
                  >{{ statuses[item.status] }}</span
                >
              </td>
              <td>
                {{ item.updatedBy
                }}<small>{{ new Date(item.updatedAt).toLocaleString() }}</small>
              </td>
              <td class="actions">
                <button @click="open(item)">
                  {{ canEdit ? '编辑' : '详情' }}</button
                ><button
                  v-if="canEdit"
                  :disabled="busy"
                  @click="statusTarget = item"
                >
                  {{ item.status === 'ENABLED' ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
            <tr v-if="!data.items.length">
              <td colspan="6" class="empty">
                {{
                  loading ? '正在加载…' : '暂无仓库，请新增仓库或调整查询条件。'
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pagination">
        <span>共 {{ data.totalElements }} 个仓库</span>
        <div>
          <button :disabled="page <= 1 || loading" @click="turnPage(-1)">
            上一页
          </button>
          {{ page }} / {{ Math.max(1, data.totalPages) }}
          <button
            :disabled="page >= data.totalPages || loading"
            @click="turnPage(1)"
          >
            下一页
          </button>
        </div>
      </footer>
    </section>
    <div v-if="dialog" class="overlay" @keydown.esc="!busy && (dialog = false)">
      <section
        class="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="warehouse-title"
      >
        <h2 id="warehouse-title">{{ editing ? '仓库资料' : '新增仓库' }}</h2>
        <p v-if="editing?.status === 'ENABLED'" class="hint">
          启用期间仅允许修改名称和备注；调整结构属性前请先停用。
        </p>
        <p v-if="formError" role="alert" class="error">{{ formError }}</p>
        <form @submit.prevent="save">
          <fieldset :disabled="busy || !canEdit" class="form-grid">
            <label
              >仓库编码<input
                v-model="draft.code"
                :disabled="!!editing"
                required
                pattern="[A-Za-z][A-Za-z0-9-]{1,31}"
                maxlength="32"
                placeholder="例如 WH-RAW-01" /></label
            ><label
              >仓库名称<input v-model="draft.name" required maxlength="100"
            /></label>
            <label
              >用途<select
                v-model="draft.purpose"
                :disabled="editing?.status === 'ENABLED'"
              >
                <option v-for="(text, key) in purposes" :key="key" :value="key">
                  {{ text }}
                </option>
              </select></label
            >
            <label
              >形态<select
                v-model="draft.form"
                :disabled="editing?.status === 'ENABLED'"
              >
                <option v-for="(text, key) in forms" :key="key" :value="key">
                  {{ text }}
                </option>
              </select></label
            >
            <label
              >管理类别<select
                v-model="draft.managementCategory"
                :disabled="editing?.status === 'ENABLED'"
              >
                <option
                  v-for="(text, key) in categories"
                  :key="key"
                  :value="key"
                >
                  {{ text }}
                </option>
              </select></label
            >
            <label class="span-2"
              >备注<textarea v-model="draft.remark" maxlength="500" rows="3" />
            </label>
          </fieldset>
          <div class="modal-actions">
            <button type="button" :disabled="busy" @click="dialog = false">
              关闭</button
            ><button v-if="canEdit" class="primary" :disabled="busy">
              {{ busy ? '保存中…' : '保存仓库' }}
            </button>
          </div>
        </form>
      </section>
    </div>
    <div v-if="statusTarget" class="overlay">
      <section
        class="modal compact"
        role="dialog"
        aria-modal="true"
        aria-labelledby="status-title"
      >
        <h2 id="status-title">
          确认{{ statusTarget.status === 'ENABLED' ? '停用' : '启用' }}仓库
        </h2>
        <p>{{ statusTarget.name }}（{{ statusTarget.code }}）</p>
        <p class="muted">停用后将无法用于新的收货业务。</p>
        <div class="modal-actions">
          <button :disabled="busy" @click="statusTarget = null">取消</button
          ><button class="primary" :disabled="busy" @click="changeStatus">
            确认
          </button>
        </div>
      </section>
    </div>
  </main>
</template>
