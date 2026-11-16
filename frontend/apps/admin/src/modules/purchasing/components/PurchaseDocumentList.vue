<script setup lang="ts">
import ResultPager from '@/shared/components/ResultPager.vue'
import { businessDate } from '@/shared/presentation'
import type { Warehouse } from '@/modules/masterdata/api'
import { stateText, statusTone, type Kind, type PurchaseDocument } from '../api'

defineProps<{
  kind: Kind
  warehouse: number
  warehouses: Warehouse[]
  page: number
  result?: { items: PurchaseDocument[]; total: number }
  ready: boolean
  loading: boolean
  blocked: boolean
  locked: boolean
  busy: boolean
  hasModal: boolean
  writable: boolean
  error: string
}>()

const emit = defineEmits<{
  'update:warehouse': [value: number]
  switchKind: [value: Kind]
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
  <section class="panel purchase-list" aria-label="采购单据列表">
    <div class="tabs purchase-tabs">
      <button
        :class="{ active: kind === 'REQUEST' }"
        :aria-pressed="kind === 'REQUEST'"
        :disabled="blocked"
        @click="emit('switchKind', 'REQUEST')"
      >
        采购需求
      </button>
      <button
        :class="{ active: kind === 'ORDER' }"
        :aria-pressed="kind === 'ORDER'"
        :disabled="blocked"
        @click="emit('switchKind', 'ORDER')"
      >
        采购订单
      </button>
    </div>
    <div class="filters">
      <label
        >收货仓库<select
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
        v-if="writable && kind === 'REQUEST'"
        class="primary create"
        :disabled="
          blocked ||
          !result ||
          warehouses.find((w) => w.id === warehouse)?.status !== 'ENABLED'
        "
        @click="emit('create')"
      >
        新建需求
      </button>
    </div>
    <p v-if="ready && !warehouse" class="hint empty">
      暂无已授权仓库，请联系管理员分配仓库范围。
    </p>
    <div class="table-wrap" tabindex="0" aria-label="采购单据列表，可横向滚动">
      <table>
        <thead>
          <tr>
            <th>单号 / 用途</th>
            <th v-if="kind === 'ORDER'">供应商</th>
            <th>{{ kind === 'ORDER' ? '约定到货日期' : '需要日期' }}</th>
            <th>状态</th>
            <th>创建账号</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in result?.items" :key="d.id">
            <td>
              <span class="document-number">{{ d.document_no }}</span
              ><small>{{ d.purpose }}</small>
            </td>
            <td v-if="kind === 'ORDER'">{{ d.supplier_name }}</td>
            <td>{{ businessDate(d.needed_date) }}</td>
            <td>
              <span class="purchase-status" :data-tone="statusTone(d.status)">{{
                stateText(d.status)
              }}</span>
            </td>
            <td>{{ d.created_by }}</td>
            <td>
              <button
                class="text-action"
                :data-document="d.id"
                :disabled="blocked"
                @click="emit('openDetail', d.id)"
              >
                查看
              </button>
            </td>
          </tr>
          <tr v-if="!result?.items.length">
            <td colspan="6" class="empty">
              {{
                loading
                  ? '正在加载…'
                  : error
                    ? '列表读取失败，请刷新重试'
                    : result
                      ? '暂无单据'
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
