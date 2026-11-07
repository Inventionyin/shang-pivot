<script setup lang="ts">
/**
 * Agent 运行轨迹 —— V3.1 Agent Runtime（ADR-0007）。
 * 运行列表按状态 / Agent / 意图筛选；详情抽屉展示步骤、LLM、工具与检索埋点。
 */
import { computed, onMounted, ref } from 'vue'
import PageHead from '@/components/PageHead.vue'
import ListPager from '@/components/ListPager.vue'
import {
  type CsAgentRun,
  type CsAgentRunDetail,
  fetchAgentRunDetail,
  fetchAgentRuns,
} from '@/api/customerService'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

const { page, total, pageSize, totalPages, canPrev, canNext, resetPage, clampEmptyPage, goPrev, goNext } =
  usePager()

const STATUS_LABEL: Record<string, string> = {
  RUNNING: '运行中',
  OK: '成功',
  NEED_USER_INPUT: '待补充',
  NEED_CONFIRMATION: '待确认',
  HANDOFF: '转人工',
  FAILED: '失败',
}

const STATUS_OPTIONS = [
  { value: 'RUNNING', label: 'RUNNING · 运行中' },
  { value: 'OK', label: 'OK · 成功' },
  { value: 'NEED_CONFIRMATION', label: 'NEED_CONFIRMATION · 待确认' },
  { value: 'HANDOFF', label: 'HANDOFF · 转人工' },
  { value: 'FAILED', label: 'FAILED · 失败' },
]

const AGENT_OPTIONS = ['SHOPPING_GUIDE', 'ORDER', 'AFTER_SALE', 'KNOWLEDGE', 'HANDOFF']

const status = ref('')
const agent = ref('')
const intent = ref('')
const rows = ref<CsAgentRun[]>([])
const loading = ref(false)

const drawer = ref(false)
const detailLoading = ref(false)
const detail = ref<CsAgentRunDetail | null>(null)
const activeTab = ref('steps')

const run = computed(() => detail.value?.run)
const drawerTitle = computed(() => {
  const traceId = detail.value?.run?.traceId
  const id = detail.value?.run?.id
  return `运行轨迹 ${traceId || id || ''}`.trim()
})

async function load() {
  loading.value = true
  try {
    const res = await fetchAgentRuns({
      page: page.value,
      size: pageSize,
      status: status.value || undefined,
      agent: (agent.value || '').trim() || undefined,
      intent: (intent.value || '').trim() || undefined,
    })
    const pageData = unwrapPage<CsAgentRun>(res.data)
    if (pageData) {
      if (clampEmptyPage(pageData.records.length)) {
        await load()
        return
      }
      rows.value = pageData.records
      total.value = pageData.total
    }
  } finally {
    loading.value = false
  }
}

function search() {
  resetPage()
  return load()
}

function resetFilters() {
  status.value = ''
  agent.value = ''
  intent.value = ''
  return search()
}

function statusLabel(value?: string | null): string {
  return STATUS_LABEL[value || ''] || value || '—'
}

