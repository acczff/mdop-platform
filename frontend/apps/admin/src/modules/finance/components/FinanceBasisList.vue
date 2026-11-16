<script setup lang="ts">
import ResultPager from '@/shared/components/ResultPager.vue'
import { quantity } from '@/shared/presentation'
import type { Warehouse } from '@/modules/masterdata/api'
import { FINANCE_LABELS, type Baseline, type Basis, type Fact } from '../api'

defineProps<{
  warehouse: number
  direction: string
  orderNumber: string
  warehouses: Warehouse[]
  baseline?: Baseline
  result?: { items: Basis[]; total: number }
  facts?: Fact[]
  selected?: number
  page: number
  blocked: boolean
  busy: boolean
  locked: boolean
  write: boolean
  reference: string
  businessDate: string
  unitPrice: string
  amount: string
  reason: string
  expected: string
  hasSource: boolean
}>()

const emit = defineEmits<{
  'update:warehouse': [value: number]
  'update:direction': [value: string]
  'update:orderNumber': [value: string]
  'update:selected': [value: number | undefined]
  'update:reference': [value: string]
  'update:businessDate': [value: string]
  'update:unitPrice': [value: string]
  'update:amount': [value: string]
  'update:reason': [value: string]
  refresh: [page?: number]
  open: [id: number]
  findSources: []
  create: []
}>()

function onWarehouseChange(event: Event) {
  emit('update:warehouse', Number((event.target as HTMLSelectElement).value))
  emit('refresh', 0)
}

function onDirectionChange(event: Event) {
  emit('update:direction', (event.target as HTMLSelectElement).value)
  emit('refresh', 0)
}
</script>

<template>
  <section class="card">
    <div class="toolbar">
      <label
        >仓库<select
          :value="warehouse"
          :disabled="blocked"
          @change="onWarehouseChange"
        >
          <option :value="0">选择仓库</option>
          <option v-for="w in warehouses" :key="w.id" :value="w.id">
            {{ w.code }} · {{ w.name }}
          </option>
        </select></label
      ><label
        >往来方向<select
          :value="direction"
          :disabled="blocked"
          @change="onDirectionChange"
        >
          <option value="PAYABLE">采购应付</option>
          <option value="RECEIVABLE">销售应收</option>
        </select></label
      ><button :disabled="busy || locked" @click="emit('refresh')">刷新</button>
    </div>
    <div v-if="result" class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>金额依据 / 原订单</th>
            <th>往来方 / 物料</th>
            <th>金额（CNY）</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="b in result.items" :key="b.id">
            <td>
              <button
                class="link-button"
                :data-basis-id="b.id"
                :disabled="blocked"
                @click="emit('open', b.id)"
              >
                {{ b.number }}</button
              ><small>{{ b.order_number }}</small>
            </td>
            <td>
              {{ b.party_name
              }}<small>{{ b.material_code }} · {{ b.material_name }}</small>
            </td>
            <td>
              {{ b.amount
              }}<small>{{
                b.open_amount === null
                  ? '未确认往来'
                  : '未核销 ' + b.open_amount
              }}</small>
            </td>
            <td>{{ FINANCE_LABELS[b.status] }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="!result.items.length">当前筛选条件下没有金额依据。</p>
      <ResultPager
        :total="result.total"
        :page="page"
        :disabled="blocked"
        @change="emit('refresh', $event)"
      />
    </div>
  </section>
  <section v-if="baseline?.status === 'ACTIVE'" class="card">
    <h2>{{ write ? '登记金额依据' : '查询业务来源' }}</h2>
    <form @submit.prevent="emit('findSources')">
      <fieldset :disabled="blocked">
        <label
          >业务订单号<input
            :value="orderNumber"
            aria-label="业务订单号"
            :placeholder="
              direction === 'PAYABLE' ? 'PO-00000001' : 'SO-00000001'
            "
            maxlength="40"
            required
            @input="
              emit(
                'update:orderNumber',
                ($event.target as HTMLInputElement).value,
              )
            " /></label
        ><button type="submit">查询收发事实</button>
      </fieldset>
    </form>
    <p v-if="facts && !facts.length">
      未找到可计价事实。请核对仓库和订单号；采购须合格上架，销售须实际出库。
    </p>
    <form v-if="facts?.length && write" @submit.prevent="emit('create')">
      <fieldset :disabled="blocked">
        <label
          >收发事实<select
            :value="selected"
            aria-label="收发事实"
            required
            @change="
              emit(
                'update:selected',
                ($event.target as HTMLSelectElement).value
                  ? Number(($event.target as HTMLSelectElement).value)
                  : undefined,
              )
            "
          >
            <option :value="undefined">请选择</option>
            <option v-for="f in facts" :key="f.factId" :value="f.factId">
              {{ f.factNumber }} · {{ f.materialCode }} ·
              {{ quantity(f.quantity) }} {{ f.unit }} · {{ f.partyName }}
            </option>
          </select></label
        >
        <div class="form-grid">
          <label
            >金额依据号<input
              :value="reference"
              aria-label="金额依据号"
              required
              maxlength="100"
              @input="
                emit(
                  'update:reference',
                  ($event.target as HTMLInputElement).value,
                )
              " /></label
          ><label
            >业务日期<input
              :value="businessDate"
              aria-label="业务日期"
              type="date"
              :min="baseline.start_date || undefined"
              :max="baseline.end_date || undefined"
              required
              @input="
                emit(
                  'update:businessDate',
                  ($event.target as HTMLInputElement).value,
                )
              " /></label
          ><label
            >单价（CNY）<input
              :value="unitPrice"
              aria-label="单价"
              inputmode="decimal"
              required
              @input="
                emit(
                  'update:unitPrice',
                  ($event.target as HTMLInputElement).value,
                )
              " /></label
          ><label
            >确认金额（CNY）<input
              :value="amount"
              aria-label="确认金额"
              inputmode="decimal"
              required
              @input="
                emit('update:amount', ($event.target as HTMLInputElement).value)
              "
          /></label>
        </div>
        <p v-if="expected">
          按行计算金额：{{ expected }} CNY，请核对后填写确认金额。
        </p>
        <label
          >录入依据<textarea
            :value="reason"
            aria-label="录入依据"
            required
            maxlength="500"
            @input="
              emit(
                'update:reason',
                ($event.target as HTMLTextAreaElement).value,
              )
            "
          /></label
        ><button type="submit" :disabled="!hasSource">保存草稿</button>
      </fieldset>
    </form>
    <ul v-else-if="facts?.length">
      <li v-for="f in facts" :key="f.factId">
        {{ f.factNumber }} · {{ f.materialCode }} · {{ quantity(f.quantity) }}
        {{ f.unit }} · {{ f.partyName }}
      </li>
    </ul>
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
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
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
select,
textarea {
  max-width: 100%;
  min-width: 0;
}
textarea {
  min-height: 64px;
}
.table-wrap {
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
}
td,
th {
  padding: 12px;
  text-align: left;
}
small {
  display: block;
  color: #697386;
  margin-top: 4px;
}
</style>
