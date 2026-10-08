<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, request } from '../api'
const props = defineProps<{ authorities: string[]; username: string }>()
interface User {
  id: number
  username: string
  displayName: string
  enabled: boolean
  version: number
  roles: string[]
  warehouseIds: number[]
  authorities: string[]
}
interface Role {
  code: string
  name: string
  permissions: string[]
}
interface Audit {
  id: number
  action: string
  actor: string
  reason: string
  beforeState: string | null
  afterState: string
  createdAt: string
}
const allowed = computed(() => props.authorities.includes('iam:manage'))
const users = ref<User[]>([]),
  roles = ref<Role[]>([]),
  warehouses = ref<{ id: number; code: string; name: string }[]>([])
const page = ref(0),
  total = ref(0),
  busy = ref(false),
  ready = ref(false),
  uncertain = ref(false)
const error = ref(''),
  notice = ref(''),
  selected = ref<User>(),
  mode = ref<'create' | 'access' | 'status' | 'password' | 'audit'>()
const newUsername = ref(''),
  displayName = ref(''),
  password = ref(''),
  reason = ref(''),
  roleCodes = ref<string[]>([]),
  warehouseIds = ref<number[]>([]),
  audits = ref<Audit[]>([])
const actionNames: Record<string, string> = {
  CREATE: '新增账号',
  ACCESS_CHANGE: '调整角色与仓库',
  ENABLE: '启用账号',
  DISABLE: '停用账号',
  PASSWORD_RESET: '管理员重置密码',
  PASSWORD_CHANGE: '本人修改密码',
}
function roleName(code: string) {
  return (
    roles.value.find((r) => r.code === code)?.name ||
    (code === 'IMPORTED' ? '原配置导入（待分配角色）' : code)
  )
}
function invalidateList() {
  ready.value = false
  users.value = []
  total.value = 0
  roles.value = []
  warehouses.value = []
}
async function load() {
  invalidateList()
  const [result, options] = await Promise.all([
    request<{ items: User[]; totalElements: number }>(
      `/api/iam/users?page=${page.value}`,
    ),
    request<{
      roles: Role[]
      warehouses: { id: number; code: string; name: string }[]
    }>('/api/iam/options'),
  ])
  users.value = result.items
  total.value = result.totalElements
  roles.value = options.roles
  warehouses.value = options.warehouses
  ready.value = true
}
async function refresh(next = page.value) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  page.value = next
  try {
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function open(action: typeof mode.value, user?: User) {
  if (busy.value || !ready.value) return
  error.value = ''
  notice.value = ''
  uncertain.value = false
  selected.value = user
  mode.value = action
  newUsername.value = ''
  displayName.value = ''
  password.value = ''
  reason.value = ''
  audits.value = []
  roleCodes.value = user ? [...user.roles] : ['READER']
  warehouseIds.value = user ? [...user.warehouseIds] : []
  if (action === 'audit' && user) {
    busy.value = true
    try {
      audits.value = await request<Audit[]>(`/api/iam/users/${user.id}/audit`)
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      busy.value = false
    }
  }
}
function close() {
  if (!busy.value) {
    mode.value = undefined
    password.value = ''
    selected.value = undefined
  }
}
async function save() {
  if (busy.value || uncertain.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  let saved = false
  try {
    const user = selected.value
    const body =
      mode.value === 'create'
        ? {
            username: newUsername.value,
            displayName: displayName.value,
            password: password.value,
            roles: roleCodes.value,
            warehouseIds: warehouseIds.value,
            reason: reason.value,
          }
        : mode.value === 'access'
          ? {
              version: user!.version,
              roles: roleCodes.value,
              warehouseIds: warehouseIds.value,
              reason: reason.value,
            }
          : mode.value === 'status'
            ? {
                version: user!.version,
                enabled: !user!.enabled,
                reason: reason.value,
              }
            : {
                version: user!.version,
                password: password.value,
                reason: reason.value,
              }
    await request(
      mode.value === 'create'
        ? '/api/iam/users'
        : `/api/iam/users/${user!.id}/${mode.value}`,
      { method: 'POST', body: JSON.stringify(body) },
    )
    saved = true
    mode.value = undefined
    selected.value = undefined
    notice.value = '操作成功。账号停用、权限或密码变更会使原登录失效。'
    await load()
  } catch (e) {
    error.value = saved
      ? `操作已成功，但列表刷新失败：${(e as Error).message}`
      : (e as Error).message
    if (
      !saved &&
      (!(e instanceof ApiError) ||
        e.status === 0 ||
        e.status === 409 ||
        e.status >= 500)
    ) {
      uncertain.value = true
      invalidateList()
    }
  } finally {
    password.value = ''
    busy.value = false
  }
}
onMounted(() => {
  if (allowed.value) void refresh()
})
</script>
<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <h1>用户与权限</h1>
        <p class="muted">
          管理账号、固定角色与仓库范围。账号名称不可修改，历史操作永久保留。
        </p>
      </div>
    </header>
    <p v-if="!allowed" role="alert">无用户管理权限，请联系系统管理员。</p>
    <template v-else>
      <p v-if="error && !mode" role="alert" class="error">{{ error }}</p>
      <p v-if="notice" role="status" class="success">{{ notice }}</p>
      <div class="toolbar">
        <button
          class="primary"
          :disabled="busy || !ready"
          @click="open('create')"
        >
          新增账号</button
        ><button :disabled="busy || !!mode" @click="refresh()">刷新列表</button
        ><span v-if="busy" role="status">处理中…</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>账号 / 姓名</th>
              <th>角色</th>
              <th>仓库范围</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>
                {{ u.username }}<br /><small>{{ u.displayName }}</small>
              </td>
              <td>{{ u.roles.map(roleName).join('、') }}</td>
              <td>
                {{
                  u.warehouseIds
                    .map(
                      (id) =>
                        warehouses.find((w) => w.id === id)?.name ||
                        `仓库 ${id}`,
                    )
                    .join('、') || '未分配业务仓库'
                }}
              </td>
              <td>{{ u.enabled ? '启用' : '停用' }}</td>
              <td>
                <button
                  :disabled="busy || !ready || u.username === props.username"
                  @click="open('access', u)"
                >
                  分配角色
                </button>
                <button :disabled="busy || !ready" @click="open('status', u)">
                  {{ u.enabled ? '停用' : '启用' }}
                </button>
                <button :disabled="busy || !ready" @click="open('password', u)">
                  重置密码
                </button>
                <button :disabled="busy || !ready" @click="open('audit', u)">
                  审计记录
                </button>
              </td>
            </tr>
            <tr v-if="ready && !users.length">
              <td colspan="5">暂无账号</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="toolbar">
        <button
          :disabled="busy || !ready || !!mode || page === 0"
          @click="refresh(page - 1)"
        >
          上一页</button
        ><span v-if="ready">第 {{ page + 1 }} 页 · 共 {{ total }} 个账号</span
        ><span v-else>{{
          busy ? '正在读取账号列表…' : '账号列表未就绪，请刷新后操作。'
        }}</span
        ><button
          :disabled="busy || !ready || !!mode || (page + 1) * 50 >= total"
          @click="refresh(page + 1)"
        >
          下一页
        </button>
      </div>
      <div v-if="mode" class="overlay">
        <section
          class="modal wide"
          role="dialog"
          aria-modal="true"
          aria-labelledby="iam-dialog-title"
        >
          <h2 id="iam-dialog-title">
            {{
              mode === 'create'
                ? '新增账号'
                : mode === 'access'
                  ? '分配角色与仓库'
                  : mode === 'status'
                    ? selected!.enabled
                      ? '停用账号'
                      : '启用账号'
                    : mode === 'password'
                      ? '重置密码'
                      : '账号审计记录'
            }}
            <small>{{ selected?.username }}</small>
          </h2>
          <template v-if="mode === 'audit'"
            ><p>
              最近 100
              条记录，时间按当前设备时区显示；密码及密码摘要不进入审计快照。
            </p>
            <p v-if="busy">正在读取…</p>
            <article v-for="a in audits" :key="a.id">
              <p>
                {{ new Date(a.createdAt).toLocaleString('zh-CN') }} ·
                {{ actionNames[a.action] || a.action }} · {{ a.actor }}
              </p>
              <p>{{ a.reason }}</p>
              <details>
                <summary>查看变更前后</summary>
                <pre>{{ a.beforeState || '新增账号' }}</pre>
                <pre>{{ a.afterState }}</pre>
              </details>
            </article>
            <p v-if="!busy && !audits.length && !error">暂无记录</p>
            <button :disabled="busy" @click="close">关闭</button></template
          >
          <form v-else @submit.prevent="save">
            <template v-if="mode === 'create'"
              ><label
                >账号<input
                  v-model="newUsername"
                  required
                  maxlength="64"
                  pattern="[A-Za-z][A-Za-z0-9._-]{0,63}"
                  autocomplete="off"
                  :disabled="busy" /></label
              ><label
                >姓名<input
                  v-model="displayName"
                  required
                  maxlength="80"
                  :disabled="busy" /></label
            ></template>
            <label v-if="mode === 'create' || mode === 'password'"
              >{{ mode === 'create' ? '初始密码' : '新密码'
              }}<input
                v-model="password"
                type="password"
                required
                minlength="12"
                maxlength="72"
                autocomplete="new-password"
                :disabled="busy"
              /><small
                >至少12位，UTF-8编码不超过72字节；请通过安全渠道告知本人。</small
              ></label
            >
            <template v-if="mode === 'create' || mode === 'access'">
              <p>
                系统管理员不能兼任业务角色；作业与审批使用不同账号。基础资料管理不受业务仓库范围限制。
              </p>
              <p v-if="roleCodes.includes('IMPORTED')" class="hint">
                原配置权限会在保存时被所选角色替换，请先取消“原配置导入”并选择新角色。
              </p>
              <label v-if="roleCodes.includes('IMPORTED')"
                ><input
                  v-model="roleCodes"
                  type="checkbox"
                  value="IMPORTED"
                  :disabled="busy"
                />原配置导入</label
              >
              <fieldset :disabled="busy">
                <legend>角色</legend>
                <label v-for="r in roles" :key="r.code"
                  ><input
                    v-model="roleCodes"
                    type="checkbox"
                    :value="r.code"
                  />{{ r.name }}</label
                >
              </fieldset>
              <fieldset :disabled="busy">
                <legend>允许访问的业务仓库</legend>
                <p v-if="!warehouses.length">
                  暂无仓库，可先创建账号，再由基础资料管理员维护仓库。
                </p>
                <label v-for="w in warehouses" :key="w.id"
                  ><input
                    v-model="warehouseIds"
                    type="checkbox"
                    :value="w.id"
                  />{{ w.code }} · {{ w.name }}</label
                >
              </fieldset>
            </template>
            <p v-if="mode === 'status'">
              {{
                selected!.enabled
                  ? '停用后不能登录，已有会话在下一次请求时失效。'
                  : '启用后需要重新登录，旧会话不会恢复。'
              }}
            </p>
            <label
              >变更原因<textarea
                v-model="reason"
                required
                maxlength="300"
                :disabled="busy"
              />
            </label>
            <p v-if="uncertain" role="alert">
              请关闭窗口并刷新列表核对最新状态后再操作。
            </p>
            <div class="toolbar">
              <button class="primary" :disabled="busy || uncertain">保存</button
              ><button type="button" :disabled="busy" @click="close">
                取消
              </button>
            </div>
          </form>
          <p v-if="error" role="alert" class="error">{{ error }}</p>
        </section>
      </div>
    </template>
  </section>
</template>
<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 16px 0;
}
.modal form {
  display: grid;
  gap: 14px;
}
td button {
  margin: 3px;
}

fieldset {
  margin: 16px 0;
  max-height: 220px;
  overflow: auto;
}
fieldset label {
  display: flex;
  align-items: center;
  gap: 8px;
}
input[type='checkbox'] {
  width: auto;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
article {
  border-bottom: 1px solid #ddd;
  padding: 8px 0;
}
</style>
