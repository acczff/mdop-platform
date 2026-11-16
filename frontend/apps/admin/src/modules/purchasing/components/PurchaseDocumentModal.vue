<script setup lang="ts">
import { ref, toRaw } from 'vue'
import { identity } from '@/shared/presentation'
import { remaining } from '@/shared/quantity'
import {
  allocatedQuantity,
  PURCHASE_ACTIONS,
  type DirectoryEntry,
  type PendingRequest,
  type PurchaseDocument,
  type PurchaseDraft,
} from '../api'

const props = defineProps<{
  mode: string
  detail?: PurchaseDocument
  initialDraft: PurchaseDraft
  initialArrangementLines: { orderLineId: number; quantity: string }[]
  reason: string
  originalOrderId: number | null
  suppliers: DirectoryEntry[]
  materials: DirectoryEntry[]
  formError: string
  error: string
  busy: boolean
  locked: boolean
  pending?: PendingRequest
  pendingPermission: boolean
}>()

const emit = defineEmits<{
  'update:reason': [value: string]
  close: []
  send: [
    payload: {
      draft: PurchaseDraft
      arrangementLines: { orderLineId: number; quantity: string }[]
    },
  ]
  retry: []
}>()

const draft = ref<PurchaseDraft>(structuredClone(toRaw(props.initialDraft)))
const arrangementLines = ref<{ orderLineId: number; quantity: string }[]>(
  structuredClone(toRaw(props.initialArrangementLines)),
)

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
      aria-labelledby="purchase-modal-title"
    >
      <h2 id="purchase-modal-title">
        {{
          mode === 'create'
            ? '新建采购需求'
            : mode === 'edit'
              ? '编辑单据'
              : PURCHASE_ACTIONS[mode]
        }}
      </h2>
      <p v-if="formError" class="error" role="alert">{{ formError }}</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p
        v-if="originalOrderId && ['create', 'edit'].includes(mode)"
        class="hint"
      >
        补货原订单 #{{
          originalOrderId
        }}，建单后保留关联。数量需人工填写并重新审批，不恢复原单额度。
      </p>
      <p v-if="mode === 'cancel'" class="hint">
        取消保留原单和历史。采购订单取消成功后，对应需求可重新转单。
      </p>
      <p v-if="mode === 'convert'" class="hint">
        按已批准需求整单转入一个供应商，数量与单位保持不变。
      </p>
      <p v-if="mode === 'close'" class="hint">
        结案按当前收货、质量、上架及实际退供事实核对并保存快照。含退供不等于全部合格，不恢复补货额度，也不代表财务结清。
      </p>
      <p v-if="mode === 'withdraw'" class="hint">
        只有没有任何收货记录（含草稿）的安排才能撤回；WMS 确认撤回后才释放数量。
      </p>
      <p v-if="mode === 'deliver'" class="hint">
        将这份安排送达 WMS 供仓管收货；重复请求不会新建另一份通知。
      </p>
      <form @submit.prevent="emit('send', { draft, arrangementLines })">
        <fieldset :disabled="busy || !!pending">
          <div class="form-grid">
            <template v-if="mode === 'create' || mode === 'edit'"
              ><label
                >用途<input
                  v-model="draft.purpose"
                  required
                  maxlength="500" /></label
            ></template>
            <label
              v-if="['create', 'edit', 'convert', 'arrange'].includes(mode)"
              >{{
                mode === 'arrange'
                  ? '预计到货日期'
                  : (mode === 'edit' && detail?.kind === 'ORDER') ||
                      mode === 'convert'
                    ? '约定到货日期'
                    : '需要日期'
              }}<input v-model="draft.neededDate" type="date" required
            /></label>
            <label
              v-if="
                mode === 'convert' ||
                (mode === 'edit' && detail?.kind === 'ORDER')
              "
              >供应商<select v-model="draft.supplierId" required>
                <option :value="0" disabled>请选择供应商</option>
                <option
                  v-for="s in suppliers"
                  :key="s.id"
                  :value="s.id"
                  :disabled="s.status !== 'ENABLED'"
                >
                  {{ s.code }} · {{ s.name
                  }}{{ s.status !== 'ENABLED' ? '（停用）' : '' }}
                </option>
              </select></label
            >
          </div>
          <template v-if="mode === 'arrange'">
            <p class="hint">填写本次安排数量；不安排的物料留空。</p>
            <label
              v-for="(l, index) in arrangementLines"
              :key="l.orderLineId"
              class="reason"
              >{{
                identity(
                  detail?.lines[index]?.material_name,
                  detail?.lines[index]?.material_code,
                )
              }}
              · 剩余
              {{
                remaining(
                  detail?.lines[index]?.quantity || '0',
                  allocated(l.orderLineId),
                )
              }}
              {{ detail?.lines[index]?.unit }}
              <input
                v-model="l.quantity"
                :aria-label="`安排数量 ${index + 1}`"
                inputmode="decimal"
                pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
              />
            </label>
          </template>
          <template v-if="mode === 'create' || mode === 'edit'">
            <p class="hint">
              使用基本单位；同物料自动合并。订单保留已批准需求数量。
            </p>
            <div
              v-for="(l, index) in draft.lines"
              :key="index"
              class="draft-line"
            >
              <label
                >物料 {{ index + 1
                }}<select
                  v-model="l.materialId"
                  required
                  :disabled="mode === 'edit' && detail?.kind === 'ORDER'"
                >
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
              >
              <label
                >数量 {{ index + 1
                }}<input
                  v-model="l.quantity"
                  required
                  inputmode="decimal"
                  pattern="[0-9]{1,12}(\.[0-9]{1,6})?"
                  :disabled="mode === 'edit' && detail?.kind === 'ORDER'"
              /></label>
              <button
                v-if="mode === 'create' || detail?.kind === 'REQUEST'"
                type="button"
                :disabled="draft.lines.length === 1"
                @click="draft.lines.splice(index, 1)"
              >
                移除
              </button>
            </div>
            <button
              v-if="mode === 'create' || detail?.kind === 'REQUEST'"
              type="button"
              :disabled="draft.lines.length >= 100"
              @click="draft.lines.push({ materialId: 0, quantity: '' })"
            >
              添加物料
            </button>
            <p
              v-if="materials.length >= 1000 || suppliers.length >= 1000"
              class="hint"
            >
              目录最多加载 1000 条；未找到所需资料时请联系资料管理员核对。
            </p>
          </template>
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
                  : mode === 'create' || mode === 'edit'
                    ? '保存草稿'
                    : '确认' + (PURCHASE_ACTIONS[mode] || '操作')
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
