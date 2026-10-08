<script setup lang="ts">
import { ref } from 'vue'
import { request } from '../api'
defineProps<{ username: string; authorities: string[] }>()
const oldPassword = ref(''),
  newPassword = ref(''),
  confirm = ref(''),
  error = ref(''),
  busy = ref(false)
async function save() {
  if (busy.value) return
  error.value = ''
  if (newPassword.value !== confirm.value) {
    error.value = '两次新密码不一致'
    return
  }
  busy.value = true
  try {
    await request('/api/auth/password', {
      method: 'POST',
      body: JSON.stringify({
        oldPassword: oldPassword.value,
        newPassword: newPassword.value,
      }),
    })
    window.dispatchEvent(
      new CustomEvent('session-expired', {
        detail: '密码已修改，请使用新密码重新登录。',
      }),
    )
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    oldPassword.value = ''
    newPassword.value = ''
    confirm.value = ''
    busy.value = false
  }
}
</script>
<template>
  <section class="page">
    <h1>我的账号</h1>
    <p>当前账号：{{ username }}</p>
    <p class="muted">
      业务入口由角色权限决定；需要调整权限时请联系系统管理员。
    </p>
    <form class="login-card" @submit.prevent="save">
      <h2>修改密码</h2>
      <p>修改后所有原登录失效，需要重新登录。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <label
        >原密码<input
          v-model="oldPassword"
          type="password"
          required
          autocomplete="current-password"
          :disabled="busy" /></label
      ><label
        >新密码<input
          v-model="newPassword"
          type="password"
          required
          minlength="12"
          maxlength="72"
          autocomplete="new-password"
          :disabled="busy" /></label
      ><label
        >确认新密码<input
          v-model="confirm"
          type="password"
          required
          minlength="12"
          maxlength="72"
          autocomplete="new-password"
          :disabled="busy"
      /></label>
      <p>密码至少12位，UTF-8编码不超过72字节。</p>
      <button class="primary" :disabled="busy">
        {{ busy ? '提交中…' : '修改密码' }}
      </button>
    </form>
  </section>
</template>
