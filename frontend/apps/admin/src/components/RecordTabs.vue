<script setup lang="ts">
import { nextTick, ref } from 'vue'
const props = defineProps<{
  modelValue: string
  tabs: { id: string; label: string }[]
  prefix: string
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const root = ref<HTMLElement>()
function select(value: string) {
  if (!props.disabled) emit('update:modelValue', value)
}
async function move(event: KeyboardEvent, index: number) {
  if (props.disabled) return
  const target =
    event.key === 'ArrowRight'
      ? (index + 1) % props.tabs.length
      : event.key === 'ArrowLeft'
        ? (index + props.tabs.length - 1) % props.tabs.length
        : event.key === 'Home'
          ? 0
          : event.key === 'End'
            ? props.tabs.length - 1
            : -1
  if (target < 0) return
  event.preventDefault()
  select(props.tabs[target]!.id)
  await nextTick()
  root.value?.querySelectorAll<HTMLButtonElement>('button')[target]?.focus()
}
</script>
<template>
  <nav
    ref="root"
    class="purchase-detail-tabs"
    role="tablist"
    aria-label="详情分区"
  >
    <button
      v-for="(tab, index) in tabs"
      :id="`${prefix}-tab-${tab.id}`"
      :key="tab.id"
      role="tab"
      :aria-controls="`${prefix}-panel-${tab.id}`"
      :aria-selected="modelValue === tab.id"
      :tabindex="modelValue === tab.id ? 0 : -1"
      :disabled="disabled"
      @click="select(tab.id)"
      @keydown="move($event, index)"
    >
      {{ tab.label }}
    </button>
  </nav>
</template>
