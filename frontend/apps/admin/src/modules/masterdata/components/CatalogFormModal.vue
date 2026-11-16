<script setup lang="ts">
import { reactive, toRaw } from 'vue'
import { type Warehouse } from '../api'
import { areaNames } from '@/modules/receiving/api'
import {
  CATALOG_LABELS,
  type CatalogDraft,
  type CatalogKind,
  type CatalogRow,
} from '../catalogTypes'

const props = defineProps<{
  tab: CatalogKind
  current?: CatalogRow
  initialDraft: CatalogDraft
  units?: CatalogRow[]
  warehouses: Warehouse[]
  formError: string
  busy: boolean
  ready: boolean
}>()

const emit = defineEmits<{
  close: []
  save: [draft: CatalogDraft]
}>()

const draft = reactive<CatalogDraft>(structuredClone(toRaw(props.initialDraft)))
</script>

<template>
  <div class="overlay">
    <section
      class="modal"
      role="dialog"
      aria-modal="true"
      aria-labelledby="catalog-title"
    >
      <h2 id="catalog-title">
        {{ current ? '编辑' : '新增' }}{{ CATALOG_LABELS[tab] }}
      </h2>
      <p v-if="formError" class="error" role="alert">{{ formError }}</p>
      <p v-if="tab === 'organizations'" class="hint">
        维护当前工厂的组织标识，全部资料共用此归属。
      </p>
      <p v-if="tab === 'units'" class="hint">
        单位必须先登记，再供物料选择。不提供换算；已被物料引用的单位名称不可改义。
      </p>
      <p v-if="tab === 'locations'" class="hint">
        仓库在“仓库管理”中维护；本页新增库位沿用现有仓库规则。
      </p>
      <p v-if="current?.identityLocked" class="hint">
        已被业务引用，仅可修改名称和启停状态。
      </p>
      <p v-if="tab === 'locations' && warehouses.length >= 100" class="hint">
        仓库选项已达到 100 个加载上限，可能未包含全部仓库。
      </p>
      <form @submit.prevent="emit('save', draft)">
        <fieldset class="form-grid" :disabled="busy || !ready">
          <label
            >编码<input
              v-model="draft.code"
              name="code"
              :disabled="!!current"
              required
              maxlength="32"
              pattern="[A-Za-z][A-Za-z0-9-]{1,31}"
          /></label>
          <label
            >名称<input
              v-model="draft.name"
              name="name"
              required
              :maxlength="tab === 'units' ? 16 : 100"
          /></label>
          <label v-if="current && tab !== 'organizations'"
            >状态<select
              v-model="draft.status"
              name="status"
              aria-label="状态"
              aria-describedby="catalog-status-help"
            >
              <option value="ENABLED">启用</option>
              <option value="DISABLED">停用</option></select
            ><small id="catalog-status-help" class="hint"
              >停用后不能用于新业务授权；已有单据仍可按原流程收尾，不会冻结库存。</small
            ></label
          >
          <template v-if="tab === 'materials'">
            <label
              >基本单位<select
                v-model="draft.unitId"
                name="unitId"
                required
                :disabled="!!current?.identityLocked"
              >
                <option :value="0" disabled>请先登记单位</option>
                <option
                  v-for="u in units"
                  :key="u.id"
                  :value="u.id"
                  :disabled="u.status !== 'ENABLED'"
                >
                  {{ u.name }}{{ u.status !== 'ENABLED' ? '（停用）' : '' }}
                </option>
              </select></label
            >
            <label
              >管理方式<select
                v-model="draft.trackingMode"
                name="trackingMode"
                :disabled="!!current?.identityLocked"
              >
                <option value="BATCH">按批次</option>
                <option value="QUANTITY">按数量</option>
              </select></label
            >
            <label class="check"
              ><input
                v-model="draft.requireDateCode"
                name="requireDateCode"
                type="checkbox"
                :disabled="!!current?.identityLocked"
              />Date Code 必填</label
            >
            <label class="check"
              ><input
                v-model="draft.requireExpiry"
                name="requireExpiry"
                type="checkbox"
                :disabled="!!current?.identityLocked"
              />有效期必填</label
            >
          </template>
          <template v-if="tab === 'locations'">
            <label
              >所属仓库<select v-model="draft.warehouseId" required>
                <option :value="0" disabled>请选择仓库</option>
                <option
                  v-for="w in warehouses.filter((w) => w.status === 'ENABLED')"
                  :key="w.id"
                  :value="w.id"
                >
                  {{ w.name }}
                </option>
              </select></label
            >
            <label
              >区域类型<select v-model="draft.areaType">
                <option
                  v-for="(name, key) in areaNames"
                  :key="key"
                  :value="key"
                >
                  {{ name }}
                </option>
              </select></label
            >
          </template>
          <label v-if="current"
            >变更原因<textarea
              v-model="draft.reason"
              required
              maxlength="500"
            />
          </label>
        </fieldset>
        <div class="modal-actions">
          <button type="button" :disabled="busy" @click="emit('close')">
            取消</button
          ><button
            class="primary"
            :disabled="
              busy ||
              !ready ||
              (tab === 'materials' && !draft.unitId) ||
              (tab === 'locations' && !draft.warehouseId)
            "
          >
            {{ busy ? '保存中…' : '保存' }}
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style scoped>
label .hint {
  font-weight: 400;
  line-height: 1.6;
}
</style>
