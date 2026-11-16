<script setup lang="ts">
import { ref, toRaw } from 'vue'
import { quantity as displayQuantity } from '@/shared/presentation'
import {
  productionLabel,
  type BomSnapshot,
  type DemandDraft,
  type ManufacturingDirectoryEntry,
  type OrderDraft,
  type PendingProductionRequest,
  type ProductionDemand,
  type ProductionOrderItem,
  type SalesSourceLine,
} from '../productionOrderTypes'

const props = defineProps<{
  mode: string
  detail?: ProductionDemand
  selected?: ProductionOrderItem
  initialDemand: DemandDraft
  initialOrder: OrderDraft
  reason: string
  materials: ManufacturingDirectoryEntry[]
  sites: ManufacturingDirectoryEntry[]
  boms: BomSnapshot[]
  sources: SalesSourceLine[]
  formError: string
  busy: boolean
  pending?: PendingProductionRequest
}>()

const emit = defineEmits<{
  'update:reason': [value: string]
  close: []
  send: [payload: { demand: DemandDraft; order: OrderDraft }]
}>()

const demand = ref<DemandDraft>(structuredClone(toRaw(props.initialDemand)))
const order = ref<OrderDraft>(structuredClone(toRaw(props.initialOrder)))
</script>

<template>
  <div class="modal-backdrop">
    <section
      class="modal"
      role="dialog"
      aria-modal="true"
      :aria-label="productionLabel(mode)"
    >
      <h2>{{ productionLabel(mode) }}</h2>
      <form @submit.prevent="emit('send', { demand, order })">
        <fieldset :disabled="busy || !!pending">
          <template v-if="mode === 'create'">
            <label
              >需求来源<select v-model="demand.sourceType">
                <option value="MANUAL">手工依据</option>
                <option value="SALES">已批准销售行</option>
              </select></label
            >
            <template v-if="demand.sourceType === 'MANUAL'"
              ><label
                >来源依据<input
                  v-model="demand.sourceReference"
                  required
                  maxlength="100" /></label
              ><label
                >产品<select v-model.number="demand.productId" required>
                  <option :value="0" disabled>选择启用物料</option>
                  <option
                    v-for="m in materials.filter((m) => m.status === 'ENABLED')"
                    :key="m.id"
                    :value="m.id"
                  >
                    {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                  </option>
                </select></label
              ><label
                >计划数量<input
                  v-model="demand.quantity"
                  required
                  inputmode="decimal"
                  pattern="(?:0|[1-9][0-9]{0,11})(?:\.[0-9]{1,6})?" /></label
              ><label
                >需求日期<input
                  v-model="demand.neededDate"
                  type="date"
                  required /></label
            ></template>
            <label v-else
              >销售行<select v-model.number="demand.salesLineId" required>
                <option :value="0" disabled>选择销售行</option>
                <option v-for="s in sources" :key="s.id" :value="s.id">
                  {{ s.document_no }} / {{ s.id }} · {{ s.material_code }} ·
                  {{ displayQuantity(s.quantity) }} {{ s.unit }}
                </option>
              </select></label
            >
            <label
              >生产用途<textarea
                v-model="demand.purpose"
                required
                maxlength="500"
              />
            </label>
            <p class="muted">
              产品与数量保存后不改写；销售来源整行转需求，不自动扣除库存。目录最多显示
              1000 条。
            </p>
          </template>
          <template v-else-if="mode === 'order' || mode === 'edit'"
            ><p>
              {{ detail?.product_code }} ·
              {{ displayQuantity(detail?.quantity) }}
              {{ detail?.unit }}
            </p>
            <label
              >生产地点<select v-model.number="order.siteId" required>
                <option :value="0" disabled>选择启用地点</option>
                <option
                  v-for="s in sites.filter((s) => s.status === 'ENABLED')"
                  :key="s.id"
                  :value="s.id"
                >
                  {{ s.code }} · {{ s.name }}
                </option>
              </select></label
            ><label
              >BOM 版本<select v-model.number="order.bomId" required>
                <option :value="0" disabled>明确选择已发布版本</option>
                <option v-for="b in boms" :key="b.id" :value="b.id">
                  {{ b.version_label }} · 基准
                  {{ displayQuantity(b.base_quantity) }}
                  {{ b.product_unit }}
                </option>
              </select></label
            ><label
              >计划日期<input v-model="order.plannedDate" type="date" required
            /></label>
            <p class="muted">
              保存所选版本快照，后续新版本不覆盖本工单。地点及版本最多显示 1000
              条。
            </p></template
          >
          <p v-else>
            {{ selected?.order_no || detail?.source_reference }} ·
            {{ displayQuantity(detail?.quantity) }} {{ detail?.unit }}<br />{{
              mode === 'cancel'
                ? '有有效采购、领料、待复核或已批准完工记录时不能取消；历史记录保留。'
                : mode === 'approve'
                  ? '批准后固定 BOM、地点和生产数量；不自动发料或报工。'
                  : '请核对当前依据并填写原因。'
            }}
          </p>
          <label v-if="mode !== 'create'"
            >操作原因<textarea
              :value="reason"
              required
              maxlength="500"
              @input="
                emit(
                  'update:reason',
                  ($event.target as HTMLTextAreaElement).value,
                )
              "
            />
          </label>
          <p v-if="formError" role="alert" class="error">{{ formError }}</p>
          <div class="toolbar">
            <button type="button" @click="emit('close')">返回</button
            ><button
              class="primary"
              :disabled="
                busy ||
                ((mode === 'order' || mode === 'edit') &&
                  !boms.some((b) => b.id === order.bomId))
              "
            >
              {{ busy ? '处理中…' : '确认保存' }}
            </button>
          </div>
        </fieldset>
      </form>
    </section>
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
button {
  white-space: nowrap;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: #0005;
  display: grid;
  place-items: center;
  padding: 16px;
}
.modal {
  width: min(560px, 100%);
  max-height: 90dvh;
  overflow: auto;
  background: white;
  padding: 24px;
  border-radius: 12px;
  box-sizing: border-box;
}
fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
  display: grid;
  gap: 16px;
}
input,
select,
textarea {
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
textarea {
  min-height: 70px;
}
</style>
