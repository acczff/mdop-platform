<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'

const props = defineProps<{ authorities: string[]; username: string }>()
interface Material {
  id: number
  code: string
  name: string
  unit: string
  status: string
}
interface Component {
  material_id: number
  material_code: string
  material_name: string
  quantity: string
  unit: string
}
interface Bom {
  id: number
  product_id: number
  product_code: string
  product_name: string
  product_unit: string
  version_label: string
  base_quantity: string
  description: string
  status: string
  version: number
  copied_from_id: number | null
  published_by: string | null
  published_at: string | null
  disabled_by: string | null
  disabled_at: string | null
  components: Component[]
  versions: { id: number; version_label: string; status: string }[]
  history: {
    id: number
    action: string
    reason: string
    created_by: string
    created_at: string
    before_state: string | null
    after_state: string
  }[]
}
interface Pending {
  url: string
  method: string
  body: string
}
const base = '/api/v1/manufacturing/boms'
const storageKey = `mdop-bom-pending:${props.username}`
const result = ref<{ items: Bom[]; total: number }>()
const detail = ref<Bom>()
const materials = ref<Material[]>([])
const query = ref(''),
  status = ref(''),
  page = ref(0)
const loading = ref(false),
  busy = ref(false),
  locked = ref(false),
  mode = ref('')
const error = ref(''),
  detailError = ref(''),
  formError = ref(''),
  notice = ref('')
const reason = ref(''),
  copyLabel = ref('')
const pending = ref<Pending>()
const draft = ref({
  productId: 0,
  versionLabel: 'V1',
  baseQuantity: '1',
  description: '',
  components: [{ materialId: 0, quantity: '' }],
})
const writable = computed(() => props.authorities.includes('bom:write'))
const blocked = computed(
  () => busy.value || locked.value || !!pending.value || !!mode.value,
)
const labels: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  DISABLED: '已停用',
  create: '新建 BOM',
  edit: '编辑草稿',
  copy: '复制新版本',
  publish: '发布版本',
  disable: '停用版本',
  CREATE: '创建',
  EDIT: '编辑',
  COPY: '复制',
  PUBLISH: '发布',
  DISABLE: '停用',
}
let generation = 0,
  detailGeneration = 0
