<script setup lang="ts">
import ResultPager from '@/shared/components/ResultPager.vue'
import {
  businessDate,
  quantity as displayQuantity,
} from '@/shared/presentation'
import type { Warehouse } from '@/modules/masterdata/api'
import { productionLabel, type ProductionDemand } from '../productionOrderTypes'

defineProps<{
  warehouse: number
  warehouses: Warehouse[]
  page: number
  result?: { items: ProductionDemand[]; total: number }
  loading: boolean
  blocked: boolean
  write: boolean
}>()

const emit = defineEmits<{
  'update:warehouse': [value: number]
  load: [page?: number]
  create: []
  openDetail: [id: number]
}>()

function onWarehouseChange(event: Event) {
  emit('update:warehouse', Number((event.target as HTMLSelectElement).value))
  emit('load', 0)
}
</script>

<template>
  <div class="business-list panel">
    <form class="toolbar" @submit.prevent="emit('load', 0)">
      <label
        >目标成品仓<select
          :value="warehouse"
          :disabled="blocked || loading"
          @change="onWarehouseChange"
        >
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.code }} · {{ w.name
            }}{{ w.status === 'ENABLED' ? '' : '（停用）' }}
          </option>
        </select></label
      >
      <button :disabled="blocked || loading">刷新</button
      ><button
        v-if="write"
        type="button"
        class="primary"
        :disabled="
          blocked ||
          loading ||
          !result ||
          warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
        "
        @click="emit('create')"
      >
        新建需求
      </button>
    </form>
    <p v-if="!loading && !warehouse" class="muted">
      尚未分配成品仓权限，请联系系统管理员。
    </p>
    <div
      class="table-scroll"
      role="region"
      aria-label="生产需求列表，可横向滚动"
      tabindex="0"
    >
      <table>
        <thead>
          <tr>
            <th>来源 / 产品</th>
            <th>计划数量</th>
            <th>需求日期</th>
            <th>当前工单</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in result?.items" :key="d.id">
            <td>
              {{ d.source_reference
              }}<small>{{ d.product_code }} · {{ d.product_name }}</small>
            </td>
            <td>{{ displayQuantity(d.quantity) }} {{ d.unit }}</td>
            <td>{{ businessDate(d.needed_date) }}</td>
            <td>
              {{ d.order_no || '无有效工单'
              }}<small>{{ productionLabel(d.order_status || d.status) }}</small>
            </td>
            <td>
              <button
                :disabled="blocked || loading"
                @click="emit('openDetail', d.id)"
              >
                查看
              </button>
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="5">
              {{
                loading
                  ? '正在加载…'
                  : result
                    ? '暂无生产需求'
                    : '资料未就绪，请刷新'
              }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <ResultPager
      v-if="result"
      :total="result.total"
      :page="page"
      :disabled="blocked || loading"
      @change="emit('load', $event)"
    />
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 16px 0;
}
label {
  display: grid;
  gap: 6px;
  min-width: 0;
}
.table-scroll {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  min-width: 600px;
}
td,
th {
  text-align: left;
  padding: 12px;
  border-bottom: 1px solid var(--border, #ddd);
}
small {
  display: block;
  margin-top: 4px;
  color: var(--muted, #777);
}
button {
  white-space: nowrap;
}
@media (max-width: 600px) {
  .toolbar label {
    min-width: 0;
    flex: 1 1 100%;
  }
}
</style>
