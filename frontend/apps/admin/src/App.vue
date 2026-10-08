<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ApiError, request, refreshCsrf, type Session } from './api'
import './style.css'
const session = ref<Session | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const username = ref('admin')
const password = ref('')
function expired(event: Event) {
  session.value = null
  error.value =
    event instanceof CustomEvent && typeof event.detail === 'string'
      ? event.detail
      : '登录已过期，请重新登录。'
}
async function restore() {
  try {
    session.value = await request<Session>('/api/auth/me')
    await refreshCsrf()
  } catch (e) {
    error.value =
      e instanceof ApiError && e.status === 401 ? '' : (e as Error).message
  } finally {
    loading.value = false
  }
}
async function login() {
  busy.value = true
  error.value = ''
  try {
    await refreshCsrf()
    await request('/api/auth/login', {
      method: 'POST',
      body: new URLSearchParams({
        username: username.value,
        password: password.value,
      }),
    })
    password.value = ''
    await refreshCsrf()
    session.value = await request<Session>('/api/auth/me')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function logout() {
  busy.value = true
  error.value = ''
  try {
    await request('/api/auth/logout', { method: 'POST' })
    session.value = null
    password.value = ''
    await refreshCsrf()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(() => {
  window.addEventListener('session-expired', expired)
  void restore()
})
onUnmounted(() => window.removeEventListener('session-expired', expired))
</script>
<template>
  <main v-if="loading" class="login"><p role="status">正在连接 MDOP…</p></main>
  <main v-else-if="!session" class="login">
    <form class="login-card" @submit.prevent="login">
      <span class="brand">MDOP <small>制造运营平台</small></span>
      <h1>登录工作台</h1>
      <p class="muted">管理仓库、维护基础资料，开始采购收货。</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <label
        >账号<input
          v-model="username"
          name="username"
          autocomplete="username"
          required
          maxlength="64"
      /></label>
      <label
        >密码<input
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
      /></label>
      <button class="primary" :disabled="busy">
        {{ busy ? '正在登录…' : '登录' }}
      </button>
      <p class="hint">使用管理员分配的账号登录。首次初始化账号见部署说明。</p>
    </form>
  </main>
  <div v-else class="layout">
    <aside class="sidebar">
      <div class="brand">MDOP<small>制造运营平台</small></div>
      <p class="nav-caption">仓储工作台</p>
      <nav aria-label="主导航">
        <RouterLink to="/account">我的账号</RouterLink>
        <RouterLink
          v-if="session.authorities.includes('iam:manage')"
          to="/users"
          >用户与权限</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:freeze:read')
          "
          to="/freezes"
          >库存冻结</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:cross-transfer:read')
          "
          to="/cross-transfers"
          >跨仓调拨</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:sales:read')
          "
          to="/sales"
          >销售出库</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:finished:read')
          "
          to="/finished-goods"
          >成品入库</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:production:read')
          "
          to="/production"
          >生产消耗与退料</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:issue:read')
          "
          to="/issues"
          >生产领料</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:count:read')
          "
          to="/counts"
          >库存盘点</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:transfer:read')
          "
          to="/transfers"
          >仓内移库</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:inventory:read')
          "
          to="/inventory"
          >库存与追溯</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:return:read')
          "
          to="/purchase-returns"
          >不合格品退货</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:quality:read')
          "
          to="/quality"
          >质检与上架</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('warehouse:read') ||
            session.authorities.includes('ROLE_ADMIN')
          "
          to="/warehouses"
          >仓库管理</RouterLink
        ><RouterLink
          v-if="
            session.authorities.includes('warehouse:read') ||
            session.authorities.includes('ROLE_ADMIN')
          "
          to="/catalog"
          >基础资料</RouterLink
        ><RouterLink
          v-if="
            session.authorities.includes('wms:arrival:read') ||
            session.authorities.includes('ROLE_ADMIN')
          "
          to="/receiving"
          >采购收货</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('wms:correction:read')
          "
          to="/corrections"
          >差异与冲正</RouterLink
        >
        <RouterLink
          v-if="
            session.authorities.includes('ROLE_ADMIN') ||
            session.authorities.includes('integration:simulate')
          "
          to="/messages"
          >消息管理</RouterLink
        >
      </nav>
      <p class="sidebar-note">从主数据到业务记录<br />每次操作均可追溯</p>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span>制造运营 / 仓储</span>
        <div>
          {{ session.username }}
          <button :disabled="busy" @click="logout">退出登录</button>
        </div>
      </header>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <RouterView
        :authorities="session.authorities"
        :username="session.username"
      />
    </div>
  </div>
</template>
