<script setup lang="ts">
/**
 * 知识库 —— V3.0 智能客服（PRD-V3.0 §8.7）。
 * 文档 CRUD / 发布 / 重建索引（知识 + 商品快照）。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import ListPager from '@/components/ListPager.vue'
import {
  type CsKnowledgeDoc,
  createCsDoc,
  deleteCsDoc,
  fetchCsDoc,
  fetchCsDocs,
  rebuildCsIndex,
  rebuildProductIndex,
  updateCsDoc,
} from '@/api/customerService'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'
import { usePermission } from '@/utils/permission'

const { hasPermission } = usePermission()
const { page, total, pageSize, totalPages, canPrev, canNext, resetPage, clampEmptyPage, goPrev, goNext } =
  usePager()

const keyword = ref('')
const status = ref('')
const rows = ref<CsKnowledgeDoc[]>([])

const dialog = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = ref({ title: '', category: 'FAQ', content: '', status: 'DRAFT' })

const VECTOR_LABEL: Record<string, string> = {
  PENDING: '待向量化',
  DONE: '已向量化',
  FAILED: '向量化失败',
}

async function load() {
  const res = await fetchCsDocs({
    keyword: keyword.value || undefined,
    status: status.value || undefined,
    page: page.value,
    pageSize,
  })
  const pageData = unwrapPage<CsKnowledgeDoc>(res.data)
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
  form.value = { title: '', category: 'FAQ', content: '', status: 'DRAFT' }
  dialog.value = true
}

async function openEdit(row: CsKnowledgeDoc) {
  const res = await fetchCsDoc(row.id)
  const doc = res.data as CsKnowledgeDoc
  editingId.value = doc.id
  form.value = { title: doc.title, category: doc.category, content: doc.content || '', status: doc.status }
  dialog.value = true
}

async function save() {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('标题与内容必填')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateCsDoc(editingId.value, { ...form.value })
    } else {
      await createCsDoc({ ...form.value })
    }
    ElMessage.success('已保存')
    dialog.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function onDelete(row: CsKnowledgeDoc) {
  await ElMessageBox.confirm(`确认删除文档《${row.title}》？`, '删除')
  await deleteCsDoc(row.id)
  ElMessage.success('已删除')
  await load()
}

async function onRebuild(row?: CsKnowledgeDoc) {
  const res = await rebuildCsIndex(row ? { docId: row.id } : { full: true })
  const r = res.data as { processed: number; failed: number; vectorized: number; embeddingReady: boolean }
  ElMessage.success(
    `重建完成：处理 ${r.processed} 篇，失败 ${r.failed}，向量化 ${r.vectorized}${r.embeddingReady ? '' : '（未配置 Embedding，仅词法切片）'}`,
  )
  await load()
}

async function onRebuildProducts() {
  const res = await rebuildProductIndex()
  const r = res.data as { onSale: number; indexed: number; embeddingReady: boolean }
  ElMessage.success(`商品索引重建完成：在售 ${r.onSale}，本次处理 ${r.indexed}${r.embeddingReady ? '' : '（词法切片）'}`)
}

onMounted(load)
</script>

<template>
  <div>
    <PageHead title="知识库" sub="FAQ / 政策维护 · 发布后参与智能客服检索">
      <template #actions>
        <el-button v-if="hasPermission('cs:kb:rebuild')" @click="onRebuildProducts()">重建商品索引</el-button>
        <el-button v-if="hasPermission('cs:kb:rebuild')" type="warning" @click="onRebuild()">全量重建索引</el-button>
        <el-button v-if="hasPermission('cs:kb:edit')" type="primary" @click="openCreate">新建文档</el-button>
      </template>
    </PageHead>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="按标题搜索"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      />
      <el-select v-model="status" placeholder="状态" clearable style="width: 140px" @change="search">
        <el-option label="草稿" value="DRAFT" />
        <el-option label="发布" value="PUBLISHED" />
      </el-select>
      <el-button @click="search">查询</el-button>
    </div>

    <el-table :data="rows" border stripe>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="分类" width="110">
        <template #default="{ row }">
          {{ { AFTER_SALE: '售后政策', FAQ: 'FAQ', ACTIVITY: '活动规则', QA: '商品问答' }[row.category as string] || row.category }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'">
            {{ row.status === 'PUBLISHED' ? '发布' : '草稿' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="向量化" width="110">
        <template #default="{ row }">
          <el-tag :type="row.vectorStatus === 'DONE' ? 'success' : row.vectorStatus === 'FAILED' ? 'danger' : 'warning'" effect="plain">
            {{ VECTOR_LABEL[row.vectorStatus] || row.vectorStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切片数" width="80" />
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button v-if="hasPermission('cs:kb:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="hasPermission('cs:kb:rebuild')" link type="warning" @click="onRebuild(row)">重建</el-button>
          <el-button v-if="hasPermission('cs:kb:edit')" link type="danger" @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="dialog" :title="editingId ? '编辑知识文档' : '新建知识文档'" width="640px">
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option label="FAQ" value="FAQ" />
            <el-option label="售后政策" value="AFTER_SALE" />
            <el-option label="活动规则" value="ACTIVITY" />
            <el-option label="商品问答" value="QA" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="10" maxlength="20000" show-word-limit />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="DRAFT">草稿</el-radio>
            <el-radio value="PUBLISHED">发布</el-radio>
          </el-radio-group>
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
  display: flex;
  gap: 8px;
}
</style>
