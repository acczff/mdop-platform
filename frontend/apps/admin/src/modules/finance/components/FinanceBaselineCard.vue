<script setup lang="ts">
import OperationRecord from '@/shared/components/OperationRecord.vue'
import OperationTimeNote from '@/shared/components/OperationTimeNote.vue'
import { FINANCE_ACTION_NAMES, FINANCE_LABELS, type Baseline } from '../api'

defineProps<{
  baseline: Baseline
  organization: string
  startDate: string
  endDate: string
  setupReason: string
  blocked: boolean
  write: boolean
  review: boolean
  username: string
}>()

const emit = defineEmits<{
  'update:organization': [value: string]
  'update:startDate': [value: string]
  'update:endDate': [value: string]
  'update:setupReason': [value: string]
  setup: []
  reviewSetup: [name: string]
}>()
</script>

<template>
  <details class="card" :open="baseline.status !== 'ACTIVE'">
    <summary>演示基线 · {{ FINANCE_LABELS[baseline.status] }}</summary>
    <p>
      DEMO-CNY-V1：金额 2 位、单价 6
      位、按行四舍五入；一条收发事实整量计价一次，不回填历史金额。
    </p>
    <p v-if="baseline.organization">
      {{ baseline.organization }} · 开放期间 {{ baseline.start_date }} 至
      {{ baseline.end_date }}
    </p>
    <form
      v-if="write && ['UNINITIALIZED', 'REJECTED'].includes(baseline.status)"
      @submit.prevent="emit('setup')"
    >
      <fieldset :disabled="blocked">
        <div class="form-grid">
          <label
            >演示组织<input
              :value="organization"
              required
              maxlength="100"
              @input="
                emit(
                  'update:organization',
                  ($event.target as HTMLInputElement).value,
                )
              " /></label
          ><label
            >启用日<input
              :value="startDate"
              type="date"
              required
              @input="
                emit(
                  'update:startDate',
                  ($event.target as HTMLInputElement).value,
                )
              " /></label
          ><label
            >期间结束日<input
              :value="endDate"
              type="date"
              required
              @input="
                emit(
                  'update:endDate',
                  ($event.target as HTMLInputElement).value,
                )
              "
          /></label>
        </div>
        <label
          >初始化依据<textarea
            :value="setupReason"
            required
            maxlength="500"
            @input="
              emit(
                'update:setupReason',
                ($event.target as HTMLTextAreaElement).value,
              )
            "
          /></label
        ><button type="submit">提交初始化</button>
      </fieldset>
    </form>
    <div v-if="review && baseline.status === 'PENDING'">
      <label
        >初始化复核依据<textarea
          :value="setupReason"
          :disabled="blocked"
          maxlength="500"
          @input="
            emit(
              'update:setupReason',
              ($event.target as HTMLTextAreaElement).value,
            )
          "
        /></label
      ><button
        :disabled="blocked || baseline.created_by === username"
        @click="emit('reviewSetup', 'approve')"
      >
        复核启用</button
      ><button
        :disabled="blocked || baseline.created_by === username"
        @click="emit('reviewSetup', 'reject')"
      >
        驳回初始化
      </button>
    </div>
    <ol>
      <li v-for="h in baseline.history" :key="h.id">
        <OperationRecord
          :title="FINANCE_ACTION_NAMES[h.action] || h.action"
          :account="h.actor"
          :at="h.created_at"
          :note="h.reason"
          note-label="操作依据"
        />
      </li>
    </ol>
    <OperationTimeNote v-if="baseline.history.length" />
  </details>
</template>

<style scoped>
.card {
  padding: 20px;
  margin: 16px 0;
  border: 1px solid #e4e8ef;
  border-radius: 8px;
  background: white;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
}
label {
  display: grid;
  gap: 6px;
  margin: 12px 0;
}
fieldset {
  border: 0;
  padding: 0;
  min-width: 0;
}
input,
textarea {
  max-width: 100%;
  min-width: 0;
}
textarea {
  min-height: 64px;
}
summary {
  cursor: pointer;
}
ol {
  padding-left: 22px;
}
li {
  margin: 10px 0;
  overflow-wrap: anywhere;
}
</style>
