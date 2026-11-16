<script setup lang="ts">
import ResultPager from '@/shared/components/ResultPager.vue'
import { statusTone } from '@/shared/presentation/business'
import { businessDate } from '@/shared/presentation'
import type { Warehouse } from '@/modules/masterdata/api'
import { stateText, type SalesDocument } from '../api'

defineProps<{
  warehouse: number
  warehouses: Warehouse[]
  page: number
  result?: { items: SalesDocument[]; total: number }
  ready: boolean
  loading: boolean
  blocked: boolean
  locked: boolean
  busy: boolean
  hasModal: boolean
  writable: boolean
}>()

const emit = defineEmits<{
  'update:warehouse': [value: number]
  load: [page?: number]
  create: []
  openDetail: [id: number]
}>()

function onWarehouseChange(event: Event) {
  emit('update:warehouse', Number((event.target as HTMLSelectElement).value))
  emit('load')
}
</script>

<template>
  <section class="panel business-list">
    <div class="filters">
      <label
        >发货仓库<select
          :value="warehouse"
          :disabled="blocked || loading"
          @change="onWarehouseChange"
        >
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.name }}{{ w.status === 'DISABLED' ? '（停用）' : '' }}
          </option>
        </select></label
      >
      <button
        :disabled="locked || busy || loading || hasModal"
        @click="emit('load', page)"
      >
        刷新
      </button>
      <button
        v-if="writable"
        class="primary create"
        :disabled="
          blocked ||
          !result ||
          warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
        "
        @click="emit('create')"
      >
        新建销售订单
      </button>
    </div>
    <p v-if="ready && !warehouse" class="hint empty">
      暂无已授权成品仓，请联系管理员分配仓库范围。
    </p>
    <div class="table-wrap" tabindex="0" aria-label="销售订单列表，可横向滚动">
      <table>
        <thead>
          <tr>
            <th>订单 / 用途</th>
            <th>客户</th>
            <th>约定日期</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in result?.items" :key="d.id">
            <td>
              <strong class="document-number">{{ d.document_no }}</strong
              ><small>{{ d.purpose }}</small>
            </td>
            <td>{{ d.customer_name }}</td>
            <td>{{ businessDate(d.needed_date) }}</td>
            <td>
              <span class="purchase-status" :data-tone="statusTone(d.status)">{{
                stateText(d.status)
              }}</span>
            </td>
            <td>
              <button
                class="text-action"
                :disabled="blocked"
                @click="emit('openDetail', d.id)"
              >
                查看
              </button>
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="5" class="empty">
              {{
                loading
                  ? '正在加载…'
                  : result
                    ? '暂无订单'
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
  </section>
</template>

<style scoped>
.create {
  margin-left: auto;
}
.filters label {
  min-width: 220px;
}
@media (max-width: 640px) {
  .filters label {
    min-width: 0;
    flex: 1 1 100%;
  }
}
</style>
