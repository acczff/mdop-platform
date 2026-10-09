<script setup lang="ts">
import { computed } from 'vue'
import type { Closure, Fulfillment } from '../purchasing'
const props = defineProps<{
  value: Fulfillment
  closure?: Closure | null
  matches: boolean
  canWrite: boolean
  blocked: boolean
  authorities: string[]
}>()
defineEmits<{ refresh: []; close: [] }>()
const saved = computed(() => {
  try {
    return props.closure
      ? (JSON.parse(props.closure.snapshot) as Fulfillment)
      : null
  } catch {
    return null
  }
})
const state: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已批准',
  REJECTED: '已驳回',
  CANCELLED: '已撤销',
  RETURNED: '已实际退供',
  DRAFT: '草稿',
  SUBMITTED: '已提交',
}
</script>

<template>
  <section class="fulfillment" aria-label="采购履约">
    <header>
      <h2>履约进度</h2>
      <div class="controls">
        <button :disabled="blocked" @click="$emit('refresh')">重新核对</button>
        <button
          v-if="canWrite"
          class="primary"
          :disabled="blocked || !value.canClose || !matches"
          @click="$emit('close')"
        >
          确认履约结案
        </button>
      </div>
    </header>
    <p v-if="closure" class="hint">
      {{
        closure.outcome === 'WITH_RETURNS' ? '含退供结案' : '全部合格上架结案'
      }}
      · {{ closure.closed_by }} · {{ closure.closed_at }} <br />结案原因：{{
        closure.reason
      }}
    </p>
    <p v-if="!matches" class="error" role="alert">
      结案后事实发生变化，待核对。原结案快照已保留，不自动重开或覆盖；请核对下方来源单据。
    </p>
    <div v-for="line in value.lines" :key="line.orderLineId" class="line">
      <strong>{{ line.code }} · 订单 {{ line.ordered }} {{ line.unit }}</strong>
      <p>
        净收货 {{ line.netReceived }} / 尚欠 {{ line.remaining
        }}<span class="hint"
          >（已收 {{ line.received }} − 冲正 {{ line.reversed }}）</span
        >
      </p>
      <dl>
        <div>
          <dt>待检</dt>
          <dd>{{ line.pendingInspection }}</dd>
        </div>
        <div>
          <dt>合格待上架</dt>
          <dd>{{ line.pendingPutaway }}</dd>
        </div>
        <div>
          <dt>已合格上架</dt>
          <dd>{{ line.putaway }}</dd>
        </div>
        <div>
          <dt>不合格待退</dt>
          <dd>{{ line.rejectedPendingReturn }}</dd>
        </div>
        <div>
          <dt>已实际退供</dt>
          <dd>{{ line.returned }}</dd>
        </div>
      </dl>
    </div>
    <div v-if="value.blockers.length" class="blockers">
      <strong>未完成事项</strong>
      <ul>
        <li v-for="reason in value.blockers" :key="reason">{{ reason }}</li>
      </ul>
    </div>
    <p v-else-if="!closure" class="hint">
      数量与处置已完成，可以确认{{
        value.outcome === 'WITH_RETURNS' ? '含退供' : '合格交付'
      }}结案。
    </p>
    <p class="hint">
      已上架为累计事实，不随后续领用减少。退供不恢复原订单额度；数量结案不代表财务结清。
    </p>
    <details>
      <summary>来源明细与异常记录</summary>
      <nav>
        <a v-if="authorities.includes('wms:quality:read')" href="/quality"
          >质检与上架</a
        >
        <a
          v-if="authorities.includes('wms:return:read')"
          href="/purchase-returns"
          >不合格品退货</a
        >
        <a
          v-if="authorities.includes('wms:correction:read')"
          href="/corrections"
          >差异与冲正</a
        >
      </nav>
      <article v-for="notice in value.notices" :key="notice.arrangementId">
        <strong
          >到货安排 #{{ notice.arrangementId }} · WMS 通知 #{{
            notice.delivery.arrivalId
          }}</strong
        >
        <p v-for="r in notice.facts.receipts" :key="r.receiptItemId">
          {{ r.receiptNumber }} / 行 #{{ r.receiptItemId }} · {{ r.quantity }} ·
          {{
            r.correctionStatus === 'REVERSED'
              ? '已冲正'
              : state[r.status] || r.status
          }}
          <span v-if="r.qualityReference">
            · 质检 {{ r.qualityReference }}：合格 {{ r.qualified }} / 不合格
            {{ r.rejected
            }}<span v-if="r.putawayLocation">
              · 已上架至库位 #{{ r.putawayLocation }}</span
            ></span
          >
        </p>
        <p v-for="c in notice.facts.cases" :key="`case-${c.id}`">
          {{ c.kind === 'REVERSAL' ? '冲正' : '差异' }} #{{ c.id }} ·
          {{ state[c.status] || c.status }} · {{ c.reason }}
        </p>
        <p v-for="r in notice.facts.returns" :key="`return-${r.id}`">
          退供 #{{ r.id }} / 收货行 #{{ r.receiptItemId }} · {{ r.quantity }} ·
          {{ state[r.status] || r.status
          }}<span v-if="r.handover"> · 交接 {{ r.handover }}</span>
        </p>
      </article>
    </details>
    <details v-if="closure">
      <summary>原结案快照</summary>
      <p v-if="!saved" class="error">快照暂时无法展示，请联系管理员核对。</p>
      <p v-for="line in saved?.lines || []" :key="line.orderLineId">
        {{ line.code }} · 净收货 {{ line.netReceived }} · 合格上架
        {{ line.putaway }} · 实际退供 {{ line.returned }}
      </p>
    </details>
  </section>
</template>

<style scoped>
.fulfillment {
  margin-top: 24px;
  border-top: 1px solid #ece9f1;
  padding-top: 20px;
}
header,
.controls,
nav {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
header {
  justify-content: space-between;
}
h2 {
  margin: 0;
  font-size: 18px;
}
.line {
  padding: 16px 0;
  border-bottom: 1px solid #ece9f1;
}
dl {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 14px;
  margin: 16px 0 0;
}
dt,
.hint {
  font-size: 13px;
  color: #6c687d;
}
dd {
  margin: 6px 0 0;
  font-variant-numeric: tabular-nums;
}
.blockers {
  margin-top: 16px;
}
li {
  margin: 6px 0;
}
details {
  margin-top: 14px;
}
summary {
  cursor: pointer;
}
article {
  padding: 12px 0;
}
p {
  overflow-wrap: anywhere;
}
nav {
  margin-top: 12px;
}
</style>
