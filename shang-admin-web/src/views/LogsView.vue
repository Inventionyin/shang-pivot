<script setup lang="ts">
/**
 * 操作日志 —— 对齐原型 .stitch/原型/admin/logs.html 与弹窗图鉴 dialogs.html。
 * 详情为右侧抽屉（D12-01 · PRD §8.7 规定抽屉形态），导出为确认弹窗（D12-02）。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import DetailDrawer from '@/components/DetailDrawer.vue'
import FormDialog from '@/components/FormDialog.vue'
import JsonViewer from '@/components/JsonViewer.vue'
import ListPager from '@/components/ListPager.vue'
import {
  exportOperationLogs,
  fetchOperationLog,
  fetchOperationLogs,
  formatLogModule,
  LOG_MODULE_OPTIONS,
} from '@/api/logs'
import { usePager } from '@/composables/usePager'
import { unwrapPage } from '@/utils/page'
import { formatDateTime } from '@/utils/datetime'

type LogRow = {
  id: string
  user: string
  module: string
  action: string
  params: string
  ip: string
  ok: boolean
  time: string
}

type LogDetail = LogRow & {
  moduleCode: string
  moduleText: string
  object: string
  ua: string
  result: string
  traceId: string
}

const {
  page,
  total,
  pageSize,
  totalPages,
  canPrev,
  canNext,
  resetPage,
  clampEmptyPage,
  goPrev,
  goNext,
} = usePager()

const keyword = ref('')
const moduleFilter = ref('')
const fromDate = ref('')
const toDate = ref('')
const rows = ref<LogRow[]>([])
const exporting = ref(false)
const exportDialog = ref(false)
const exportError = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref<LogDetail | null>(null)

const exportModuleLabel = computed(
  () => LOG_MODULE_OPTIONS.find((o) => o.value === moduleFilter.value)?.label ?? '全部模块',
)
const exportKeyword = computed(() => keyword.value.trim() || '空')
const exportRange = computed(() => `${fromDate.value || '不限'} ~ ${toDate.value || '不限'}`)

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return e instanceof Error ? e.message : ''
}

function mapOk(r: Record<string, unknown>): boolean {
  const result = String(r.result ?? '').toUpperCase()
  if (result === 'FAIL' || result === 'FAILED' || result === 'ERROR') return false
  return r.success !== false && r.status !== 'FAIL' && r.ok !== false
}

function mapRow(r: Record<string, unknown>): LogRow {
  const moduleCode = String(r.module ?? r.bizType ?? '')
  return {
    id: String(r.id ?? ''),
    user: String(r.username ?? r.userName ?? r.operator ?? '—'),
    module: formatLogModule(moduleCode),
    action: String(r.action ?? r.operation ?? '—'),
    params: String(r.params ?? r.requestParams ?? '') || '',
    ip: String(r.ip ?? r.clientIp ?? '—'),
    ok: mapOk(r),
    time: formatDateTime(r.createdAt ?? r.time, true),
  }
}

/** 参数可能是 JSON，也可能是普通文本；非对象一律返回 null */
function parseParams(raw: string): Record<string, unknown> | null {
  const text = raw.trim()
  if (!text) return null
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
  } catch {
    /* 非 JSON：按原始文本展示 */
  }
  return null
}

/** 「对象」：后端未单列字段时，回落取参数里的第一个标识键 */
function pickLogObject(r: Record<string, unknown>, params: string): string {
  for (const key of ['target', 'bizId', 'object', 'objectName']) {
    const v = r[key]
    if (v != null && String(v)) return String(v)
  }
  const parsed = parseParams(params)
  if (parsed) {
    for (const key of ['id', 'userId', 'code', 'username', 'orderNo', 'productId', 'skuId']) {
      const v = parsed[key]
      if (v != null && typeof v !== 'object' && String(v)) return String(v)
    }
  }
  return '—'
}