function label(value: string) {
  return labels[value] || value
}
function unit(id: number) {
  return materials.value.find((m) => m.id === id)?.unit || '基本单位'
}
function remember(value?: Pending) {
  if (value) sessionStorage.setItem(storageKey, JSON.stringify(value))
  else sessionStorage.removeItem(storageKey)
  pending.value = value
}
async function load(next = 0) {
  if (busy.value || locked.value) return
  const token = ++generation
  ++detailGeneration
  detail.value = undefined
  result.value = undefined
  detailError.value = ''
  error.value = ''
  loading.value = true
  page.value = next
  try {
    const [list, directory] = await Promise.all([
      request<{ items: Bom[]; total: number }>(
        `${base}?q=${encodeURIComponent(query.value)}&status=${status.value}&page=${next}&size=20`,
      ),
      request<Material[]>('/api/master-data/materials'),
    ])
    if (token === generation) {
      result.value = list
      materials.value = directory
    }
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}
async function openDetail(id: number) {
  if (blocked.value || loading.value) return
  const token = ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  try {
    const d = await request<Bom>(`${base}/${id}`)
    if (token === detailGeneration) detail.value = d
  } catch (e) {
    if (token === detailGeneration) detailError.value = (e as Error).message
  }
}
function open(value: string) {
  if (blocked.value || loading.value || !result.value || !writable.value) return
  mode.value = value
  reason.value = ''
  formError.value = ''
  copyLabel.value = ''
  const d = detail.value
  draft.value =
    value === 'edit' && d
      ? {
          productId: d.product_id,
          versionLabel: d.version_label,
          baseQuantity: d.base_quantity,
          description: d.description,
          components: d.components.map((c) => ({
            materialId: c.material_id,
            quantity: c.quantity,
          })),
        }
      : {
          productId: 0,
          versionLabel: 'V1',
          baseQuantity: '1',
          description: '',
          components: [{ materialId: 0, quantity: '' }],
        }
}
function close() {
  if (!busy.value && !pending.value) {
    mode.value = ''
    formError.value = ''
  }
}
async function send() {
  if (busy.value || pending.value || !writable.value) return
  const d = detail.value
  let url = base,
    method = 'POST'
  const body: Record<string, unknown> = {
    idempotencyKey: crypto.randomUUID(),
    reason: reason.value,
  }
  if (mode.value === 'create' || mode.value === 'edit') {
    Object.assign(body, draft.value)
    if (mode.value === 'edit' && d) {
      url += `/${d.id}`
      method = 'PUT'
      body.version = d.version
    }
  } else {
    if (!d) return
    url += `/${d.id}/${mode.value === 'copy' ? 'copy' : `actions/${mode.value}`}`
    body.version = d.version
    if (mode.value === 'copy') body.versionLabel = copyLabel.value
  }
  try {
    remember({ url, method, body: JSON.stringify(body) })
  } catch {
    formError.value = '无法保存重试凭据，本次请求未发送。请检查浏览器存储设置。'
    return
  }
  await retry()
}
async function retry() {
  if (locked.value || busy.value || !pending.value || !writable.value) return
  ++generation
  ++detailGeneration
  loading.value = false
  busy.value = true
  error.value = ''
  formError.value = ''
  notice.value = ''
  const original = pending.value
  let saved: Bom
  try {
    saved = await request<Bom>(original.url, {
      method: original.method,
      body: original.body,
    })
    remember()
    mode.value = ''
  } catch (e) {
    detail.value = undefined
    result.value = undefined
    mode.value = ''
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
        error.value = '无法清理重试凭据，请联系管理员核对；本页已停止新操作。'
        busy.value = false
        return
      }
      error.value = `操作被拒绝：${e.message}。请刷新核对后再操作。`
    } else {
      error.value = `结果待核对：${(e as Error).message}。请原样重试，不要另建版本。`
    }
    busy.value = false
    return
  }
  busy.value = false
  notice.value = `${saved.product_code} · ${saved.version_label} · ${label(saved.status)}，操作已保存`
  await load(page.value)
  if (!error.value) await openDetail(saved.id)
}
function snapshot(raw: string | null) {
  if (!raw) return '无前值'
  try {
    const d = JSON.parse(raw) as Bom
    return `${d.product_code} · ${d.product_name} · ${d.version_label} · ${label(d.status)}\n基准产量 ${d.base_quantity} ${d.product_unit}\n${d.components.map((c) => `${c.material_code} · ${c.material_name}：${c.quantity} ${c.unit}`).join('\n')}`
  } catch {
    return '快照无法显示，请核对原记录。'
  }
}
onMounted(() => {
  try {
    const stored = sessionStorage.getItem(storageKey)
    if (stored) {
      const p = JSON.parse(stored) as Pending
      if (
        !new RegExp(
          `^${base}(?:/[1-9][0-9]*(?:/copy|/actions/(?:publish|disable))?)?$`,
        ).test(p.url) ||
        !['POST', 'PUT'].includes(p.method) ||
        typeof p.body !== 'string'
      )
        throw Error('invalid')
      pending.value = p
    }
  } catch {
    locked.value = true
    error.value = '重试凭据无法读取，本页已停止新操作。请联系管理员核对。'
    return
  }
  void load()
})
onBeforeUnmount(() => {
  generation++
  detailGeneration++
})
</script>

