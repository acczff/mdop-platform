<script setup lang="ts">
import { computed } from 'vue'
import RecordTabs from '@/shared/components/RecordTabs.vue'
import RecordHeading from '@/shared/components/RecordHeading.vue'
import OperationRecord from '@/shared/components/OperationRecord.vue'
import OperationTimeNote from '@/shared/components/OperationTimeNote.vue'
import {
  businessDate,
  identity,
  quantity as displayQuantity,
} from '@/shared/presentation'
import { remaining } from '@/shared/quantity'
import PurchaseFulfillmentPanel from './PurchaseFulfillmentPanel.vue'
import {
  allocatedQuantity,
  describeSnapshot,
  PURCHASE_AUDIT_NAMES,
  stateText,
  statusTone,
  type PurchaseDocument,
} from '../api'

const props = defineProps<{
  detail: PurchaseDocument
  detailTab: string
  blocked: boolean
  writable: boolean
  canEdit: boolean
  canReview: boolean
  canReplenish: boolean
  canReceive: boolean
  authorities: string[]
}>()

const emit = defineEmits<{
  'update:detailTab': [value: string]
  returnToList: []
  openDetail: [id: number]
  openAction: [mode: string, target?: number]
}>()

const detailTabs = computed(() => [
  { id: 'overview' as const, label: '物料与履约' },
  ...(props.detail.kind === 'ORDER'
    ? [{ id: 'arrangements' as const, label: '到货安排' }]
    : []),
  { id: 'history' as const, label: '操作记录' },
])

