<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { request, type Page, type Warehouse } from '../api'
const props = defineProps<{ authorities: string[] }>()
interface Transfer {
  id: number
  material_code: string
  material_name: string
  unit: string
  source_location: string
  target_location: string
  quantity: string
  batch_no: string
  reason: string
  confirmed_by: string
  confirmed_at: string
}
const warehouses = ref<Warehouse[]>([]),
  warehouseId = ref(0),
  result = ref<Page<Transfer>>(),
  busy = ref(false),
  error = ref('')
async function load(page = 0) {
  if (!warehouseId.value) return
  busy.value = true
  error.value = ''
  result.value = undefined
  try {
    result.value = await request<Page<Transfer>>(
      `/api/v1/wms/transfers?warehouseId=${warehouseId.value}&page=${page}&size=20`,
    )
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  try {
    let page = 1
    while (true) {
      const rows = await request<Page<Warehouse>>(
        `/api/master-data/warehouses?page=${page}&size=100`,
      )
      warehouses.value.push(
        ...rows.items.filter(
          (w) =>
            props.authorities.includes('ROLE_ADMIN') ||
            props.authorities.includes(`wms:warehouse:${w.id}`),
        ),
      )
      if (page++ >= rows.totalPages) break
    }
    warehouseId.value = warehouses.value[0]?.id || 0
    await load()
  } catch (e) {
    error.value = (e as Error).message
  }
})
</script>
<template>
  <main class="page">
    <header class="page-heading">
      <div>
        <h1>仓内移库记录</h1>
        <p class="muted">
          确认完成的实物移动，保留移出与移入流水。新移库从库存页面选择来源。
        </p>
      </div>
      <RouterLink to="/inventory">前往库存</RouterLink>
    </header>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <label
      >仓库<select v-model="warehouseId" :disabled="busy" @change="load()">
        <option v-for="w in warehouses" :key="w.id" :value="w.id">
          {{ w.name }}
        </option>
      </select></label
    >
    <button :disabled="busy" @click="load()">刷新记录</button>
    <section class="panel">
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>移库单 / 状态</th>
              <th>物料 / 批次</th>
              <th>来源 → 目标</th>
              <th>数量</th>
              <th>原因</th>
              <th>操作人 / 时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in result?.items" :key="t.id">
              <td>TR-{{ t.id }}<small>已完成</small></td>
              <td>
                {{ t.material_name
                }}<small
                  >{{ t.material_code }} / {{ t.batch_no || '无批次' }}</small
                >
              </td>
              <td>{{ t.source_location }} → {{ t.target_location }}</td>
              <td>{{ t.quantity }} {{ t.unit }}</td>
              <td>{{ t.reason }}</td>
              <td>
                {{ t.confirmed_by }}<small>{{ t.confirmed_at }}</small>
              </td>
            </tr>
            <tr v-if="!result?.items.length">
              <td colspan="6">{{ busy ? '正在加载…' : '暂无移库记录' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="result" class="pagination">
        <span
          >共 {{ result.totalElements }} 条 · 第 {{ result.page + 1 }} /
          {{ Math.max(1, result.totalPages) }} 页</span
        ><button
          :disabled="busy || result.page === 0"
          @click="load(result.page - 1)"
        >
          上一页</button
        ><button
          :disabled="busy || result.page + 1 >= result.totalPages"
          @click="load(result.page + 1)"
        >
          下一页
        </button>
      </div>
    </section>
  </main>
</template>
<style scoped>
.table-scroll {
  overflow-x: auto;
}
small {
  display: block;
  color: #6e6a80;
}
.pagination {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 16px;
}
</style>