<template>
  <main class="page bom-page">
    <div class="page-heading">
      <h1>BOM 版本</h1>
      <span v-if="!writable" class="muted">只读</span>
    </div>
    <p class="muted">定义单层产品用料，发布后保留版本依据。</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="pending" class="pending" role="status">
      有一笔结果待核对的操作，请保留原请求。
      <button :disabled="locked || busy || !writable" @click="retry">
        原样重试
      </button>
      <p v-if="!writable">当前账号无写权限，请联系管理员核对原操作。</p>
    </div>
    <form class="toolbar" @submit.prevent="load(0)">
      <label
        >搜索<input
          v-model="query"
          aria-label="搜索产品或版本"
          placeholder="产品编码、名称或版本"
          maxlength="100"
          :disabled="blocked"
      /></label>
      <label
        >状态<select v-model="status" :disabled="blocked">
          <option value="">全部状态</option>
          <option value="DRAFT">草稿</option>
          <option value="PUBLISHED">已发布</option>
          <option value="DISABLED">已停用</option>
        </select></label
      >
      <button :disabled="blocked || loading">查询</button>
      <button
        v-if="writable"
        type="button"
        class="primary"
        :disabled="blocked || loading || !result"
        @click="open('create')"
      >
        新建 BOM
      </button>
    </form>
    <div
      class="table-scroll"
      role="region"
      aria-label="BOM 版本列表，可横向滚动"
      tabindex="0"
    >
      <table>
        <thead>
          <tr>
            <th>产品</th>
            <th>版本</th>
            <th>基准产量</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in result?.items" :key="b.id">
            <td>
              {{ b.product_code }}<small>{{ b.product_name }}</small>
            </td>
            <td>{{ b.version_label }}</td>
            <td>{{ b.base_quantity }} {{ b.product_unit }}</td>
            <td>{{ label(b.status) }}</td>
            <td>
              <button :disabled="blocked || loading" @click="openDetail(b.id)">
                查看
              </button>
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="5">
              {{
                loading
                  ? '正在加载…'
                  : result
                    ? '暂无 BOM 版本'
                    : '数据未就绪，请查询重试'
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="toolbar">
      <span>共 {{ result?.total ?? 0 }} 条 · 第 {{ page + 1 }} 页</span
      ><button
        :disabled="blocked || loading || !result || page === 0"
        @click="load(page - 1)"
      >
        上一页</button
      ><button
        :disabled="
          blocked || loading || !result || (page + 1) * 20 >= result.total
        "
        @click="load(page + 1)"
      >
        下一页
      </button>
    </div>
    <p v-if="detailError" role="alert" class="error">
      详情读取失败：{{ detailError }}
    </p>
    <section v-if="detail" class="detail" aria-label="BOM 详情">
      <div class="page-heading">
        <h2>{{ detail.product_code }} · {{ detail.version_label }}</h2>
        <span>{{ label(detail.status) }}</span>
      </div>
      <p>
        {{ detail.product_name }} · 基准产量 {{ detail.base_quantity }}
        {{ detail.product_unit }}
      </p>
      <p>{{ detail.description }}</p>
      <div v-if="writable" class="toolbar">
        <button
          v-if="detail.status === 'DRAFT'"
          :disabled="blocked"
          @click="open('edit')"
        >
          编辑草稿
        </button>
        <button
          v-if="detail.status === 'DRAFT'"
          class="primary"
          :disabled="blocked"
          @click="open('publish')"
        >
          发布版本
        </button>
        <button :disabled="blocked" @click="open('copy')">复制新版本</button>
        <button
          v-if="detail.status === 'PUBLISHED'"
          :disabled="blocked"
          @click="open('disable')"
        >
          停用版本
        </button>
      </div>
      <p v-if="detail.published_at">
        发布：{{ detail.published_by }} · {{ detail.published_at }}
      </p>
      <p v-if="detail.disabled_at">
        停用：{{ detail.disabled_by }} · {{ detail.disabled_at }}
      </p>
      <p v-if="detail.copied_from_id">
        复制自
        <button :disabled="blocked" @click="openDetail(detail.copied_from_id)">
          BOM #{{ detail.copied_from_id }}
        </button>
      </p>
      <div
        class="table-scroll"
        role="region"
        aria-label="BOM 组件，可横向滚动"
        tabindex="0"
      >
        <table>
          <thead>
            <tr>
              <th>组件</th>
              <th>基准用量</th>
              <th>基本单位</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in detail.components" :key="c.material_id">
              <td>
                {{ c.material_code }}<small>{{ c.material_name }}</small>
              </td>
              <td>{{ c.quantity }}</td>
              <td>{{ c.unit }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="muted">
        用量对应上述基准产量。发布只确立生产依据，不生成工单、预占或扣减库存。
      </p>
      <details>
        <summary>同产品版本（最多 1000 条）</summary>
        <div class="versions">
          <button
            v-for="v in detail.versions"
            :key="v.id"
            :disabled="blocked || v.id === detail.id"
            @click="openDetail(v.id)"
          >
            {{ v.version_label }} · {{ label(v.status) }}
          </button>
        </div>
      </details>
      <details>
        <summary>操作历史（{{ detail.history.length }}，最多 1000 条）</summary>
        <article v-for="h in detail.history" :key="h.id">
          <strong>{{ label(h.action) }} · {{ h.created_by }}</strong>
          <p>{{ h.created_at }} · {{ h.reason }}</p>
          <details>
            <summary>变更前后</summary>
            <pre>{{ snapshot(h.before_state) }}</pre>
            <pre>{{ snapshot(h.after_state) }}</pre>
          </details>
        </article>
      </details>
    </section>
    <div v-if="mode" class="dialog-backdrop">
      <section
        class="dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="label(mode)"
        @keydown.esc="close"
      >
        <h2>{{ label(mode) }}</h2>
        <form @submit.prevent="send">
          <div v-if="mode === 'create' || mode === 'edit'" class="form-grid">
            <label
              >产品<select
                v-model="draft.productId"
                required
                :disabled="busy || mode === 'edit'"
              >
                <option :value="0" disabled>请选择产品</option>
                <option
                  v-for="m in materials"
                  :key="m.id"
                  :value="m.id"
                  :disabled="m.status !== 'ENABLED'"
                >
                  {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                </option>
              </select></label
            >
            <label
              >版本标识<input
                v-model="draft.versionLabel"
                required
                maxlength="32"
                pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,31}"
                :disabled="busy || mode === 'edit'"
            /></label>
            <label
              >基准产量（{{ unit(draft.productId) }}）<input
                v-model="draft.baseQuantity"
                required
                inputmode="decimal"
                :disabled="busy"
            /></label>
            <label
              >用途说明<textarea
                v-model="draft.description"
                required
                maxlength="500"
                :disabled="busy"
              />
            </label>
            <p class="muted">
              物料目录最多 1000 条。按基本单位填写，不自动合并重复组件。
            </p>
            <div
              v-for="(c, i) in draft.components"
              :key="i"
              class="component-row"
            >
              <label
                >组件 {{ i + 1
                }}<select v-model="c.materialId" required :disabled="busy">
                  <option :value="0" disabled>请选择组件</option>
                  <option
                    v-for="m in materials"
                    :key="m.id"
                    :value="m.id"
                    :disabled="
                      m.status !== 'ENABLED' || m.id === draft.productId
                    "
                  >
                    {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                  </option>
                </select></label
              >
              <label
                >用量 {{ i + 1
                }}<input
                  v-model="c.quantity"
                  required
                  inputmode="decimal"
                  :disabled="busy"
              /></label>
              <button
                type="button"
                :disabled="busy || draft.components.length === 1"
                @click="draft.components.splice(i, 1)"
              >
                移除
              </button>
            </div>
            <button
              type="button"
              :disabled="busy || draft.components.length >= 100"
              @click="draft.components.push({ materialId: 0, quantity: '' })"
            >
              添加组件
            </button>
          </div>
          <label v-if="mode === 'copy'"
            >新版本标识<input
              v-model="copyLabel"
              required
              maxlength="32"
              pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,31}"
              :disabled="busy"
          /></label>
          <p v-if="mode === 'copy'">
            复制为同产品的新草稿，原版本不变；发布前请核对当前物料及单位。
          </p>
          <p v-if="mode === 'publish'">
            发布后内容不可修改，后续变更须复制新版本。系统将校验物料、基本单位和循环引用。
          </p>
          <p v-if="mode === 'disable'">
            停用后不得用于新生产授权，原内容和历史保留，不能恢复为草稿。
          </p>
          <label
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="500"
              :disabled="busy"
            />
          </label>
          <p v-if="formError" role="alert" class="error">{{ formError }}</p>
          <div class="toolbar">
            <button type="button" :disabled="busy || !!pending" @click="close">
              取消</button
            ><button class="primary" :disabled="busy || !!pending">
              {{
                busy
                  ? '处理中…'
                  : mode === 'create' || mode === 'edit'
                    ? '保存草稿'
                    : `确认${label(mode)}`
              }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </main>
</template>

<style scoped>
.bom-page {
  max-width: 1200px;
}
.page-heading,
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar {
  margin: 16px 0;
}
label {
  display: grid;
  gap: 6px;
}
.muted,
small {
  color: #64748b;
}
small {
  display: block;
  margin-top: 4px;
}
.error {
  color: #b42318;
}
.pending {
  padding: 12px;
  border: 1px solid #b7791f;
  background: #fffaf0;
  border-radius: 8px;
}
.table-scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  min-width: 560px;
  border-collapse: collapse;
  text-align: left;
}
th,
td {
  padding: 12px;
  border-bottom: 1px solid #e2e8f0;
}
th {
  color: #64748b;
  font-weight: 500;
}
input,
select,
textarea {
  box-sizing: border-box;
  max-width: 100%;
  padding: 9px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font: inherit;
  background: white;
  color: inherit;
}
textarea {
  min-height: 64px;
  resize: vertical;
}
button {
  white-space: nowrap;
  padding: 8px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: white;
  color: inherit;
  cursor: pointer;
  font: inherit;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
button.primary {
  color: white;
  background: #6d28d9;
  border-color: #6d28d9;
}
.detail {
  border-top: 1px solid #e2e8f0;
  margin-top: 24px;
  padding-top: 16px;
}
details {
  margin: 16px 0;
}
summary {
  cursor: pointer;
}
.versions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}
article {
  border-bottom: 1px solid #e2e8f0;
  padding: 12px 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  background: #f8fafc;
  padding: 12px;
}
.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  background: #0f172a66;
  display: grid;
  place-items: center;
  padding: 16px;
}
.dialog {
  box-sizing: border-box;
  width: min(720px, 100%);
  max-height: 90dvh;
  overflow: auto;
  background: white;
  padding: 24px;
  border-radius: 10px;
}
.form-grid {
  display: grid;
  gap: 16px;
  margin-bottom: 16px;
}
.component-row {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) auto;
  gap: 8px;
  align-items: end;
}
@media (max-width: 600px) {
  .component-row {
    grid-template-columns: 1fr;
  }
  .dialog {
    padding: 16px;
  }
  th,
  td {
    padding: 8px;
  }
  .toolbar label {
    width: 100%;
  }
}
</style>
