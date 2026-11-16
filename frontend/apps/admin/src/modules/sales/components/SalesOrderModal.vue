<script setup lang="ts">
import { ref, toRaw } from 'vue'
import { remaining } from '@/shared/quantity'
import {
  allocatedQuantity,
  SALES_ACTIONS,
  type PendingSalesRequest,
  type SalesDirectoryEntry,
  type SalesDocument,
  type SalesOrderDraft,
} from '../api'

const props = defineProps<{
  mode: string
  detail?: SalesDocument
  initialDraft: SalesOrderDraft
  arrangementLine: number
  arrangementQty: string
  reason: string
  customers: SalesDirectoryEntry[]
  materials: SalesDirectoryEntry[]
  formError: string
  error: string
  busy: boolean
  locked: boolean
  pending?: PendingSalesRequest
  pendingPermission: boolean
}>()

const emit = defineEmits<{
  'update:arrangementLine': [value: number]
  'update:arrangementQty': [value: string]
  'update:reason': [value: string]
  close: []
  send: [draft: SalesOrderDraft]
  retry: []
}>()

const draft = ref<SalesOrderDraft>(structuredClone(toRaw(props.initialDraft)))

function allocated(lineId: number) {
  return allocatedQuantity(props.detail?.arrangements, lineId)
}
</script>

<template>
  <div class="overlay">
    <section
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="sales-modal-title"
    >
      <h2 id="sales-modal-title">
        {{
          mode === 'create'
            ? '新建销售订单'
            : mode === 'edit'
              ? '编辑订单'
              : SALES_ACTIONS[mode]
        }}
      </h2>
      <p v-if="formError" class="error" role="alert">{{ formError }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="mode === 'withdraw'" class="hint">
        尚未实际出库的安排可撤回；WMS 确认取消并释放预占后，才释放订单额度。
      </p>
      <p v-if="mode === 'close'" class="hint">
        逐行核对授权数量、实际出库及流水，保存不可覆盖的数量结案快照。
      </p>
      <form @submit.prevent="emit('send', draft)">
        <fieldset :disabled="busy || !!pending">
          <div class="form-grid">
            <template v-if="['create', 'edit'].includes(mode)"
              ><label
                >用途<input
                  v-model="draft.purpose"
                  required
                  maxlength="500" /></label
              ><label
                >客户<select v-model="draft.customerId" required>
                  <option :value="0" disabled>请选择客户</option>
                  <option
                    v-for="c in customers"
                    :key="c.id"
                    :value="c.id"
                    :disabled="c.status !== 'ENABLED'"
                  >
                    {{ c.code }} · {{ c.name
                    }}{{ c.status !== 'ENABLED' ? '（停用）' : '' }}
                  </option>
                </select></label
              ><label
                >客户单号（选填）<input
                  v-model="draft.customerReference"
                  maxlength="128" /></label
            ></template>
            <label v-if="['create', 'edit', 'arrange'].includes(mode)"
              >{{ mode === 'arrange' ? '预计发货日期' : '约定交付日期'
              }}<input v-model="draft.neededDate" type="date" required
            /></label>
          </div>
          <template v-if="mode === 'arrange'"
            ><label
              >订单物料<select
                :value="arrangementLine"
                required
                @change="
                  emit(
                    'update:arrangementLine',
                    Number(($event.target as HTMLSelectElement).value),
                  )
                "
              >
                <option v-for="l in detail?.lines" :key="l.id" :value="l.id">
                  {{ l.material_code }} · 可安排
                  {{ remaining(l.quantity, allocated(l.id)) }} {{ l.unit }}
                </option>
              </select></label
            ><label class="reason"
              >本次安排数量<input
                :value="arrangementQty"
                required
                inputmode="decimal"
                pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
                @input="
                  emit(
                    'update:arrangementQty',
                    ($event.target as HTMLInputElement).value,
                  )
                " /></label
          ></template>
          <template v-if="['create', 'edit'].includes(mode)"
            ><p class="hint">
              使用基本单位，同物料自动合并。批准后数量不可修改。
            </p>
            <div
              v-for="(l, index) in draft.lines"
              :key="index"
              class="draft-line"
            >
              <label
                >物料 {{ index + 1
                }}<select v-model="l.materialId" required>
                  <option :value="0" disabled>请选择物料</option>
                  <option
                    v-for="m in materials"
                    :key="m.id"
                    :value="m.id"
                    :disabled="m.status !== 'ENABLED'"
                  >
                    {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                  </option>
                </select></label
              ><label
                >数量 {{ index + 1
                }}<input
                  v-model="l.quantity"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?" /></label
              ><button
                type="button"
                :disabled="draft.lines.length === 1"
                @click="draft.lines.splice(index, 1)"
              >
                移除
              </button>
            </div>
            <button
              type="button"
              :disabled="draft.lines.length >= 100"
              @click="draft.lines.push({ materialId: 0, quantity: '' })"
            >
              添加物料
            </button>
            <p
              v-if="materials.length >= 1000 || customers.length >= 1000"
              class="hint"
            >
              目录最多加载 1000 条，未找到资料时请联系资料管理员核对。
            </p></template
          >
          <label v-if="mode !== 'create'" class="reason"
            >{{ mode === 'edit' ? '变更原因' : '操作说明'
            }}<textarea
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
          <div class="modal-actions">
            <button type="button" @click="emit('close')">取消</button
            ><button class="primary" type="submit">
              {{
                busy
                  ? '处理中…'
                  : ['create', 'edit'].includes(mode)
                    ? '保存草稿'
                    : '确认' + (SALES_ACTIONS[mode] || '操作')
              }}
            </button>
          </div>
        </fieldset>
      </form>
      <p v-if="pending && !pendingPermission">
        当前账号无原操作权限，请联系管理员核对。
      </p>
      <button
        v-if="pending"
        :disabled="locked || busy || !pendingPermission"
        @click="emit('retry')"
      >
        结果待核对，原样重试
      </button>
    </section>
  </div>
</template>

<style scoped>
.draft-line {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) auto;
  gap: 10px;
  align-items: end;
  margin: 12px 0;
}
.reason {
  margin-top: 16px;
}
@media (max-width: 640px) {
  .draft-line {
    grid-template-columns: 1fr;
  }
}
</style>
