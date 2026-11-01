<script setup lang="ts">
/**
 * 服务看板 —— V3.0 智能客服（PRD-V3.0 §8.12 + §8.8）。
 * 指标卡 + AI 配置（读写 system_config LLM 分组，密文打码；连通性测试）。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import { fetchAiConfig, fetchCsStats, testAiConfig, updateAiConfig } from '@/api/customerService'

interface AiConfigRow {
  configKey: string
  configValue: string
  isSecret: boolean
  remark?: string
}

const range = ref<'today' | 'week'>('today')
const stats = ref<Record<string, number | string>>({})
const loading = ref(false)

const configRows = ref<AiConfigRow[]>([])
const chatReady = ref(false)
const embeddingReady = ref(false)
const configLoading = ref(false)
const savingConfig = ref(false)
const testing = ref(false)
const edited = ref<Record<string, string>>({})

const KEY_LABEL: Record<string, string> = {
  'llm.base-url': 'LLM 网关 Base URL',
  'llm.api-key': 'LLM API Key',
  'llm.model': '对话模型',
  'llm.temperature': 'Temperature（0~1）',
  'llm.system-prompt': '系统提示词',
  'llm.embedding.base-url': 'Embedding Base URL',
  'llm.embedding.api-key': 'Embedding API Key',
  'llm.embedding.model': 'Embedding 模型',
  'llm.embedding.dim': '向量维度',
  'retrieval.mode': '检索模式',
}

const EDITABLE = [
  'llm.base-url',
  'llm.api-key',
  'llm.model',
  'llm.temperature',
  'llm.system-prompt',
  'llm.embedding.base-url',
  'llm.embedding.api-key',
  'llm.embedding.model',
  'llm.embedding.dim',
  'retrieval.mode',
]

async function loadStats() {
  loading.value = true
  try {
    const res = await fetchCsStats(range.value)
    stats.value = (res.data as Record<string, number | string>) || {}
  } finally {
    loading.value = false
  }
}

async function loadConfig() {
  configLoading.value = true
  try {
    const res = await fetchAiConfig()
    const data = res.data as {
      items: AiConfigRow[]
      chatReady: boolean
      embeddingReady: boolean
      retrievalMode: string
    }
    configRows.value = (data.items || []).filter((r) => EDITABLE.includes(r.configKey))
    chatReady.value = !!data.chatReady
    embeddingReady.value = !!data.embeddingReady
  } finally {
    configLoading.value = false
  }
}

function displayValue(row: AiConfigRow): string {
  return edited.value[row.configKey] ?? row.configValue ?? ''
}

function onEdit(row: AiConfigRow, value: string) {
  edited.value[row.configKey] = value
}

async function saveConfig() {
  if (!Object.keys(edited.value).length) {
    ElMessage.info('没有修改')
    return
  }
  savingConfig.value = true
  try {
    await updateAiConfig(edited.value)
    ElMessage.success('AI 配置已保存')
    edited.value = {}
    await loadConfig()
  } finally {
    savingConfig.value = false
  }
}

async function onTest() {
  testing.value = true
  try {
    const res = await testAiConfig()
    const r = res.data as { ok: boolean; modelCount?: number; containsCurrentModel?: boolean; error?: string }
    if (r.ok) {
      ElMessage.success(`连通正常：模型 ${r.modelCount} 个${r.containsCurrentModel ? '，包含当前模型' : '，未包含当前模型!'}`)
    } else {
      ElMessage.error(`连通失败：${r.error || '未知错误'}`)
    }
  } finally {
    testing.value = false
  }
}

onMounted(() => {
  void loadStats()
  void loadConfig()
})
</script>

<template>
  <div>
    <PageHead title="服务看板" sub="会话量 / 转人工率 / 工单量 + AI 配置">
      <template #actions>
        <el-radio-group v-model="range" @change="loadStats">
          <el-radio-button value="today">今日</el-radio-button>
          <el-radio-button value="week">近 7 日</el-radio-button>
        </el-radio-group>
      </template>
    </PageHead>

    <div v-loading="loading" class="cards">
      <div class="card">
        <div class="num">{{ stats.sessionTotal ?? 0 }}</div>
        <div class="label">会话总数</div>
      </div>
      <div class="card">
        <div class="num">{{ stats.aiOnly ?? 0 }}</div>
        <div class="label">AI 独立应答</div>
      </div>
      <div class="card">
        <div class="num">{{ stats.escalated ?? 0 }}</div>
        <div class="label">转人工</div>
      </div>
      <div class="card">
        <div class="num">{{ stats.escalateRate ?? 0 }}%</div>
        <div class="label">转人工率</div>
      </div>
      <div class="card">
        <div class="num">{{ stats.avgSessionMinutes ?? 0 }} min</div>
        <div class="label">平均会话时长</div>
      </div>
      <div class="card">
        <div class="num">{{ stats.ticketOpen ?? 0 }} / {{ stats.ticketResolved ?? 0 }}</div>
        <div class="label">工单 新增/解决</div>
      </div>
    </div>

    <div class="config">
      <div class="config-head">
        <div>
          <div class="config-title">AI 配置</div>
          <div class="config-sub">
            对话模型：
            <el-tag :type="chatReady ? 'success' : 'danger'" size="small">
              {{ chatReady ? '已就绪' : '未配置（降级模式）' }}
            </el-tag>
            Embedding：
            <el-tag :type="embeddingReady ? 'success' : 'info'" size="small">
              {{ embeddingReady ? '已配置（向量检索）' : '未配置（词法检索）' }}
            </el-tag>
          </div>
        </div>
        <div style="display: flex; gap: 8px">
          <el-button :loading="testing" @click="onTest">连通性测试</el-button>
          <el-button
            v-if="configRows.some((r) => r.configKey === 'retrieval.mode')"
            type="primary"
            :loading="savingConfig"
            @click="saveConfig"
          >
            保存修改
          </el-button>
        </div>
      </div>

      <el-table v-loading="configLoading" :data="configRows" border>
        <el-table-column label="配置项" width="220">
          <template #default="{ row }">
            {{ KEY_LABEL[row.configKey] || row.configKey }}
            <div class="key">{{ row.configKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="值">
          <template #default="{ row }">
            <el-switch
              v-if="row.configKey === 'retrieval.mode'"
              :model-value="displayValue(row) === 'vector'"
              active-text="向量"
              inactive-text="auto"
              @change="(v: string | number | boolean | undefined) => onEdit(row, v ? 'vector' : 'auto')"
            />
            <el-input-number
              v-else-if="row.configKey === 'llm.temperature' || row.configKey === 'llm.embedding.dim'"
              :model-value="Number(displayValue(row) || 0)"
              :step="row.configKey === 'llm.temperature' ? 0.1 : 64"
              @change="(v: number | undefined) => onEdit(row, String(v ?? ''))"
            />
            <el-input
              v-else-if="row.configKey === 'llm.system-prompt'"
              :model-value="displayValue(row)"
              type="textarea"
              :rows="3"
              @input="(v: string) => onEdit(row, v)"
            />
            <el-input
              v-else
              :model-value="displayValue(row)"
              :placeholder="row.isSecret ? '密文不回显，留空或 ****** 表示不修改' : ''"
              @input="(v: string) => onEdit(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" width="240" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.card {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  padding: 16px;
}

.num {
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.config {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  padding: 16px;
}

.config-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.config-title {
  font-size: 15px;
  font-weight: 600;
}

.config-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.key {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  font-family: monospace;
}
</style>
