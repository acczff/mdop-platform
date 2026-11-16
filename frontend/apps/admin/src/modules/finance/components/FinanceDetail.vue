<script setup lang="ts">
import RecordHeading from '@/shared/components/RecordHeading.vue'
import OperationRecord from '@/shared/components/OperationRecord.vue'
import OperationTimeNote from '@/shared/components/OperationTimeNote.vue'
import { quantity } from '@/shared/presentation'
import { FINANCE_ACTION_NAMES, FINANCE_LABELS, type Basis } from '../api'

defineProps<{
  detail: Basis
  actionReason: string
  blocked: boolean
  write: boolean
  review: boolean
  username: string
}>()

const emit = defineEmits<{
  'update:actionReason': [value: string]
  back: []
  action: [name: string]
}>()
</script>

<template>
  <button :disabled="blocked" class="back-to-list" @click="emit('back')">
    ← 返回列表
  </button>
  <RecordHeading
    :title="detail.number"
    :subtitle="
      detail.party_name +
      ' · ' +
      (detail.direction === 'PAYABLE' ? '应付' : '应收')
    "
    :status="FINANCE_LABELS[detail.status] || detail.status"
  />
  <section class="card">
    <h2>金额与来源</h2>
    <dl class="facts-grid">
      <div>
        <dt>确认金额</dt>
        <dd>{{ detail.amount }} CNY</dd>
      </div>
      <div>
        <dt>未核销金额</dt>
        <dd>
          {{
            detail.open_amount === null
              ? '尚未确认往来'
              : detail.open_amount + ' CNY'
          }}
        </dd>
      </div>
      <div>
        <dt>业务订单</dt>
        <dd>
          <a
            :href="`${detail.direction === 'PAYABLE' ? '/purchasing' : '/sales-orders'}?warehouseId=${detail.warehouse_id}&documentId=${detail.order_id}`"
            >{{ detail.order_number }}</a
          >
        </dd>
      </div>
      <div>
        <dt>收发依据</dt>
        <dd>{{ detail.fact_number }}</dd>
      </div>
      <div>
        <dt>物料</dt>
        <dd>{{ detail.material_code }} · {{ detail.material_name }}</dd>
      </div>
      <div>
        <dt>本次计价数量</dt>
        <dd>{{ quantity(detail.quantity) }} {{ detail.unit }}</dd>
      </div>
      <div>
        <dt>单价</dt>
        <dd>{{ detail.unit_price }} CNY / {{ detail.unit }}</dd>
      </div>
      <div>
        <dt>金额依据号</dt>
        <dd>{{ detail.reference }}</dd>
      </div>
      <div>
        <dt>业务日期</dt>
        <dd>{{ detail.business_date }}</dd>
      </div>
      <div>
        <dt>录入说明</dt>
        <dd>{{ detail.reason }}</dd>
      </div>
    </dl>
    <p>
      来源核对：{{
        detail.sourceMatches ? '一致' : '异常，请核对原单'
      }}；往来核对：{{
        detail.balanceMatches ? '一致' : '异常，禁止据此结清'
      }}。
    </p>
    <p class="hint">
      本轮未登记收付款或核销，已确认不代表已收款／付款。不计算税额，也不代表凭证已过账。
    </p>
  </section>
  <section
    v-if="
      (write && ['DRAFT', 'PENDING'].includes(detail.status)) ||
      (review && detail.status === 'PENDING')
    "
    class="card"
  >
    <h2>处理依据</h2>
    <label
      >本次操作依据<textarea
        :value="actionReason"
        aria-label="本次操作依据"
        maxlength="500"
        :disabled="blocked"
        @input="
          emit(
            'update:actionReason',
            ($event.target as HTMLTextAreaElement).value,
          )
        "
      />
    </label>
    <div class="detail-actions">
      <button
        v-if="write && detail.status === 'DRAFT'"
        :disabled="blocked"
        @click="emit('action', 'submit')"
      >
        提交复核
      </button>
      <button
        v-if="review && detail.status === 'PENDING'"
        :disabled="
          blocked ||
          detail.selfReview ||
          detail.created_by === username ||
          !detail.sourceMatches
        "
        @click="emit('action', 'approve')"
      >
        确认往来
      </button>
      <button
        v-if="review && detail.status === 'PENDING'"
        :disabled="
          blocked || detail.selfReview || detail.created_by === username
        "
        @click="emit('action', 'reject')"
      >
        驳回
      </button>
      <button
        v-if="write"
        :disabled="blocked"
        @click="emit('action', 'cancel')"
      >
        取消依据
      </button>
    </div>
    <p
      v-if="(detail.selfReview || detail.created_by === username) && review"
      class="hint"
    >
      录入人或提交人不能复核自己的依据。
    </p>
  </section>
  <section class="card">
    <h2>操作记录</h2>
    <ol>
      <li v-for="h in detail.history" :key="h.id">
        <OperationRecord
          :title="FINANCE_ACTION_NAMES[h.action] || h.action"
          :account="h.actor"
          :at="h.created_at"
          :note="h.reason"
          note-label="操作依据"
        />
      </li>
    </ol>
    <OperationTimeNote v-if="detail.history.length" />
  </section>
</template>

<style scoped>
.card {
  padding: 20px;
  margin: 16px 0;
  border: 1px solid #e4e8ef;
  border-radius: 8px;
  background: white;
}
.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
}
.facts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}
label {
  display: grid;
  gap: 6px;
  margin: 12px 0;
}
textarea {
  max-width: 100%;
  min-width: 0;
  min-height: 64px;
}
dt,
.hint {
  color: #697386;
}
dd {
  margin: 4px 0;
  overflow-wrap: anywhere;
}
ol {
  padding-left: 22px;
}
li {
  margin: 10px 0;
  overflow-wrap: anywhere;
}
</style>
