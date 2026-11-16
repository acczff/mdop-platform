<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useListPosition } from '@/shared/composables/useListPosition'
import ResultPager from '@/shared/components/ResultPager.vue'
import { quantity as displayQuantity } from '@/shared/presentation'
import { ApiError, request } from '@/shared/api'
import {
  bomLabel,
  type BomDraft,
  type BomMaterial,
  type BomRecord,
  type PendingBomRequest,
} from '../bomTypes'
import BomDetail from '../components/BomDetail.vue'
import BomModal from '../components/BomModal.vue'

const props = defineProps<{ authorities: string[]; username: string }>()

const base = '/api/v1/manufacturing/boms'
const storageKey = `mdop-bom-pending:${props.username}`
const result = ref<{ items: BomRecord[]; total: number }>()
const detail = ref<BomRecord>()
const { pageRoot, rememberList, focusDetail, restoreList } = useListPosition()
const materials = ref<BomMaterial[]>([])
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
const pending = ref<PendingBomRequest>()
const draft = ref<BomDraft>({
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
let generation = 0,
  detailGeneration = 0

function returnToList() {
  if (blocked.value) return
  ++detailGeneration
  detail.value = undefined
  void restoreList()
}

function remember(value?: PendingBomRequest) {
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
      request<{ items: BomRecord[]; total: number }>(
        `${base}?q=${encodeURIComponent(query.value)}&status=${status.value}&page=${next}&size=20`,
      ),
      request<BomMaterial[]>('/api/master-data/materials'),
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
  if (!detail.value) rememberList()
  const token = ++detailGeneration
  detail.value = undefined
  detailError.value = ''
  try {
    const d = await request<BomRecord>(`${base}/${id}`)
    if (token === detailGeneration) {
      detail.value = d
      void focusDetail()
    }
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

async function send(submittedDraft: BomDraft) {
  if (busy.value || pending.value || !writable.value) return
  draft.value = submittedDraft
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
  let saved: BomRecord
  try {
    saved = await request<BomRecord>(original.url, {
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
  notice.value = `${saved.product_code} · ${saved.version_label} · ${bomLabel(saved.status)}，操作已保存`
  await load(page.value)
  if (!error.value) await openDetail(saved.id)
}

onMounted(() => {
  try {
    const stored = sessionStorage.getItem(storageKey)
    if (stored) {
      const p = JSON.parse(stored) as PendingBomRequest
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
  <main ref="pageRoot" class="page bom-page">
    <div v-show="!detail" class="page-heading">
      <h1>BOM 版本</h1>
      <span v-if="!writable" class="muted">只读</span>
    </div>
    <p v-show="!detail" class="muted">定义单层产品用料，发布后保留版本依据。</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div v-if="pending" class="pending" role="status">
      有一笔结果待核对的操作，请保留原请求。
      <button :disabled="locked || busy || !writable" @click="retry">
        原样重试
      </button>
      <p v-if="!writable">当前账号无写权限，请联系管理员核对原操作。</p>
    </div>
    <div v-show="!detail" class="panel business-list">
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
              <td>
                {{ displayQuantity(b.base_quantity) }} {{ b.product_unit }}
              </td>
              <td>{{ bomLabel(b.status) }}</td>
              <td>
                <button
                  :disabled="blocked || loading"
                  @click="openDetail(b.id)"
                >
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
      <ResultPager
        v-if="result"
        :total="result.total"
        :page="page"
        :disabled="blocked || loading"
        @change="load"
      />
      <p v-if="detailError" role="alert" class="error">
        详情读取失败：{{ detailError }}
      </p>
    </div>
    <BomDetail
      v-if="detail"
      :detail="detail"
      :blocked="blocked"
      :writable="writable"
      @return-to-list="returnToList"
      @open-detail="openDetail"
      @open-action="open"
    />
    <BomModal
      v-if="mode"
      v-model:copy-label="copyLabel"
      v-model:reason="reason"
      :mode="mode"
      :initial-draft="draft"
      :materials="materials"
      :form-error="formError"
      :busy="busy"
      :pending="pending"
      @close="close"
      @send="send"
    />
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
select {
  box-sizing: border-box;
  max-width: 100%;
  padding: 9px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font: inherit;
  background: white;
  color: inherit;
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
@media (max-width: 600px) {
  th,
  td {
    padding: 8px;
  }
  .toolbar label {
    width: 100%;
  }
}
</style>
