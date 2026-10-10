<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { navigation } from '../navigation'

const props = defineProps<{
  authorities: string[]
  username: string
  busy: boolean
}>()
defineEmits<{ logout: [] }>()
const route = useRoute()
const media = window.matchMedia('(max-width: 1100px)')
const narrow = ref(media.matches)
const navOpen = ref(!media.matches)
const expanded = ref<Record<string, boolean>>({})
const toggle = ref<HTMLButtonElement>()
const sidebar = ref<HTMLElement>()
const content = ref<HTMLDivElement>()
const account = ref<HTMLDetailsElement>()
const groups = computed(() =>
  navigation
    .map((group) => ({
      ...group,
      items: group.items.filter(
        (item) =>
          props.authorities.includes(item.permission) ||
          (item.legacyAdmin !== false &&
            props.authorities.includes('ROLE_ADMIN')),
      ),
    }))
    .filter((group) => group.items.length),
)
const activeGroup = computed(() =>
  groups.value.find((group) =>
    group.items.some((item) => item.path === route.path),
  ),
)
const pageName = computed(
  () =>
    activeGroup.value?.items.find((item) => item.path === route.path)?.name ||
    '我的账号',
)
function closeAccount() {
  if (account.value) account.value.open = false
}
function dismissAccount() {
  closeAccount()
  account.value?.querySelector('summary')?.focus()
}
function outsideAccount(event: PointerEvent) {
  if (event.target instanceof Node && !account.value?.contains(event.target))
    closeAccount()
}
function accountBlur(event: FocusEvent) {
  if (
    !(event.relatedTarget instanceof Node) ||
    !account.value?.contains(event.relatedTarget)
  )
    closeAccount()
}
function resize() {
  const hidingFocusedNavigation =
    media.matches && sidebar.value?.contains(document.activeElement)
  narrow.value = media.matches
  navOpen.value = !media.matches
  if (hidingFocusedNavigation) void nextTick(() => toggle.value?.focus())
}
function toggleNavigation() {
  navOpen.value = !navOpen.value
  if (narrow.value && navOpen.value)
    void nextTick(() =>
      sidebar.value?.querySelector<HTMLElement>('button, a')?.focus(),
    )
}
function toggleGroup(name: string) {
  expanded.value = expanded.value[name] ? {} : { [name]: true }
}
function closeNavigation() {
  navOpen.value = false
  void nextTick(() => toggle.value?.focus())
}
function followLink() {
  closeAccount()
  if (narrow.value) navOpen.value = false
  void nextTick(() => content.value?.focus())
}
watch(
  () => route.path,
  () => {
    expanded.value = activeGroup.value ? { [activeGroup.value.name]: true } : {}
    if (narrow.value) navOpen.value = false
    closeAccount()
  },
  { immediate: true },
)
onMounted(() => {
  media.addEventListener('change', resize)
  document.addEventListener('pointerdown', outsideAccount)
})
onBeforeUnmount(() => {
  media.removeEventListener('change', resize)
  document.removeEventListener('pointerdown', outsideAccount)
})
</script>
<template>
  <div class="layout" :class="{ 'nav-closed': !navOpen, 'nav-open': navOpen }">
    <aside
      v-show="navOpen"
      id="workspace-navigation"
      ref="sidebar"
      class="sidebar"
      @keydown.esc="closeNavigation"
    >
      <div class="brand">MDOP<small>制造运营平台</small></div>
      <nav aria-label="主导航">
        <section
          v-for="(group, index) in groups"
          :key="group.name"
          class="nav-group"
        >
          <RouterLink
            v-if="group.items.length === 1"
            class="nav-direct"
            :to="group.items[0]!.path"
            @click="followLink"
          >
            {{ group.items[0]!.name }}
          </RouterLink>
          <button
            v-else
            class="nav-group-toggle"
            :aria-expanded="!!expanded[group.name]"
            :aria-controls="'navigation-group-' + index"
            @click="toggleGroup(group.name)"
          >
            {{ group.name }}<span class="nav-chevron" aria-hidden="true"></span>
          </button>
          <div
            v-if="group.items.length > 1"
            v-show="expanded[group.name]"
            :id="'navigation-group-' + index"
            class="nav-links"
          >
            <RouterLink
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              @click="followLink"
              >{{ item.name }}</RouterLink
            >
          </div>
        </section>
        <p v-if="!groups.length" class="hint">
          暂无已授权业务菜单，可从右上角查看我的账号。
        </p>
      </nav>
    </aside>
    <header class="topbar">
      <div class="workspace-heading">
        <button
          ref="toggle"
          :aria-expanded="navOpen"
          aria-controls="workspace-navigation"
          @click="toggleNavigation"
        >
          <span class="sr-only">{{ navOpen ? '收起导航' : '展开导航' }}</span>
          <span aria-hidden="true">导航</span>
        </button>
        <nav class="breadcrumb" aria-label="当前位置">
          <span>{{
            activeGroup?.name === pageName
              ? '工作台'
              : activeGroup?.name || '个人中心'
          }}</span>
        </nav>
      </div>
      <details
        ref="account"
        class="account-menu"
        @keydown.esc.prevent.stop="dismissAccount"
        @focusout="accountBlur"
      >
        <summary :aria-label="username + '，账号菜单'">{{ username }}</summary>
        <div class="account-menu-panel">
          <RouterLink to="/account" @click="followLink">我的账号</RouterLink>
          <button :disabled="busy" @click="$emit('logout')">退出登录</button>
        </div>
      </details>
    </header>
    <div
      v-show="!(narrow && navOpen)"
      ref="content"
      tabindex="-1"
      class="workspace"
      aria-label="工作区"
    >
      <slot />
    </div>
  </div>
</template>