function formatParamValue(v: unknown): string {
  if (v == null || v === '') return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

function mapDetail(r: Record<string, unknown>): LogDetail {
  const row = mapRow(r)
  const moduleCode = String(r.module ?? r.bizType ?? '')
  return {
    ...row,
    moduleCode,
    moduleText: moduleCode && row.module !== moduleCode ? `${row.module}（${moduleCode}）` : row.module,
    object: pickLogObject(r, row.params),
    ua: String(r.ua ?? r.userAgent ?? '') || '',
    result: String(r.result ?? (row.ok ? 'SUCCESS' : 'FAIL')),
    traceId: String(r.traceId ?? '') || '',
  }
}

/** 参数摘要：JSON 逐键展开，非 JSON 时按原文一行展示 */
const paramRows = computed(() => {
  const raw = detail.value?.params ?? ''
  const parsed = parseParams(raw)
  if (parsed) {
    return Object.entries(parsed)
      .slice(0, 12)
      .map(([key, value]) => ({ key, text: formatParamValue(value) }))
  }
  return raw.trim() ? [{ key: '参数', text: raw }] : []
})

function currentQuery() {
  return {
    keyword: keyword.value || undefined,
    module: moduleFilter.value || undefined,
    from: fromDate.value || undefined,
    to: toDate.value || undefined,
    page: page.value,
    pageSize,
  }
}

async function load() {
  try {
    const res = await fetchOperationLogs(currentQuery())
    const pageData = unwrapPage<Record<string, unknown>>(res.data)
    if (!pageData) {
      rows.value = []
      total.value = 0
      return
    }
    total.value = pageData.total
    if (clampEmptyPage(pageData.records.length)) {
      await load()
      return
    }
    rows.value = pageData.records.map(mapRow)
  } catch {
    rows.value = []
    total.value = 0
  }
}

function search() {
  resetPage()
  return load()
}

function resetFilters() {
  keyword.value = ''
  moduleFilter.value = ''
  fromDate.value = ''
  toDate.value = ''
  search()
}

/* ===== 导出（dlg-log-export · D12-02）===== */
function openExport() {
  exportError.value = ''
  exportDialog.value = true
}

async function confirmExport() {
  if (exporting.value) return
  exporting.value = true
  exportError.value = ''
  try {
    await exportOperationLogs({
      keyword: keyword.value || undefined,
      module: moduleFilter.value || undefined,
      from: fromDate.value || undefined,
      to: toDate.value || undefined,
    })
    ElMessage.success('导出成功')
    exportDialog.value = false
  } catch (e: unknown) {
    // 弹窗不关：后端 msg 内联显示，同时保留 ElMessage 反馈
    exportError.value = readErrMsg(e) || '导出失败'
    ElMessage.error(exportError.value)
  } finally {
    exporting.value = false
  }
}

/** 抽屉底部的「导出当前筛选」：先收起抽屉再弹确认 */
function exportFromDetail() {
  detailOpen.value = false
  openExport()
}

/* ===== 详情抽屉（drw-log · D12-01）===== */
async function openDetail(row: LogRow) {
  if (!row.id) return
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await fetchOperationLog(row.id)
    const data = res.data
    if (data && typeof data === 'object') {
      detail.value = mapDetail(data as Record<string, unknown>)
    }
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

function shortParams(params: string) {
  if (!params) return '—'
  return params.length > 48 ? `${params.slice(0, 48)}…` : params
}

onMounted(load)
</script>

<template>
  <div class="list-page">
    <PageHead title="操作日志" sub="关键操作审计 · 异步落库 · 按筛选结果导出 CSV">
      <template #actions>
        <button
          class="btn btn-secondary btn-sm"
          type="button"
          :disabled="exporting"
          @click="openExport"
        >
          {{ exporting ? '导出中…' : '导出 CSV' }}
        </button>
      </template>
    </PageHead>

    <div class="card card-pad list-card">
      <div class="toolbar">
        <input
          v-model="keyword"
          class="input"
          type="text"
          placeholder="用户 / 操作内容"
          @keyup.enter="search"
        />
        <select v-model="moduleFilter" class="select">
          <option value="">全部模块</option>
          <option v-for="m in LOG_MODULE_OPTIONS" :key="m.value" :value="m.value">
            {{ m.label }}
          </option>
        </select>
        <input v-model="fromDate" class="input" type="date" title="开始日期" />
        <input v-model="toDate" class="input" type="date" title="结束日期" />
        <div class="toolbar-actions">
          <button class="btn btn-secondary btn-sm" type="button" @click="resetFilters">重置</button>
          <button class="btn btn-primary btn-sm" type="button" @click="search">查询</button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>用户</th>
              <th>模块</th>
              <th>操作</th>
              <th class="code">参数</th>
              <th class="code">IP</th>
              <th>结果</th>
              <th class="num center">时间</th>
              <th class="col-op">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!rows.length">
              <td colspan="8" class="hint empty-cell">暂无日志</td>
            </tr>
            <tr v-for="row in rows" :key="row.id || row.time + row.action">
              <td>{{ row.user }}</td>
              <td>{{ row.module }}</td>
              <td>{{ row.action }}</td>
              <td class="code params-cell" :title="row.params || undefined">
                {{ shortParams(row.params) }}
              </td>
              <td class="code">{{ row.ip }}</td>
              <td>
                <StatusBadge :text="row.ok ? '成功' : '失败'" :tone="row.ok ? 'forest' : 'brick'" />
              </td>
              <td class="num center">{{ row.time }}</td>
              <td class="col-op">
                <button class="btn btn-ghost btn-sm" type="button" @click="openDetail(row)">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <ListPager
        :total="total"
        :page="page"
        :total-pages="totalPages"
        :page-size="pageSize"
        :can-prev="canPrev"
        :can-next="canNext"
        @prev="goPrev(load)"
        @next="goNext(load)"
      />
    </div>
  </div>

  <!-- 日志详情抽屉（drw-log · D12-01） -->
  <DetailDrawer
    v-model="detailOpen"
    title="操作日志详情"
    :badge="detail ? (detail.ok ? '成功' : '失败') : ''"
    :badge-tone="detail && !detail.ok ? 'brick' : 'forest'"
  >
    <p v-if="detailLoading" class="hint">加载中…</p>
    <p v-else-if="!detail" class="hint">未找到该日志</p>
    <template v-else>
      <dl class="kv log-kv">
        <dt>操作人</dt>
        <dd>{{ detail.user }}</dd>
        <dt>时间</dt>
        <dd class="code">{{ detail.time }}</dd>
        <dt>模块</dt>
        <dd>{{ detail.moduleText }}</dd>
        <dt>动作</dt>
        <dd>{{ detail.action }}</dd>
        <dt>对象</dt>
        <dd class="code">{{ detail.object }}</dd>
        <dt>结果</dt>
        <dd>
          <StatusBadge
            :text="detail.ok ? '成功' : '失败'"
            :tone="detail.ok ? 'forest' : 'brick'"
          />
        </dd>
        <dt>来源 IP</dt>
        <dd class="code">{{ detail.ip }}</dd>
        <dt>TraceId</dt>
        <dd class="code">{{ detail.traceId || '—' }}</dd>
      </dl>

      <div class="page-title log-section">参数摘要</div>
      <div class="card card-pad params-card">
        <dl v-if="paramRows.length" class="kv log-kv params-kv">
          <template v-for="p in paramRows" :key="p.key">
            <dt>{{ p.key }}</dt>
            <dd class="code">{{ p.text }}</dd>
          </template>
        </dl>
        <p v-else class="hint">暂无参数</p>
        <p class="hint params-note">敏感字段（密码、密钥等）落库前已脱敏，仅保留字段名。</p>
      </div>

      <details class="log-json">
        <summary class="hint log-json-summary">原始参数（JSON，点击展开）</summary>
        <JsonViewer :value="detail.params" title="JSON" empty-text="无参数" />
      </details>

      <div class="alert alert-info log-note">
        日志为<b>异步落库</b>的审计快照，写入失败不影响业务操作；日志仅可查询与导出，<b>不提供编辑 / 删除</b>。
      </div>
    </template>

    <template #footer="{ close }">
      <button class="btn btn-secondary" type="button" @click="close">关闭</button>
      <button class="btn btn-primary" type="button" @click="exportFromDetail">
        导出当前筛选
      </button>
    </template>
  </DetailDrawer>

  <!-- 导出 CSV 确认（dlg-log-export · D12-02） -->
  <FormDialog
    v-model="exportDialog"
    title="导出操作日志"
    icon="info"
    icon-text="↓"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认导出"
    :loading="exporting"
    @submit="confirmExport"
  >
    <p v-if="exportError" class="error-text">{{ exportError }}</p>
    <p>将按<b>当前筛选条件</b>导出日志：</p>
    <ul class="impact">
      <li>时间范围 <b>{{ exportRange }}</b></li>
      <li>模块 <b>{{ exportModuleLabel }}</b> · 关键字 <b>{{ exportKeyword }}</b></li>
      <li>预计条数 <b>{{ total }}</b> 条（翻页不影响导出范围）</li>
    </ul>
    <div class="alert alert-info export-note">
      CSV 为 UTF-8（带 BOM），可直接用 Excel 打开；导出内容包含参数摘要，敏感字段已脱敏。导出动作本身也会写入一条操作日志。
    </div>
  </FormDialog>
</template>

<style scoped>
/* 原型 logs.html 内联样式的类化版本（不写行内 style） */
.params-cell {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.empty-cell {
  text-align: center;
  padding: 24px;
}
.log-kv {
  grid-template-columns: 96px 1fr;
  margin-bottom: 18px;
}
.params-kv {
  margin-bottom: 0;
}
.log-section {
  font-size: 13px;
  margin-bottom: 10px;
}
.params-card {
  background: var(--cream);
  margin-bottom: 18px;
}
.params-note {
  margin-top: 10px;
}
.log-json-summary {
  cursor: pointer;
}
.log-note {
  margin-top: 18px;
}
.export-note {
  margin-top: 12px;
}
</style>