function allocated(lineId: number) {
  return allocatedQuantity(props.detail.arrangements, lineId)
}
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
    <div class="purchase-detail-title" :data-tone="statusTone(detail.status)">
      <RecordHeading
        :title="
          (detail.kind === 'REQUEST' ? '采购需求 ' : '采购订单 ') +
          detail.document_no
        "
        :subtitle="
          (detail.supplier_name || '供应商待确定') +
          ' · ' +
          detail.warehouse_name
        "
        :status="stateText(detail.status)"
      ></RecordHeading>
    </div>
    <dl class="purchase-facts">
      <div v-if="detail.kind === 'ORDER'">
        <dt>供应商</dt>
        <dd>{{ detail.supplier_name || '供应商待确定' }}</dd>
      </div>
      <div>
        <dt>收货仓库</dt>
        <dd>{{ detail.warehouse_name }}</dd>
      </div>
      <div>
        <dt>{{ detail.kind === 'ORDER' ? '约定到货日期' : '需要日期' }}</dt>
        <dd>{{ businessDate(detail.needed_date) }}</dd>
      </div>
      <div>
        <dt>创建账号</dt>
        <dd>{{ detail.created_by }}</dd>
      </div>
    </dl>
    <p class="purchase-purpose"><span>用途</span>{{ detail.purpose }}</p>
    <p v-if="detail.production_order_no" class="hint">
      生产来源：{{
        detail.production_order_no
      }}。数量来自已确认快照；仍须完成采购审核。
    </p>
    <p v-if="detail.original_order_id" class="hint">
      补货原订单：
      <button
        :disabled="blocked"
        @click="emit('openDetail', detail.original_order_id)"
      >
        {{
          detail.related.find((d) => d.id === detail.original_order_id)
            ?.document_no || `#${detail.original_order_id}`
        }}
      </button>
    </p>
    <div class="detail-actions">
      <button
        v-if="
          detail.kind === 'ORDER' &&
          writable &&
          ['APPROVED', 'FULFILLING'].includes(detail.status)
        "
        class="primary"
        :disabled="blocked"
        @click="emit('openAction', 'arrange')"
      >
        安排到货
      </button>
      <button
        v-if="canReplenish"
        :disabled="blocked"
        @click="emit('openAction', 'create', detail.id)"
      >
        新建关联补货需求
      </button>
      <button
        v-if="canEdit && detail.source_type !== 'MANUFACTURING'"
        :disabled="blocked"
        @click="emit('openAction', 'edit')"
      >
        编辑
      </button>
      <button
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
        批准
      </button>
      <button
        v-if="canReview"
        :disabled="blocked"
        @click="emit('openAction', 'reject')"
      >
        驳回
      </button>
      <button
        v-if="
          writable && detail.kind === 'REQUEST' && detail.status === 'APPROVED'
        "
        class="primary"
        :disabled="blocked"
        @click="emit('openAction', 'convert')"
      >
        转采购订单
      </button>
      <button
        v-if="
          writable &&
          ['DRAFT', 'REJECTED', 'SUBMITTED', 'APPROVED', 'FULFILLING'].includes(
            detail.status,
          )
        "
        :disabled="
          blocked ||
          (detail.kind === 'ORDER' &&
            (detail.arrangements || []).some((a) => a.status !== 'WITHDRAWN'))
        "
        @click="emit('openAction', 'cancel')"
      >
        {{ detail.kind === 'REQUEST' ? '撤销需求' : '取消订单' }}
      </button>
    </div>
    <p v-if="detail.status === 'SUBMITTED' && !canReview" class="hint">
      等待其他采购审批员审核。
    </p>
    <p v-if="authorities.includes('finance:read') && detail.kind === 'ORDER'">
      <a
        :href="`/finance?warehouseId=${detail.warehouse_id}&direction=PAYABLE&orderNumber=${encodeURIComponent(detail.document_no)}`"
        >查看金额依据与应付</a
      >
    </p>
    <RecordTabs
      :model-value="detailTab"
      prefix="purchase"
      :tabs="detailTabs"
      :disabled="blocked"
      @update:model-value="emit('update:detailTab', $event)"
    />
    <div
      v-show="detailTab === 'overview'"
      id="purchase-panel-overview"
      role="tabpanel"
      aria-labelledby="purchase-tab-overview"
    >
      <p v-if="detail.kind === 'ORDER'" class="hint">
        金额由财务独立确认；数量履约不代表已付款。物料与数量来自已批准需求。
      </p>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>物料 / 编码</th>
              <th>数量</th>
              <th>单位</th>
              <th v-if="detail.kind === 'ORDER'">来源需求</th>
              <th v-if="detail.kind === 'ORDER'">已安排 / 剩余</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in detail.lines" :key="l.id">
              <td>
                {{ l.material_name
                }}<small class="material-code">{{ l.material_code }}</small>
              </td>
              <td class="quantity-cell">{{ displayQuantity(l.quantity) }}</td>
              <td>{{ l.unit }}</td>
              <td v-if="detail.kind === 'ORDER'">
                <button
                  :disabled="blocked || !detail.request_id"
                  @click="
                    detail.request_id && emit('openDetail', detail.request_id)
                  "
                >
                  {{
                    detail.related.find((d) => d.id === detail.request_id)
                      ?.document_no || '查看来源需求'
                  }}
                </button>
              </td>
              <td v-if="detail.kind === 'ORDER'" class="quantity-cell">
                {{ displayQuantity(allocated(l.id)) }} /
                {{ remaining(l.quantity, allocated(l.id)) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <PurchaseFulfillmentPanel
        v-if="detail.fulfillment"
        :value="detail.fulfillment"
        :warehouse-id="detail.warehouse_id"
        :locations="detail.displayLocations || []"
        :materials="detail.lines"
        :arrangements="detail.arrangements || []"
        :closure="detail.closure"
        :matches="detail.closureMatches !== false"
        :can-write="writable && detail.status === 'FULFILLING'"
        :blocked="blocked"
        :authorities="authorities"
        @refresh="emit('openDetail', detail.id)"
        @close="emit('openAction', 'close')"
      />
    </div>
    <section
      v-if="detail.kind === 'ORDER'"
      v-show="detailTab === 'arrangements'"
      id="purchase-panel-arrangements"
      role="tabpanel"
      aria-labelledby="purchase-tab-arrangements"
      class="arrangements"
    >
      <div class="detail-heading">
        <h2>到货安排</h2>
      </div>
      <p class="hint">
        待送达和送达失败仍占用数量；全部确认撤回后才可取消订单。
      </p>
      <p v-if="!detail.arrangements?.length" class="hint">暂无到货安排</p>
      <article
        v-for="a in detail.arrangements"
        :key="a.id"
        class="arrangement-record"
      >
        <strong
          >PA-{{ String(a.id).padStart(8, '0') }} ·
          {{ stateText(a.status) }}</strong
        >
        <p class="hint">
          预计到货 {{ businessDate(a.expected_date)
          }}<span v-if="a.wms_arrival_id">
            · WMS 通知 #{{ a.wms_arrival_id }}</span
          >
        </p>
        <p v-if="a.last_error" class="error">
          送达失败：{{ a.last_error }}。核对后可重试，额度仍保留。
        </p>
        <p v-for="l in a.lines" :key="l.id">
          {{
            identity(
              detail.lines.find((m) => m.material_code === l.material_code)
                ?.material_name,
              l.material_code,
            )
          }}
          · 安排 {{ displayQuantity(l.quantity) }}
          {{
            detail.lines.find((m) => m.material_code === l.material_code)?.unit
          }}<span v-if="a.wms">
            · 已收
            {{
              displayQuantity(
                a.wms.lines.find((x) => x.arrangementLineId === l.id)
                  ?.receivedQty,
              )
            }}
            {{
              detail.lines.find((m) => m.material_code === l.material_code)
                ?.unit
            }}</span
          >
        </p>
        <div class="detail-actions">
          <button
            v-if="writable && a.status === 'PENDING'"
            :disabled="blocked"
            @click="emit('openAction', 'deliver', a.id)"
          >
            {{ a.last_error ? '重试送达' : '送达 WMS' }}
          </button>
          <button
            v-if="writable && a.status !== 'WITHDRAWN'"
            :disabled="blocked || !!a.wms?.receipts.length"
            @click="emit('openAction', 'withdraw', a.id)"
          >
            撤回安排
          </button>
          <a
            v-if="canReceive && a.wms_arrival_id && !blocked"
            :href="`/receiving?warehouseId=${detail.warehouse_id}&arrivalId=${a.wms_arrival_id}`"
            >查看 WMS 收货</a
          >
        </div>
        <p v-if="a.wms?.receipts.length" class="hint">
          已有收货记录，不能撤回。<span v-for="r in a.wms.receipts" :key="r.id">
            {{ r.number }}（{{
              r.status === 'DRAFT' ? '草稿' : '已提交'
            }}）</span
          >
        </p>
      </article>
    </section>
    <div
      v-show="detailTab === 'overview' && detail.related.length"
      class="related"
    >
      <strong>关联单据</strong
      ><button
        v-for="d in detail.related"
        :key="d.id"
        :disabled="blocked"
        @click="emit('openDetail', d.id)"
      >
        {{ d.document_no }} · {{ stateText(d.status) }}
      </button>
    </div>
    <section
      v-show="detailTab === 'history'"
      id="purchase-panel-history"
      role="tabpanel"
      aria-labelledby="purchase-tab-history"
      class="history"
    >
      <h2>操作历史（{{ detail.history.length }}）</h2>
      <p v-if="!detail.history.length" class="hint">暂无操作记录</p>
      <div v-for="h in detail.history" :key="h.id" class="history-item">
        <OperationRecord
          :title="PURCHASE_AUDIT_NAMES[h.action] || h.action"
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