function statusTagType(value?: string | null): TagType {
  switch (value) {
    case 'OK':
      return 'success'
    case 'RUNNING':
    case 'NEED_CONFIRMATION':
    case 'NEED_USER_INPUT':
      return 'warning'
    case 'HANDOFF':
      return 'primary'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

function msText(value?: number | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? `${value} ms` : '—'
}

/** 列表/详情优先使用后端聚合的 latencyMs，缺失时用起止时间计算。 */
function latencyText(row?: CsAgentRun | null): string {
  if (!row) return '—'
  if (typeof row.latencyMs === 'number' && Number.isFinite(row.latencyMs)) {
    return `${row.latencyMs} ms`
  }
  if (row.startedAt && row.finishedAt) {
    const started = new Date(row.startedAt).getTime()
    const finished = new Date(row.finishedAt).getTime()
    if (Number.isFinite(started) && Number.isFinite(finished) && finished >= started) {
      return `${finished - started} ms`
    }
  }
  return '—'
}

/** 文本插值自动转义；对象/JSON 字符串统一 prettify，解析失败按原文展示。 */
function formatJson(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  let parsed: unknown = value
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return '—'
    try {
      parsed = JSON.parse(text)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(parsed, null, 2) ?? String(value)
  } catch {
    return String(value)
  }
}

let detailSeq = 0

async function openDetail(row: CsAgentRun) {
  const seq = ++detailSeq
  drawer.value = true
  activeTab.value = 'steps'
  detailLoading.value = true
  detail.value = null
  try {
    const res = await fetchAgentRunDetail(row.id)
    if (seq === detailSeq) detail.value = res.data as CsAgentRunDetail
  } finally {
    if (seq === detailSeq) detailLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHead title="Agent 运行轨迹" sub="V3.1 子 Agent 运行记录：步骤 / LLM / 工具 / 检索" />

    <div class="toolbar">
      <el-select v-model="status" class="select" style="width: 220px" placeholder="全部状态">
        <el-option label="全部状态" value="" />
        <el-option v-for="opt in STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-select
        v-model="agent"
        class="select"
        style="width: 190px"
        placeholder="全部 Agent"
        filterable
        allow-create
        default-first-option
        clearable
      >
        <el-option v-for="name in AGENT_OPTIONS" :key="name" :label="name" :value="name" />
      </el-select>
      <el-input v-model="intent" class="input" placeholder="按意图筛选，如 order_query" clearable />
      <div class="toolbar-actions">
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      stripe
      row-class-name="clickable-row"
      @row-click="openDetail"
    >
      <el-table-column label="Trace ID" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.traceId || '—' }}</template>
      </el-table-column>
      <el-table-column label="会话 ID" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sessionId ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="会员 ID" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.customerId ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="意图" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.intent || '—' }}</template>
      </el-table-column>
      <el-table-column label="Agent" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.agent || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="开始时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ latencyText(row) }}</template>
      </el-table-column>
      <el-table-column label="Token" width="100">
        <template #default="{ row }">{{ row.totalTokens ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="错误" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ 'error-text': row.error }">{{ row.error || '—' }}</span>
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

    <el-drawer v-model="drawer" size="820px" :title="drawerTitle">
      <div v-loading="detailLoading" class="detail">
        <template v-if="run">
          <div class="summary-grid">
            <div class="summary-item">
              <span class="k">状态</span>
              <span class="v">
                <el-tag :type="statusTagType(run.status)" size="small">{{ statusLabel(run.status) }}</el-tag>
              </span>
            </div>
            <div class="summary-item">
              <span class="k">Agent</span>
              <span class="v">{{ run.agent || '—' }}</span>
            </div>
            <div class="summary-item">
              <span class="k">意图</span>
              <span class="v">{{ run.intent || '—' }}</span>
            </div>
            <div class="summary-item">
              <span class="k">总 Token</span>
              <span class="v">{{ run.totalTokens ?? '—' }}</span>
            </div>
            <div class="summary-item">
              <span class="k">耗时</span>
              <span class="v">{{ latencyText(run) }}</span>
            </div>
            <div class="summary-item">
              <span class="k">开始时间</span>
              <span class="v">{{ formatDateTime(run.startedAt) }}</span>
            </div>
            <div class="summary-item">
              <span class="k">结束时间</span>
              <span class="v">{{ formatDateTime(run.finishedAt) }}</span>
            </div>
            <div class="summary-item">
              <span class="k">会话 / 会员</span>
              <span class="v">{{ run.sessionId ?? '—' }} / {{ run.customerId ?? '—' }}</span>
            </div>
          </div>

          <el-alert
            v-if="run.error"
            :title="run.error"
            type="error"
            :closable="false"
            show-icon
            class="run-error"
          />

          <el-tabs v-model="activeTab">
            <el-tab-pane :label="`步骤 (${detail?.steps?.length || 0})`" name="steps">
              <el-table :data="detail?.steps || []" border size="small">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="节点" min-width="130" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.node || '—' }}</template>
                </el-table-column>
                <el-table-column label="Agent" width="130" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.agent || '—' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row }">{{ msText(row.latencyMs) }}</template>
                </el-table-column>
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="输入 / 输出" min-width="260">
                  <template #default="{ row }">
                    <div class="json-label">输入</div>
                    <pre class="json">{{ formatJson(row.inputSummary) }}</pre>
                    <div class="json-label">输出</div>
                    <pre class="json">{{ formatJson(row.outputSummary) }}</pre>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane :label="`LLM (${detail?.llmCalls?.length || 0})`" name="llm">
              <el-table :data="detail?.llmCalls || []" border size="small">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="模型" min-width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.model || '—' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="Token 入/出" width="120">
                  <template #default="{ row }">{{ row.inputTokens ?? 0 }} / {{ row.outputTokens ?? 0 }}</template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row }">{{ msText(row.latencyMs) }}</template>
                </el-table-column>
                <el-table-column label="结束原因" width="110">
                  <template #default="{ row }">{{ row.finishReason || '—' }}</template>
                </el-table-column>
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="请求 / 响应" min-width="260">
                  <template #default="{ row }">
                    <div class="json-label">请求</div>
                    <pre class="json">{{ formatJson(row.requestRef) }}</pre>
                    <div class="json-label">响应</div>
                    <pre class="json">{{ formatJson(row.responseRef) }}</pre>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane :label="`工具 (${detail?.toolCalls?.length || 0})`" name="tools">
              <el-table :data="detail?.toolCalls || []" border size="small">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="工具" min-width="150" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.toolName || '—' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row }">{{ msText(row.latencyMs) }}</template>
                </el-table-column>
                <el-table-column label="重试" width="70">
                  <template #default="{ row }">{{ row.retryCount ?? 0 }}</template>
                </el-table-column>
                <el-table-column label="错误码" width="120" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.errorCode || '—' }}</template>
                </el-table-column>
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="参数 / 结果" min-width="260">
                  <template #default="{ row }">
                    <div class="json-label">参数</div>
                    <pre class="json">{{ formatJson(row.argsJson) }}</pre>
                    <div class="json-label">结果</div>
                    <pre class="json">{{ formatJson(row.resultJson) }}</pre>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane :label="`检索 (${detail?.retrievals?.length || 0})`" name="retrievals">
              <el-table :data="detail?.retrievals || []" border size="small">
                <el-table-column prop="id" label="ID" width="70" />
                <el-table-column label="查询词" min-width="160" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.query || '—' }}</template>
                </el-table-column>
                <el-table-column label="后端" width="90">
                  <template #default="{ row }">{{ row.backend || '—' }}</template>
                </el-table-column>
                <el-table-column label="TopK" width="70">
                  <template #default="{ row }">{{ row.topK ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="命中" width="80">
                  <template #default="{ row }">
                    <el-tag :type="row.hit ? 'success' : 'info'" size="small">
                      {{ row.hit ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="90">
                  <template #default="{ row }">{{ msText(row.latencyMs) }}</template>
                </el-table-column>
                <el-table-column label="时间" width="150">
                  <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
                </el-table-column>
                <el-table-column label="文档 ID / 分数" min-width="240">
                  <template #default="{ row }">
                    <div class="json-label">文档 ID</div>
                    <pre class="json">{{ formatJson(row.docIds) }}</pre>
                    <div class="json-label">分数</div>
                    <pre class="json">{{ formatJson(row.scores) }}</pre>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else-if="!detailLoading" description="暂无运行详情" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
:deep(.clickable-row) {
  cursor: pointer;
}

.error-text {
  color: var(--el-color-danger);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 200px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
}

.summary-item .k {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.summary-item .v {
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}

.run-error {
  margin-top: 0;
}

.json-label {
  margin-top: 6px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.json {
  margin: 2px 0 0;
  padding: 6px 8px;
  max-height: 180px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
