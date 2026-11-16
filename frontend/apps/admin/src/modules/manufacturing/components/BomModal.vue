<script setup lang="ts">
import { ref, toRaw } from 'vue'
import {
  bomLabel,
  type BomDraft,
  type BomMaterial,
  type PendingBomRequest,
} from '../bomTypes'

const props = defineProps<{
  mode: string
  initialDraft: BomDraft
  materials: BomMaterial[]
  copyLabel: string
  reason: string
  formError: string
  busy: boolean
  pending?: PendingBomRequest
}>()

const emit = defineEmits<{
  'update:copyLabel': [value: string]
  'update:reason': [value: string]
  close: []
  send: [draft: BomDraft]
}>()

const draft = ref<BomDraft>(structuredClone(toRaw(props.initialDraft)))

function unit(id: number) {
  return props.materials.find((m) => m.id === id)?.unit || '基本单位'
}
</script>

<template>
  <div class="dialog-backdrop">
    <section
      class="dialog"
      role="dialog"
      aria-modal="true"
      :aria-label="bomLabel(mode)"
      @keydown.esc="emit('close')"
    >
      <h2>{{ bomLabel(mode) }}</h2>
      <form @submit.prevent="emit('send', draft)">
        <div v-if="mode === 'create' || mode === 'edit'" class="form-grid">
          <label
            >产品<select
              v-model="draft.productId"
              required
              :disabled="busy || mode === 'edit'"
            >
              <option :value="0" disabled>请选择产品</option>
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
            >版本标识<input
              v-model="draft.versionLabel"
              required
              maxlength="32"
              pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,31}"
              :disabled="busy || mode === 'edit'"
          /></label>
          <label
            >基准产量（{{ unit(draft.productId) }}）<input
              v-model="draft.baseQuantity"
              required
              inputmode="decimal"
              :disabled="busy"
          /></label>
          <label
            >用途说明<textarea
              v-model="draft.description"
              required
              maxlength="500"
              :disabled="busy"
            />
          </label>
          <p class="muted">
            物料目录最多 1000 条。按基本单位填写，不自动合并重复组件。
          </p>
          <div
            v-for="(c, i) in draft.components"
            :key="i"
            class="component-row"
          >
            <label
              >组件 {{ i + 1
              }}<select v-model="c.materialId" required :disabled="busy">
                <option :value="0" disabled>请选择组件</option>
                <option
                  v-for="m in materials"
                  :key="m.id"
                  :value="m.id"
                  :disabled="m.status !== 'ENABLED' || m.id === draft.productId"
                >
                  {{ m.code }} · {{ m.name }}（{{ m.unit }}）
                </option>
              </select></label
            >
            <label
              >用量 {{ i + 1
              }}<input
                v-model="c.quantity"
                required
                inputmode="decimal"
                :disabled="busy"
            /></label>
            <button
              type="button"
              :disabled="busy || draft.components.length === 1"
              @click="draft.components.splice(i, 1)"
            >
              移除
            </button>
          </div>
          <button
            type="button"
            :disabled="busy || draft.components.length >= 100"
            @click="draft.components.push({ materialId: 0, quantity: '' })"
          >
            添加组件
          </button>
        </div>
        <label v-if="mode === 'copy'"
          >新版本标识<input
            :value="copyLabel"
            required
            maxlength="32"
            pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,31}"
            :disabled="busy"
            @input="
              emit(
                'update:copyLabel',
                ($event.target as HTMLInputElement).value,
              )
            "
        /></label>
        <p v-if="mode === 'copy'">
          复制为同产品的新草稿，原版本不变；发布前请核对当前物料及单位。
        </p>
        <p v-if="mode === 'publish'">
          发布后内容不可修改，后续变更须复制新版本。系统将校验物料、基本单位和循环引用。
        </p>
        <p v-if="mode === 'disable'">
          停用后不得用于新生产授权，原内容和历史保留，不能恢复为草稿。
        </p>
        <label
          >操作原因<textarea
            :value="reason"
            required
            maxlength="500"
            :disabled="busy"
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
          <button
            type="button"
            :disabled="busy || !!pending"
            @click="emit('close')"
          >
            取消</button
          ><button class="primary" :disabled="busy || !!pending">
            {{
              busy
                ? '处理中…'
                : mode === 'create' || mode === 'edit'
                  ? '保存草稿'
                  : `确认${bomLabel(mode)}`
            }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  margin: 16px 0;
}
label {
  display: grid;
  gap: 6px;
}
.muted {
  color: #64748b;
}
.error {
  color: #b42318;
}
input,
select,
textarea {
  box-sizing: border-box;
  max-width: 100%;
  padding: 9px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font: inherit;
  background: white;
  color: inherit;
}
textarea {
  min-height: 64px;
  resize: vertical;
}
button {
  white-space: nowrap;
  padding: 8px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: white;
  color: inherit;
  cursor: pointer;
  font: inherit;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
button.primary {
  color: white;
  background: #6d28d9;
  border-color: #6d28d9;
}
.dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 50;
  background: #0f172a66;
  display: grid;
  place-items: center;
  padding: 16px;
}
.dialog {
  box-sizing: border-box;
  width: min(720px, 100%);
  max-height: 90dvh;
  overflow: auto;
  background: white;
  padding: 24px;
  border-radius: 10px;
}
.form-grid {
  display: grid;
  gap: 16px;
  margin-bottom: 16px;
}
.component-row {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr) auto;
  gap: 8px;
  align-items: end;
}
@media (max-width: 600px) {
  .component-row {
    grid-template-columns: 1fr;
  }
  .dialog {
    padding: 16px;
  }
}
</style>
