<script setup lang="ts">
/** 坐席管理 —— V3.0 智能客服（PRD-V3.0 §8.11）：在线状态与最大并发维护。 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import { type CsSeat, fetchSeats, updateSeatConcurrency, updateSeatStatus } from '@/api/customerService'
import { usePermission } from '@/utils/permission'

const { hasPermission } = usePermission()

const rows = ref<CsSeat[]>([])
const loading = ref(false)
let pollTimer: number | null = null

async function load() {
  loading.value = true
  try {
    const res = await fetchSeats()
    rows.value = (res.data as CsSeat[]) || []
  } finally {
    loading.value = false
  }
}

async function onStatusChange(row: CsSeat, status: string) {
  await updateSeatStatus(row.id, status)
  ElMessage.success(`坐席「${row.nickname}」状态已更新`)
  await load()
}

async function onConcurrencyChange(row: CsSeat, maxConcurrent: number) {
  await updateSeatConcurrency(row.id, maxConcurrent)
  ElMessage.success('最大并发已更新')
  await load()
}

async function confirmConcurrency(row: CsSeat) {
  const { value } = await ElMessageBox.prompt('设置最大并发会话数（1~20）', '最大并发', {
    inputValue: String(row.maxConcurrent),
    inputPattern: /^\d+$/,
    inputErrorMessage: '请输入数字',
  })
  const n = Number(value)
  if (n >= 1 && n <= 20) {
    await onConcurrencyChange(row, n)
  } else {
    ElMessage.warning('需在 1~20 之间')
  }
}

onMounted(() => {
  void load()
  pollTimer = window.setInterval(() => void load(), 10000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<template>
  <div>
    <PageHead title="坐席管理" sub="人工客服账号的在线状态与并发上限" />

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="nickname" label="坐席" width="180" />
      <el-table-column prop="adminUserId" label="管理端用户 ID" width="160" />
      <el-table-column label="在线状态" width="220">
        <template #default="{ row }">
          <el-radio-group
            v-if="hasPermission('cs:seat:manage')"
            :model-value="row.status"
            size="small"
            @change="(v: string | number | boolean | undefined) => onStatusChange(row, String(v))"
          >
            <el-radio-button value="ONLINE">在线</el-radio-button>
            <el-radio-button value="BUSY">忙碌</el-radio-button>
            <el-radio-button value="OFFLINE">离线</el-radio-button>
          </el-radio-group>
          <el-tag v-else :type="row.status === 'ONLINE' ? 'success' : row.status === 'BUSY' ? 'warning' : 'info'">
            {{ { ONLINE: '在线', BUSY: '忙碌', OFFLINE: '离线' }[row.status as string] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="当前/最大并发" width="160">
        <template #default="{ row }">{{ row.ongoingCount ?? 0 }} / {{ row.maxConcurrent }}</template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button v-if="hasPermission('cs:seat:manage')" link type="primary" @click="confirmConcurrency(row)">
            设置最大并发
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
