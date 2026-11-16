<script setup lang="ts">
import { statuses, type Warehouse } from '../api'
import { areaNames } from '@/modules/receiving/api'
import {
  CATALOG_LABELS,
  type CatalogKind,
  type CatalogRow,
} from '../catalogTypes'

defineProps<{
  tab: CatalogKind
  rows: CatalogRow[]
  warehouses: Warehouse[]
  editable: boolean
  blocked: boolean
  ready: boolean
  loading: boolean
  keyword: string
  statusFilter: string
}>()

const emit = defineEmits<{
  edit: [row: CatalogRow]
  history: [row: CatalogRow]
}>()
</script>

<template>
  <div
    class="table-wrap"
    role="region"
    :aria-label="CATALOG_LABELS[tab] + '列表，可横向滚动'"
    tabindex="0"
  >
    <table>
      <thead>
        <tr>
          <th>编码</th>
          <th>名称</th>
          <th v-if="tab === 'materials'">单位 / 管理方式</th>
          <th v-if="tab === 'locations'">所属仓库 / 区域</th>
          <th v-if="!['locations', 'organizations'].includes(tab)">状态</th>
          <th v-if="editable && tab !== 'locations'">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.id">
          <td>{{ row.code }}</td>
          <td>{{ row.name }}</td>
          <td v-if="tab === 'materials'">
            {{ row.unit }}
            <span class="material-tracking">{{
              row.trackingMode === 'BATCH' ? '按批次' : '按数量'
            }}</span>
            <small v-if="row.requireDateCode || row.requireExpiry">
              {{ row.requireDateCode ? 'Date Code 必填' : ''
              }}{{ row.requireDateCode && row.requireExpiry ? ' · ' : ''
              }}{{ row.requireExpiry ? '有效期必填' : '' }}
            </small>
          </td>
          <td v-if="tab === 'locations'">
            {{
              warehouses.find((w) => w.id === row.warehouseId)?.name ||
              row.warehouseId
            }}<small>{{ areaNames[row.areaType || ''] }}</small>
          </td>
          <td v-if="!['locations', 'organizations'].includes(tab)">
            <span
              class="catalog-status"
              :class="{ disabled: row.status === 'DISABLED' }"
              >{{ statuses[row.status || ''] }}</span
            >
          </td>
          <td v-if="editable && tab !== 'locations'" class="catalog-actions">
            <button
              class="text-action"
              :disabled="blocked || !ready"
              @click="emit('edit', row)"
            >
              编辑
            </button>
            <button
              class="text-action"
              :disabled="blocked || !ready"
              @click="emit('history', row)"
            >
              变更历史
            </button>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="6" class="empty">
            {{
              loading
                ? '正在加载…'
                : ready
                  ? keyword || statusFilter
                    ? '暂无匹配资料'
                    : '暂无' + CATALOG_LABELS[tab]
                  : '资料未就绪，请刷新后操作'
            }}
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
table {
  min-width: 560px;
}
th,
td {
  padding: 12px 16px;
}
td {
  overflow-wrap: anywhere;
}
td:first-child {
  white-space: nowrap;
}
th {
  color: #60586e;
  font-size: 13px;
}
td small {
  color: #6c687d;
}
.catalog-status {
  color: #26734a;
  white-space: nowrap;
  font-size: 13px;
}
.catalog-status.disabled {
  color: #78717f;
}
.material-tracking {
  display: inline-block;
  margin-left: 6px;
  color: #6c687d;
  font-size: 12px;
}
.catalog-actions {
  white-space: nowrap;
}
.text-action {
  border-color: transparent;
  background: transparent;
  color: #6941b5;
  padding: 6px 8px;
}
.catalog-actions button {
  font-size: 13px;
  padding: 6px 8px;
  margin: 2px 4px 2px 0;
}
</style>
