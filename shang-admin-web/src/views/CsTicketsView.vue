<script setup lang="ts">
/** 客服工单 —— V3.0 智能客服（PRD-V3.0 §8.9）：列表 / 新建 / 编辑 / 状态流转。 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import ListPager from '@/components/ListPager.vue'
import {
  type CsTicket,
  createCsTicket,
  deleteCsTicket,
  fetchCsTickets,
  transitionCsTicket,
  updateCsTicket,
} from '@/api/customerService'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
import { usePermission } from '@/utils/permission'

const { hasPermission } = usePermission()
const { page, total, pageSize, totalPages, canPrev, canNext, resetPage, clampEmptyPage, goPrev, goNext } =
  usePager()

const TYPE_LABEL: Record<string, string> = {
  consult: '咨询',
  complaint: '投诉',
  refund: '退款协助',
  other: '其他',
}
const STATUS_LABEL: Record<string, string> = {
  OPEN: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
}

const status = ref('')
const rows = ref<CsTicket[]>([])
const dialog = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = ref({ customerId: '', type: 'consult', title: '', remark: '' })

async function load() {
  const res = await fetchCsTickets({ status: status.value || undefined, page: page.value, pageSize })
  const pageData = unwrapPage<CsTicket>(res.data)
  if (pageData) {
    if (clampEmptyPage(pageData.records.length)) {
      await load()
      return
    }
    rows.value = pageData.records
    total.value = pageData.total
  }
}

function search() {
  resetPage()
  return load()
}

function openCreate() {
  editingId.value = null
  form.value = { customerId: '', type: 'consult', title: '', remark: '' }
  dialog.value = true
}

function openEdit(row: CsTicket) {
  editingId.value = row.id
  form.value = { customerId: row.customerId, type: row.type, title: row.title, remark: row.remark || '' }
  dialog.value = true
}

async function save() {
  if (!form.value.title.trim() || !form.value.customerId.trim()) {
    ElMessage.warning('会员 ID 与标题必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateCsTicket(editingId.value, { type: form.value.type, title: form.value.title, remark: form.value.remark })
    } else {
      await createCsTicket({
        customerId: form.value.customerId,
        type: form.value.type,
        title: form.value.title,
        remark: form.value.remark,
      })
    }
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onTransition(row: CsTicket, target: string) {
  await transitionCsTicket(row.id, target)
  ElMessage.success('状态已更新')
  await load()
}

async function onDelete(row: CsTicket) {
  await ElMessageBox.confirm(`确认删除工单「${row.title}」？`, '删除')
  await deleteCsTicket(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <PageHead title="客服工单" sub="待办跟进：待处理 → 处理中 → 已解决">
      <template #actions>
        <el-button v-if="hasPermission('cs:ticket:handle')" type="primary" @click="openCreate">新建工单</el-button>
      </template>
    </PageHead>

    <div class="toolbar">
      <el-radio-group v-model="status" @change="search">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="OPEN">待处理</el-radio-button>
        <el-radio-button value="PROCESSING">处理中</el-radio-button>
        <el-radio-button value="RESOLVED">已解决</el-radio-button>
      </el-radio-group>
    </div>

    <el-table :data="rows" border stripe>
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ TYPE_LABEL[row.type] || row.type }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'RESOLVED' ? 'success' : row.status === 'OPEN' ? 'warning' : 'primary'">
            {{ STATUS_LABEL[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="customerId" label="会员 ID" width="160" />
      <el-table-column prop="sessionId" label="关联会话" width="160" />
      <el-table-column label="SLA" width="170">
        <template #default="{ row }">
          <span :class="{ overdue: row.status !== 'RESOLVED' && row.slaDueAt && new Date(row.slaDueAt) < new Date() }">
            {{ formatDateTime(row.slaDueAt) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'OPEN' && hasPermission('cs:ticket:handle')"
            link
            type="primary"
            @click="onTransition(row, 'PROCESSING')"
          >
            开始处理
          </el-button>
          <el-button
            v-if="row.status === 'PROCESSING' && hasPermission('cs:ticket:handle')"
            link
            type="success"
            @click="onTransition(row, 'RESOLVED')"
          >
            标记解决
          </el-button>
          <el-button
            v-if="row.status === 'RESOLVED' && hasPermission('cs:ticket:handle')"
            link
            type="warning"
            @click="onTransition(row, 'PROCESSING')"
          >
            退回
          </el-button>
          <el-button v-if="hasPermission('cs:ticket:handle')" link @click="openEdit(row)">编辑</el-button>
          <el-button v-if="hasPermission('cs:ticket:handle')" link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <ListPager
      :total="total"
      :page="page"
      :page-size="pageSize"
      :total-pages="totalPages"
      :can-prev="canPrev"
      :can-next="canNext"
      @prev="goPrev(load)"
      @next="goNext(load)"
    />

    <el-dialog v-model="dialog" :title="editingId ? '编辑工单' : '新建工单'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="会员 ID" :required="!editingId">
          <el-input v-model="form.customerId" :disabled="!!editingId" placeholder="顾客 ID" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="咨询" value="consult" />
            <el-option label="投诉" value="complaint" />
            <el-option label="退款协助" value="refund" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 12px;
}

.overdue {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
