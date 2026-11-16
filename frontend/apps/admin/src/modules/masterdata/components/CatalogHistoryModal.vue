<script setup lang="ts">
import { timestamp } from '@/shared/presentation'
import {
  describeCatalogState,
  type CatalogAudit,
  type CatalogRow,
} from '../catalogTypes'

defineProps<{
  historyRow: CatalogRow
  historyRows: CatalogAudit[]
  historyError: string
  historyLoading: boolean
}>()

const emit = defineEmits<{
  close: []
}>()
</script>

<template>
  <div class="overlay">
    <section
      class="modal wide"
      role="dialog"
      aria-modal="true"
      aria-labelledby="history-title"
    >
      <h2 id="history-title">{{ historyRow.code }} · 变更记录</h2>
      <p v-if="historyError" class="error" role="alert">{{ historyError }}</p>
      <p v-if="historyLoading">正在加载…</p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>动作 / 时间</th>
              <th>操作人 / 原因</th>
              <th>变更前</th>
              <th>变更后</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in historyRows" :key="a.id">
              <td>
                {{ a.action_type === 'CREATE' ? '创建' : '维护'
                }}<small>{{ timestamp(a.created_at) }}</small>
              </td>
              <td>
                {{ a.created_by }}<small>{{ a.reason }}</small>
              </td>
              <td>{{ describeCatalogState(a.before_state) }}</td>
              <td>{{ describeCatalogState(a.after_state) }}</td>
            </tr>
            <tr v-if="!historyRows.length && !historyLoading && !historyError">
              <td colspan="4">暂无变更记录。升级前的资料不补造创建记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="modal-actions">
        <button @click="emit('close')">关闭</button>
      </div>
    </section>
  </div>
</template>
