<script setup lang="ts">
/**
 * 答案质量 —— V3.7。
 * 用户点踩/点赞、点踩原因分布与最近点踩明细；用于把线上差评回流成评测用例。
 */
import { computed, onMounted, ref } from 'vue'
import PageHead from '@/components/PageHead.vue'
import {
  fetchAnswerQuality,
  fetchAnswerQualityTrend,
  fetchNegativeFeedback,
  type AnswerQuality,
  type AnswerQualityTrendPoint,
  type NegativeFeedbackItem,
} from '@/api/customerService'

const days = ref(14)
const loading = ref(false)
const quality = ref<AnswerQuality | null>(null)
const trend = ref<AnswerQualityTrendPoint[]>([])
const recent = ref<NegativeFeedbackItem[]>([])

const trendMax = computed(() =>
  Math.max(1, ...trend.value.map((p) => Math.max(p.up || 0, p.down || 0))),
)
const reasonMax = computed(() =>
  Math.max(1, ...(quality.value?.reasons || []).map((r) => r.count || 0)),
)

async function load() {
  loading.value = true
  try {
    const [stats, points, rows] = await Promise.all([
      fetchAnswerQuality(days.value),
      fetchAnswerQualityTrend(days.value),
      fetchNegativeFeedback(20),
    ])
    quality.value = (stats.data as AnswerQuality) || null
    trend.value = (points.data as AnswerQualityTrendPoint[]) || []
    recent.value = (rows.data as NegativeFeedbackItem[]) || []
  } finally {
    loading.value = false
  }
}

function barHeight(value: number): string {
  return `${Math.max(2, Math.round(((value || 0) / trendMax.value) * 120))}px`
}

function reasonWidth(value: number): string {
  return `${Math.max(4, Math.round(((value || 0) / reasonMax.value) * 100))}%`
}

function fmtTime(value?: string): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

function preview(value?: string | null): string {
  if (!value) return '-'
  return value.length > 120 ? `${value.slice(0, 120)}…` : value
}

onMounted(load)
</script>

<template>
  <div>
    <PageHead title="答案质量" sub="近 N 天：点赞/点踩、点踩原因与最近差评，用于回流评测集">
      <template #actions>
        <el-radio-group v-model="days" @change="load">
          <el-radio-button :value="1">今日</el-radio-button>
          <el-radio-button :value="7">近 7 日</el-radio-button>
          <el-radio-button :value="14">近 14 日</el-radio-button>
          <el-radio-button :value="30">近 30 日</el-radio-button>
        </el-radio-group>
      </template>
    </PageHead>

    <div v-loading="loading" class="cards">
      <div class="card">
        <div class="num">{{ quality?.total ?? 0 }}</div>
        <div class="label">有效反馈</div>
      </div>
      <div class="card">
        <div class="num">{{ quality?.upRate ?? 0 }}%</div>
        <div class="label">点赞率（{{ quality?.up ?? 0 }}）</div>
      </div>
      <div class="card">
        <div class="num num--down">{{ quality?.downRate ?? 0 }}%</div>
        <div class="label">点踩率（{{ quality?.down ?? 0 }}）</div>
      </div>
      <div class="card">
        <div class="num">{{ quality?.down ?? 0 }}</div>
        <div class="label">待复核差评</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <div class="panel-title">点踩原因分布</div>
      </div>
      <div v-if="quality?.reasons?.length" class="reasons">
        <div v-for="item in quality.reasons" :key="item.reason" class="reason-row">
          <span class="reason-label">{{ item.reason }}</span>
          <div class="reason-track">
            <div class="reason-bar" :style="{ width: reasonWidth(item.count) }" />
          </div>
          <span class="reason-count">{{ item.count }}</span>
        </div>
      </div>
      <div v-else class="empty">暂无点踩数据</div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <div class="panel-title">每日趋势</div>
        <div class="legend">
          <span><i class="dot dot--up" />点赞</span>
          <span><i class="dot dot--down" />点踩</span>
        </div>
      </div>
      <div class="chart">
        <div v-for="point in trend" :key="point.date" class="chart-col">
          <div class="bars">
            <div class="bar bar--up" :style="{ height: barHeight(point.up) }" :title="`点赞 ${point.up}`" />
            <div class="bar bar--down" :style="{ height: barHeight(point.down) }" :title="`点踩 ${point.down}`" />
          </div>
          <div class="chart-date">{{ point.date.slice(5) }}</div>
        </div>
      </div>
      <div v-if="!trend.length" class="empty">暂无趋势数据</div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <div class="panel-title">最近点踩</div>
        <div class="sub">按时间倒序，最多 20 条</div>
      </div>
      <el-table :data="recent" size="small" empty-text="暂无差评">
        <el-table-column prop="created_at" label="时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" width="110">
          <template #default="{ row }">{{ row.reason || '未填写' }}</template>
        </el-table-column>
        <el-table-column prop="answer" label="回答预览" min-width="320">
          <template #default="{ row }">{{ preview(row.answer) }}</template>
        </el-table-column>
        <el-table-column prop="comment" label="补充" width="180">
          <template #default="{ row }">{{ row.comment || '-' }}</template>
        </el-table-column>
        <el-table-column prop="run_id" label="Run" width="100">
          <template #default="{ row }">{{ row.run_id || '-' }}</template>
        </el-table-column>
      </el-table>
    </div>

    <el-alert
      class="hint"
      type="info"
      :closable="false"
      title="差评明细可直接用于构造 conversation-repair 回归用例；评测通过后再关闭对应问题。"
    />
  </div>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}
.card {
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 10px;
  padding: 18px;
}
.num {
  font-size: 28px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}
.num--down {
  color: #f56c6c;
}
.label {
  margin-top: 6px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}
.panel {
  margin-top: 18px;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 10px;
  padding: 18px;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.panel-title {
  font-weight: 600;
}
.sub {
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}
.reasons {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.reason-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.reason-label {
  width: 88px;
  font-size: 13px;
  color: var(--el-text-color-regular, #606266);
}
.reason-track {
  flex: 1;
  height: 10px;
  background: #f5f7fa;
  border-radius: 6px;
  overflow: hidden;
}
.reason-bar {
  height: 100%;
  background: #f56c6c;
  border-radius: 6px;
}
.reason-count {
  width: 40px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
.legend {
  display: flex;
  gap: 14px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  display: inline-block;
}
.dot--up {
  background: #67c23a;
}
.dot--down {
  background: #f56c6c;
}
.chart {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  min-height: 150px;
  overflow-x: auto;
}
.chart-col {
  flex: 1 0 22px;
  text-align: center;
}
.bars {
  height: 122px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
}
.bar {
  width: 7px;
  border-radius: 2px 2px 0 0;
}
.bar--up {
  background: #67c23a;
}
.bar--down {
  background: #f56c6c;
}
.chart-date {
  margin-top: 6px;
  font-size: 10px;
  color: var(--el-text-color-secondary, #909399);
  white-space: nowrap;
}
.empty {
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
  padding: 12px 0;
}
.hint {
  margin-top: 18px;
}
</style>
