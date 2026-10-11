<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{
  total: number
  page: number
  size?: number
  pages?: number
  disabled?: boolean
}>()
const emit = defineEmits<{ change: [page: number] }>()
const pageCount = computed(() =>
  Math.max(1, props.pages ?? Math.ceil(props.total / (props.size || 20))),
)
function change(next: number) {
  if (!props.disabled && next >= 0 && next < pageCount.value)
    emit('change', next)
}
</script>
<template>
  <nav class="result-pager" aria-label="查询结果分页">
    <span>共 {{ total }} 条 · 第 {{ page + 1 }} / {{ pageCount }} 页</span>
    <button :disabled="disabled || page <= 0" @click="change(page - 1)">
      上一页
    </button>
    <button
      :disabled="disabled || page + 1 >= pageCount"
      @click="change(page + 1)"
    >
      下一页
    </button>
  </nav>
</template>
<style scoped>
.result-pager {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  padding: 16px 0;
}
.result-pager span {
  margin-right: auto;
  color: #625d73;
  font-size: 14px;
}
</style>
