<script setup lang="ts">
import RecordHeading from '@/shared/components/RecordHeading.vue'
import { quantity as displayQuantity, timestamp } from '@/shared/presentation'
import { bomLabel, describeBomSnapshot, type BomRecord } from '../bomTypes'

defineProps<{
  detail: BomRecord
  blocked: boolean
  writable: boolean
}>()

const emit = defineEmits<{
  returnToList: []
  openDetail: [id: number]
  openAction: [mode: string]
}>()
</script>

<template>
  <section class="detail" aria-label="BOM 详情">
    <button
      class="back-to-list"
      :disabled="blocked"
      @click="emit('returnToList')"
    >
      ← 返回列表
    </button>
    <RecordHeading
      :title="detail.product_name + ' · ' + detail.version_label"
      :subtitle="detail.product_code"
      :status="bomLabel(detail.status)"
    />
    <dl class="record-facts">
      <div>
        <dt>基准产量</dt>
        <dd>
          {{ displayQuantity(detail.base_quantity) }}
          {{ detail.product_unit }}
        </dd>
      </div>
      <div>
        <dt>版本说明</dt>
        <dd>{{ detail.description || '—' }}</dd>
      </div>
      <div v-if="detail.published_at">
        <dt>发布账号与时间</dt>
        <dd>
          {{ detail.published_by
          }}<small>{{ timestamp(detail.published_at) }}</small>
        </dd>
      </div>
      <div v-if="detail.disabled_at">
        <dt>停用账号与时间</dt>
        <dd>
          {{ detail.disabled_by
          }}<small>{{ timestamp(detail.disabled_at) }}</small>
        </dd>
      </div>
    </dl>
    <div v-if="writable" class="toolbar">
      <button
        v-if="detail.status === 'DRAFT'"
        :disabled="blocked"
        @click="emit('openAction', 'edit')"
      >
        编辑草稿
      </button>
      <button
        v-if="detail.status === 'DRAFT'"
        class="primary"
        :disabled="blocked"
        @click="emit('openAction', 'publish')"
      >
        发布版本
      </button>
      <button :disabled="blocked" @click="emit('openAction', 'copy')">
        复制新版本
      </button>
      <button
        v-if="detail.status === 'PUBLISHED'"
        :disabled="blocked"
        @click="emit('openAction', 'disable')"
      >
        停用版本
      </button>
    </div>
    <p v-if="detail.copied_from_id">
      复制自
      <button
        :disabled="blocked"
        @click="emit('openDetail', detail.copied_from_id)"
      >
        查看来源 BOM 版本
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
            <td>{{ displayQuantity(c.quantity) }}</td>
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
          @click="emit('openDetail', v.id)"
        >
          {{ v.version_label }} · {{ bomLabel(v.status) }}
        </button>
      </div>
    </details>
    <details>
      <summary>操作历史（{{ detail.history.length }}，最多 1000 条）</summary>
      <article v-for="h in detail.history" :key="h.id">
        <strong>{{ bomLabel(h.action) }} · {{ h.created_by }}</strong>
        <p>{{ timestamp(h.created_at) }} · {{ h.reason }}</p>
        <details>
          <summary>变更前后</summary>
          <pre>{{ describeBomSnapshot(h.before_state) }}</pre>
          <pre>{{ describeBomSnapshot(h.after_state) }}</pre>
        </details>
      </article>
    </details>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin: 16px 0;
}
.muted,
small {
  color: #64748b;
}
small {
  display: block;
  margin-top: 4px;
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
@media (max-width: 600px) {
  th,
  td {
    padding: 8px;
  }
}
</style>
