<script setup lang="ts">
import { computed } from 'vue'
import RecordTabs from '@/shared/components/RecordTabs.vue'
import RecordHeading from '@/shared/components/RecordHeading.vue'
import OperationRecord from '@/shared/components/OperationRecord.vue'
import OperationTimeNote from '@/shared/components/OperationTimeNote.vue'
import { statusTone } from '@/shared/presentation/business'
import {
  businessDate,
  identity,
  quantity as displayQuantity,
} from '@/shared/presentation'
import {
  describeSnapshot,
  SALES_AUDIT_NAMES,
  stateText,
  type SalesDocument,
  type SalesFulfillment,
} from '../api'

const props = defineProps<{
  detail: SalesDocument
  detailTab: string
  blocked: boolean
  writable: boolean
  canEdit: boolean
  canReview: boolean
  canShip: boolean
  authorities: string[]
}>()

const emit = defineEmits<{
  'update:detailTab': [value: string]
  returnToList: []
  openDetail: [id: number]
  openAction: [mode: string, target?: number]
}>()

const closureLines = computed(() => {
  if (!props.detail.closure) return []
  try {
    return (JSON.parse(props.detail.closure.snapshot) as SalesFulfillment).lines
  } catch {
    return []
  }
})
</script>

<template>
  <section class="panel detail">
    <button
      class="back-to-list"
      :disabled="blocked"
      @click="emit('returnToList')"
    >
      ← 返回列表
    </button>
    <RecordHeading
      :title="'销售订单 ' + detail.document_no"
      :subtitle="identity(detail.customer_name) + ' · ' + detail.warehouse_name"
      :status="stateText(detail.status)"
      :tone="statusTone(detail.status)"
    />
    <dl class="purchase-facts">
      <div>
        <dt>客户</dt>
        <dd>{{ identity(detail.customer_name) }}</dd>
      </div>
      <div>
        <dt>发货仓库</dt>
        <dd>{{ detail.warehouse_name }}</dd>
      </div>
      <div>
        <dt>约定日期</dt>
        <dd>{{ businessDate(detail.needed_date) }}</dd>
      </div>
      <div>
        <dt>创建账号</dt>
        <dd>{{ detail.created_by }}</dd>
      </div>
    </dl>
    <p class="purchase-purpose"><span>用途</span>{{ detail.purpose }}</p>
    <p v-if="detail.customer_reference" class="hint">
      客户单号：{{ detail.customer_reference }}
    </p>
    <div class="detail-actions">
      <button
        v-if="canEdit"
        :disabled="blocked"
        @click="emit('openAction', 'edit')"
      >
        编辑</button
      ><button
        v-if="canEdit"
        class="primary"
        :disabled="blocked"
        @click="emit('openAction', 'submit')"
      >
        提交审核
      </button>
      <button
        v-if="canReview"
        class="primary"
        :disabled="blocked"
        @click="emit('openAction', 'approve')"
      >
        批准</button
      ><button
        v-if="canReview"
        :disabled="blocked"
        @click="emit('openAction', 'reject')"
      >
        驳回
      </button>
      <button
        v-if="writable && !['CLOSED', 'CANCELLED'].includes(detail.status)"
        :disabled="
          blocked || detail.arrangements.some((a) => a.status !== 'WITHDRAWN')
        "
        @click="emit('openAction', 'cancel')"
      >
        取消订单
      </button>
    </div>
    <p v-if="detail.status === 'SUBMITTED' && !canReview" class="hint">
      等待其他销售审批员审核。
    </p>
    <p v-if="authorities.includes('finance:read')">
      <a
        :href="`/finance?warehouseId=${detail.warehouse_id}&direction=RECEIVABLE&orderNumber=${encodeURIComponent(detail.document_no)}`"
        >查看金额依据与应收</a
      >
    </p>
    <RecordTabs
      :model-value="detailTab"
      prefix="sales"
      :disabled="blocked"
      :tabs="[
        { id: 'overview', label: '物料与履约' },
        { id: 'arrangements', label: '发货安排' },
        { id: 'history', label: '操作记录' },
      ]"
      @update:model-value="emit('update:detailTab', $event)"
    />
    <div
      v-show="detailTab === 'overview'"
      id="sales-panel-overview"
      role="tabpanel"
      aria-labelledby="sales-tab-overview"
    >
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料</th>
              <th>订单数量</th>
              <th>已安排</th>
              <th>实际已发</th>
              <th>未发数量</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.fulfillment.lines" :key="l.orderLineId">
              <td>
                {{
                  detail.lines.find((x) => x.id === l.orderLineId)
                    ?.material_name
                }}<small>{{ l.code }}</small>
              </td>
              <td>{{ displayQuantity(l.ordered) }} {{ l.unit }}</td>
              <td>{{ displayQuantity(l.allocated) }}</td>
              <td>{{ displayQuantity(l.shipped) }}</td>
              <td>{{ displayQuantity(l.remaining) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <section class="arrangements sales-fulfillment" aria-label="履约核对">
        <div class="detail-heading">
          <h2>履约核对</h2>
          <button :disabled="blocked" @click="emit('openDetail', detail.id)">
            核对最新进度
          </button>
        </div>
        <template v-if="detail.closure"
          ><OperationRecord
            title="数量履约结案"
            result="已保存数量结案快照"
            :account="detail.closure.closed_by"
            :at="detail.closure.closed_at"
            :note="detail.closure.reason"
            note-label="结案原因"
          />
          <OperationTimeNote />
          <p v-if="!detail.closureMatches" class="error" role="alert">
            当前事实与结案快照不一致，请联系管理员核对。
          </p>
          <details>
            <summary>结案时数量</summary>
            <div class="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>物料</th>
                    <th>订单数量</th>
                    <th>实际已发</th>
                    <th>未发数量</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="l in closureLines" :key="l.orderLineId">
                    <td>{{ l.code }}</td>
                    <td>{{ displayQuantity(l.ordered) }} {{ l.unit }}</td>
                    <td>{{ displayQuantity(l.shipped) }}</td>
                    <td>{{ displayQuantity(l.remaining) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details></template
        >
        <template v-else
          ><ul v-if="detail.fulfillment.blockers.length">
            <li v-for="b in detail.fulfillment.blockers" :key="b">{{ b }}</li>
          </ul>
          <p v-else class="success">授权数量与实际出库、流水一致，可以结案。</p>
          <button
            v-if="writable && detail.status === 'FULFILLING'"
            class="primary"
            :disabled="blocked || !detail.fulfillment.canClose"
            @click="emit('openAction', 'close')"
          >
            履约结案
          </button></template
        >
        <p class="hint">
          结案仅确认数量履约。应收金额请在财务往来核对，不代表收款结清。
        </p>
      </section>
    </div>
    <section
      v-show="detailTab === 'arrangements'"
      id="sales-panel-arrangements"
      role="tabpanel"
      aria-labelledby="sales-tab-arrangements"
      class="arrangements"
    >
      <div class="detail-heading">
        <h2>发货安排</h2>
        <button
          v-if="writable && ['APPROVED', 'FULFILLING'].includes(detail.status)"
          class="primary"
          :disabled="blocked"
          @click="emit('openAction', 'arrange')"
        >
          安排发货
        </button>
      </div>
      <p class="hint">
        每次安排一个物料，仓库选择一个批次整笔执行。待送达、失败和未确认撤回仍占额度。
      </p>
      <p v-if="!detail.arrangements.length" class="hint">暂无发货安排</p>
      <article
        v-for="a in detail.arrangements"
        :key="a.id"
        class="arrangement-record"
      >
        <strong
          >发货安排 {{ a.id }} · 送达状态：{{ stateText(a.status)
          }}{{ a.wms ? ' · 仓库执行：' + stateText(a.wms.status) : '' }}</strong
        >
        <p>
          {{
            detail.lines.find((l) => l.id === a.order_line_id)?.material_code
          }}
          · {{ displayQuantity(a.quantity) }}
          {{ detail.lines.find((l) => l.id === a.order_line_id)?.unit }} ·
          预计发货 {{ businessDate(a.expected_date) }}
        </p>
        <p v-if="a.last_error" class="error">
          送达失败：{{ a.last_error }}。核对后可重试，额度仍保留。
        </p>
        <p v-if="a.wms" class="hint">
          销售出库 SO-{{ a.wms.id }} · 批次 {{ a.wms.batchNo || '待选择' }} ·
          实际已发 {{ displayQuantity(a.wms.shippedQuantity) }}
        </p>
        <div class="detail-actions">
          <button
            v-if="writable && a.status === 'PENDING'"
            :disabled="blocked"
            @click="emit('openAction', 'deliver', a.id)"
          >
            {{ a.last_error ? '重试送达' : '送达 WMS' }}</button
          ><button
            v-if="
              writable &&
              a.status !== 'WITHDRAWN' &&
              a.wms?.status !== 'SHIPPED'
            "
            :disabled="blocked"
            @click="emit('openAction', 'withdraw', a.id)"
          >
            撤回安排</button
          ><a
            v-if="canShip && a.wms_sales_id && !blocked"
            :href="`/sales?warehouseId=${detail.warehouse_id}&salesOrderId=${a.wms_sales_id}`"
            >查看 WMS 出库</a
          >
        </div>
      </article>
    </section>
    <section
      v-show="detailTab === 'history'"
      id="sales-panel-history"
      role="tabpanel"
      aria-labelledby="sales-tab-history"
      class="history"
    >
      <h2>操作历史（{{ detail.history.length }}）</h2>
      <div v-for="h in detail.history" :key="h.id" class="history-item">
        <OperationRecord
          :title="SALES_AUDIT_NAMES[h.action] || h.action"
          :account="h.created_by"
          :at="h.created_at"
          :note="h.reason"
          note-label="操作依据"
        />
        <details>
          <summary>变更快照</summary>
          <p class="hint">变更前</p>
          <pre>{{ describeSnapshot(h.before_state) }}</pre>
          <p class="hint">变更后</p>
          <pre>{{ describeSnapshot(h.after_state) }}</pre>
        </details>
      </div>
      <OperationTimeNote v-if="detail.history.length" />
    </section>
  </section>
</template>

<style scoped>
.arrangements {
  margin-top: 24px;
}
.arrangement-record {
  padding: 16px 0;
  border-top: 1px solid #ece9f1;
}
.detail-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
  padding: 8px 0 16px;
}
.detail {
  margin-top: 20px;
  padding: 20px;
}
.detail-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.detail h2 {
  font-size: 19px;
  margin-bottom: 6px;
}
.history {
  margin-top: 16px;
}
.history summary {
  cursor: pointer;
  color: #6941b5;
}
.history-item {
  padding: 12px 0;
  border-bottom: 1px solid #eee;
}
.history-item p {
  margin: 4px 0;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 12px;
}
@media (max-width: 640px) {
  .detail {
    padding: 14px;
  }
}
</style>
