<script setup lang="ts">
import { timestamp } from '../presentation'

defineProps<{
  title: string
  result?: string
  account?: string | null
  name?: string | null
  at?: string | null
  location?: string | null
  locationCode?: string | null
  locationLabel?: string
  note?: string | null
  noteLabel?: string
}>()
</script>
<template>
  <article class="operation-record" :aria-label="title">
    <header>
      <h4>{{ title }}</h4>
      <span v-if="result" class="operation-result">{{ result }}</span>
    </header>
    <dl>
      <div>
        <dt>{{ name ? '操作人' : '操作账号' }}</dt>
        <dd>
          <template v-if="name"
            >{{ name }}<span v-if="account">（{{ account }}）</span></template
          >
          <template v-else>{{ account || '未记录' }}</template>
        </dd>
      </div>
      <div>
        <dt>操作时间</dt>
        <dd>{{ at ? timestamp(at, false) : '未记录' }}</dd>
      </div>
      <div v-if="location || locationCode">
        <dt>{{ locationLabel || '库位' }}</dt>
        <dd>
          {{ location || '名称未提供'
          }}<small v-if="locationCode">库位编码：{{ locationCode }}</small>
        </dd>
      </div>
      <div v-if="note">
        <dt>{{ noteLabel || '备注' }}</dt>
        <dd class="operation-note">{{ note }}</dd>
      </div>
    </dl>
    <slot />
  </article>
</template>
<style scoped>
.operation-record {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--border, #e8e5ed);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
h4 {
  margin: 0;
  font-size: 1rem;
}
.operation-result {
  font-size: 0.875rem;
  font-weight: 600;
}
dl {
  margin: 12px 0 0;
}
dl > div {
  display: grid;
  grid-template-columns: 5em minmax(0, 1fr);
  gap: 8px;
  margin-top: 8px;
}
dt,
small {
  color: var(--muted, #686675);
  font-size: 0.875rem;
}
dd {
  margin: 0;
  min-width: 0;
}
small {
  display: block;
  margin-top: 4px;
}
.operation-note {
  white-space: pre-wrap;
}
</style>
