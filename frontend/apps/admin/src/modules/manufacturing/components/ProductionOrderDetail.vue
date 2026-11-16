<script setup lang="ts">
import { computed } from 'vue'
import OperationRecord from '@/shared/components/OperationRecord.vue'
import OperationTimeNote from '@/shared/components/OperationTimeNote.vue'
import RecordHeading from '@/shared/components/RecordHeading.vue'
import { statusTone } from '@/shared/presentation/business'
import {
  businessDate,
  identity,
  quantity as displayQuantity,
} from '@/shared/presentation'
import MaterialPlanPanel from './MaterialPlanPanel.vue'
import CompletionPanel from './CompletionPanel.vue'
import ProductionClosurePanel from './ProductionClosurePanel.vue'
import {
  parseBomSnapshot,
  productionLabel,
  type ProductionDemand,
  type ProductionOrderItem,
} from '../productionOrderTypes'

const props = defineProps<{
  detail: ProductionDemand
  blocked: boolean
  write: boolean
  review: boolean
  authorities: string[]
  username: string
}>()

const emit = defineEmits<{
  returnToList: []
  openAction: [mode: string, order?: ProductionOrderItem]
  closureSaved: [orderId: number]
}>()

const active = computed(() =>
  props.detail.orders.some((o) => o.status !== 'CANCELLED'),
)

function canReview(o: ProductionOrderItem) {
  return (
    props.review &&
    ![
      props.detail.created_by,
      o.created_by,
      o.last_edited_by,
      o.submitted_by,
    ].includes(props.username)
  )
}
</script>

<template>
  <section aria-label="生产需求详情" class="detail">
    <button
      class="back-to-list"
      :disabled="blocked"
      @click="emit('returnToList')"
    >
      ← 返回列表
    </button>
    <RecordHeading
      :title="identity(detail.product_name, detail.product_code)"
      :subtitle="
        '需求量：' + displayQuantity(detail.quantity) + ' ' + detail.unit
      "
      :status="productionLabel(detail.status)"
      :tone="statusTone(detail.status)"
    />
    <dl class="purchase-facts">
      <div>
        <dt>
          {{ detail.source_type === 'SALES' ? '销售来源' : '手工依据' }}
        </dt>
        <dd>{{ detail.source_reference }}</dd>
      </div>
      <div>
        <dt>需求日期</dt>
        <dd>{{ businessDate(detail.needed_date) }}</dd>
      </div>
      <div>
        <dt>计划数量</dt>
        <dd>{{ displayQuantity(detail.quantity) }} {{ detail.unit }}</dd>
      </div>
      <div>
        <dt>创建账号</dt>
        <dd>{{ detail.created_by }}</dd>
      </div>
    </dl>
    <p class="purchase-purpose"><span>用途</span>{{ detail.purpose }}</p>
    <div v-if="write && detail.status === 'OPEN' && !active" class="toolbar">
      <button :disabled="blocked" @click="emit('openAction', 'order')">
        转生产订单</button
      ><button :disabled="blocked" @click="emit('openAction', 'cancel-demand')">
        取消需求
      </button>
    </div>
    <article v-for="o in detail.orders" :key="o.id" class="order">
      <div class="heading">
        <h3>生产订单 {{ o.order_no }}</h3>
        <span class="purchase-status" :data-tone="statusTone(o.status)">{{
          productionLabel(o.status)
        }}</span>
      </div>
      <p>
        {{ o.site_code }} · {{ o.site_name }} → {{ o.warehouse_name }} · 计划
        {{ businessDate(o.planned_date) }}
      </p>
      <OperationRecord
        v-if="o.approved_by"
        title="工单批准"
        result="已批准"
        :account="o.approved_by"
        :at="o.approved_at"
      />
      <OperationTimeNote v-if="o.approved_by" />
      <div class="toolbar">
        <template v-if="write && ['DRAFT', 'REJECTED'].includes(o.status)"
          ><button :disabled="blocked" @click="emit('openAction', 'edit', o)">
            编辑工单</button
          ><button :disabled="blocked" @click="emit('openAction', 'submit', o)">
            提交审核
          </button></template
        >
        <template v-if="o.status === 'SUBMITTED' && canReview(o)"
          ><button
            :disabled="blocked"
            @click="emit('openAction', 'approve', o)"
          >
            批准</button
          ><button :disabled="blocked" @click="emit('openAction', 'reject', o)">
            驳回
          </button></template
        >
        <button
          v-if="write && o.status !== 'CANCELLED'"
          :disabled="blocked"
          @click="emit('openAction', 'cancel', o)"
        >
          取消工单
        </button>
      </div>
      <details>
        <summary>
          固定 BOM · {{ parseBomSnapshot(o)?.version_label || '待核对' }}
        </summary>
        <p v-if="parseBomSnapshot(o)">
          基准产量
          {{ displayQuantity(parseBomSnapshot(o)!.base_quantity) }}
          {{ parseBomSnapshot(o)!.product_unit }}；工单生产量
          {{ displayQuantity(detail.quantity) }}
          {{ detail.unit }}
        </p>
        <p v-else class="error">快照无法读取，请核对原记录。</p>
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>组件</th>
                <th>基准用量</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="c in parseBomSnapshot(o)?.components"
                :key="c.material_code"
              >
                <td>{{ c.material_code }} · {{ c.material_name }}</td>
                <td>{{ displayQuantity(c.quantity) }} {{ c.unit }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="muted">工单批准时固定的 BOM 用量依据。</p>
      </details>
      <details
        v-if="['APPROVED', 'CANCELLED', 'COMPLETED'].includes(o.status)"
        class="record-section"
        open
      >
        <summary>完工登记与成品入库</summary>
        <CompletionPanel
          v-if="['APPROVED', 'CANCELLED', 'COMPLETED'].includes(o.status)"
          :key="`completion:${o.id}:${o.version}`"
          :order-id="o.id"
          :authorities="authorities"
          :username="username"
        />
      </details>
      <details class="record-section" open>
        <summary>材料计划与领料执行</summary>
        <MaterialPlanPanel
          v-if="['APPROVED', 'CANCELLED', 'COMPLETED'].includes(o.status)"
          :key="`${o.id}:${o.version}`"
          :order-id="o.id"
          :authorities="authorities"
          :username="username"
        />
      </details>
      <ProductionClosurePanel
        v-if="['APPROVED', 'COMPLETED'].includes(o.status)"
        :key="`closure:${o.id}:${o.version}`"
        :order-id="o.id"
        :username="username"
        :authorities="authorities"
        @closed="emit('closureSaved', o.id)"
      />
    </article>
    <details>
      <summary>
        操作历史（已加载 {{ detail.history.length }} 条，上限 1000 条）
      </summary>
      <OperationRecord
        v-for="h in detail.history"
        :key="h.id"
        :title="productionLabel(h.action)"
        :account="h.created_by"
        :at="h.created_at"
        :note="h.reason"
        note-label="操作依据"
      />
      <OperationTimeNote v-if="detail.history.length" />
    </details>
  </section>
</template>

<style scoped>
.heading,
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.heading {
  justify-content: space-between;
}
.toolbar {
  margin: 16px 0;
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
button {
  white-space: nowrap;
}
.detail,
.order {
  border-top: 1px solid var(--border, #ddd);
  padding-top: 20px;
  margin-top: 24px;
}
.order {
  padding-bottom: 20px;
}
details {
  margin: 16px 0;
}
summary {
  cursor: pointer;
}
</style>
