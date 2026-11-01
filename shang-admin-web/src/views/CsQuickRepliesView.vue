<script setup lang="ts">
/** 快捷回复 —— V3.0 智能客服（PRD-V3.0 §8.10）：分组 + 话术，勾选「推荐问」即 C 端快捷问题。 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import {
  type CsQuickReplyItem,
  createQuickReplyGroup,
  createQuickReplyItem,
  deleteQuickReplyGroup,
  deleteQuickReplyItem,
  fetchQuickReplies,
  updateQuickReplyItem,
} from '@/api/customerService'
import { usePermission } from '@/utils/permission'

const { hasPermission } = usePermission()

const groups = ref<Array<{ id: string; name: string; sort?: number }>>([])
const items = ref<CsQuickReplyItem[]>([])
const activeGroup = ref<string>('')
const loading = ref(false)

const groupDialog = ref(false)
const groupName = ref('')

const itemDialog = ref(false)
const itemSaving = ref(false)
const editingItemId = ref<string | null>(null)
const itemForm = ref({ title: '', content: '', sort: 0, isRecommended: false })

async function load() {
  loading.value = true
  try {
    const res = await fetchQuickReplies()
    const data = res.data as {
      groups: Array<{ id: string; name: string; sort?: number }>
      items: CsQuickReplyItem[]
    }
    groups.value = data.groups || []
    items.value = data.items || []
    if (!activeGroup.value && groups.value.length) {
      const first = groups.value[0]
      if (first) activeGroup.value = String(first.id)
    }
  } finally {
    loading.value = false
  }
}

const visibleItems = () => items.value.filter((i) => String(i.groupId) === String(activeGroup.value))

function openGroupDialog() {
  groupName.value = ''
  groupDialog.value = true
}

async function saveGroup() {
  if (!groupName.value.trim()) {
    ElMessage.warning('分组名必填')
    return
  }
  await createQuickReplyGroup({ name: groupName.value.trim(), sort: groups.value.length + 1 })
  ElMessage.success('分组已创建')
  groupDialog.value = false
  await load()
}

async function removeGroup(id: string) {
  await ElMessageBox.confirm('确认删除该分组？（分组下有话术时不可删除）', '删除分组')
  await deleteQuickReplyGroup(id)
  ElMessage.success('已删除')
  if (activeGroup.value === String(id)) activeGroup.value = ''
  await load()
}

function openItemCreate() {
  editingItemId.value = null
  itemForm.value = { title: '', content: '', sort: visibleItems().length + 1, isRecommended: false }
  itemDialog.value = true
}

function openItemEdit(item: CsQuickReplyItem) {
  editingItemId.value = item.id
  itemForm.value = {
    title: item.title,
    content: item.content,
    sort: item.sort ?? 0,
    isRecommended: !!item.isRecommended,
  }
  itemDialog.value = true
}

async function saveItem() {
  if (!itemForm.value.title.trim() || !itemForm.value.content.trim()) {
    ElMessage.warning('标题与内容必填')
    return
  }
  itemSaving.value = true
  try {
    const payload = { groupId: activeGroup.value, ...itemForm.value }
    if (editingItemId.value) {
      await updateQuickReplyItem(editingItemId.value, payload)
    } else {
      await createQuickReplyItem(payload)
    }
    ElMessage.success('已保存')
    itemDialog.value = false
    await load()
  } finally {
    itemSaving.value = false
  }
}

async function removeItem(item: CsQuickReplyItem) {
  await ElMessageBox.confirm(`确认删除话术「${item.title}」？`, '删除')
  await deleteQuickReplyItem(item.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <PageHead title="快捷回复" sub="坐席快捷话术与 C 端推荐问（勾选推荐问后前台快捷问题同步展示）">
      <template #actions>
        <el-button v-if="hasPermission('cs:quick:edit')" @click="openGroupDialog">新建分组</el-button>
        <el-button v-if="hasPermission('cs:quick:edit')" type="primary" :disabled="!activeGroup" @click="openItemCreate">
          新建话术
        </el-button>
      </template>
    </PageHead>

    <div v-loading="loading" class="layout">
      <div class="groups">
        <div
          v-for="g in groups"
          :key="g.id"
          class="group"
          :class="{ active: String(g.id) === String(activeGroup) }"
          @click="activeGroup = String(g.id)"
        >
          <span>{{ g.name }}</span>
          <el-button v-if="hasPermission('cs:quick:edit')" link type="danger" size="small" @click.stop="removeGroup(g.id)">
            删
          </el-button>
        </div>
      </div>

      <div class="items">
        <el-table :data="visibleItems()" border>
          <el-table-column prop="title" label="标题" width="160" />
          <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
          <el-table-column label="推荐问" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.isRecommended" type="success" effect="plain">是</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="sort" label="排序" width="70" />
          <el-table-column v-if="hasPermission('cs:quick:edit')" label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openItemEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeItem(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="groupDialog" title="新建分组" width="380px">
      <el-input v-model="groupName" maxlength="50" placeholder="分组名（≤50 字）" />
      <template #footer>
        <el-button @click="groupDialog = false">取消</el-button>
        <el-button type="primary" @click="saveGroup">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="itemDialog" :title="editingItemId ? '编辑话术' : '新建话术'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="itemForm.title" maxlength="50" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="itemForm.content" type="textarea" :rows="4" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="itemForm.sort" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="推荐问">
          <el-switch v-model="itemForm.isRecommended" />
          <span class="tip">开启后该话术出现在 C 端「AI 助手」快捷问题</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialog = false">取消</el-button>
        <el-button type="primary" :loading="itemSaving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.groups {
  width: 200px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.group {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}

.group.active {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.items {
  flex: 1;
  min-width: 0;
}

.tip {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
