<script setup lang="ts">
/**
 * 指代加购健康 —— V3.6。
 * 只读指标：运行失败/转人工、加购调用成功率、指代反问率、检索无命中率。
 */
import { computed, onMounted, ref } from 'vue'
import PageHead from '@/components/PageHead.vue'
import { fetchReferenceHealth, fetchReferenceTrend, type ReferenceTrendPoint } from '@/api/customerService'

const days = ref(14)
const loading = ref(false)
const metrics = ref<Record<string, number | string>>({})
const trend = ref<ReferenceTrendPoint[]>([])
const trendMax = computed(() => Math.max(1, ...trend.value.map((p) => Math.max(p.runs || 0, p.contextAsk || 0))))

async function load() {
  loading.value = true
  try {
    const [health, points] = await Promise.all([
      fetchReferenceHealth(days.value),
      fetchReferenceTrend(days.value),
    ])
    metrics.value = (health.data as Record<string, number | string>) || {}
    trend.value = (points.data as ReferenceTrendPoint[]) || []
  } finally {
    loading.value = false
  }
}

function barHeight(value: number): string {
  return `${Math.max(2, Math.round(((value || 0) / trendMax.value) * 120))}px`
}

function value(key: string): string {
  const raw = metrics.value[key]
  return raw === undefined || raw === null || raw === '' ? '-' : String(raw)
}

function percent(key: string): string {
  const raw = metrics.value[key]
  if (raw === undefined || raw === null || raw === '') return '-'
  return `${raw}%`
}

onMounted(load)
</script>

<template>
  <div>
    <PageHead title="指代加购健康" sub="近 N 天：失败率 / 加购成功率 / 指代反问率 / 检索命中率">
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
        <div class="num">{{ value('runs') }}</div>
        <div class="label">Agent 运行</div>
      </div>
      <div class="card">
        <div class="num">{{ percent('failedRate') }}</div>
        <div class="label">运行失败率（{{ value('failed') }}）</div>
      </div>
      <div class="card">
        <div class="num">{{ percent('handoffRate') }}</div>
        <div class="label">转人工率（{{ value('handoff') }}）</div>
      </div>
      <div class="card">
        <div class="num">{{ percent('cartSuccessRate') }}</div>
        <div class="label">加购成功率（{{ value('cartSuccess') }}/{{ value('cartCalls') }}）</div>
      </div>
      <div class="card">
        <div class="num">{{ value('cartPending') }}</div>
        <div class="label">待确认加购</div>
      </div>
      <div class="card">
        <div class="num">{{ percent('contextAskRate') }}</div>
        <div class="label">指代反问率（{{ value('contextAsk') }}）</div>
      </div>
      <div class="card">
        <div class="num">{{ value('skuAsk') }}</div>
        <div class="label">规格反问</div>
      </div>
      <div class="card">
        <div class="num">{{ percent('retrievalMissRate') }}</div>
        <div class="label">检索无命中率（{{ value('retrievalMiss') }}/{{ value('retrievals') }}）</div>
      </div>
    </div>

    <div class="trend-panel">
      <div class="trend-head">
        <div class="trend-title">每日趋势</div>
        <div class="legend">
          <span><i class="dot dot--runs" />运行</span>
          <span><i class="dot dot--ask" />指代反问</span>
        </div>
      </div>
      <div class="chart">
        <div v-for="point in trend" :key="point.date" class="chart-col">
          <div class="bars">
            <div class="bar bar--runs" :style="{ height: barHeight(point.runs) }" :title="`运行 ${point.runs}`" />
            <div class="bar bar--ask" :style="{ height: barHeight(point.contextAsk) }" :title="`反问 ${point.contextAsk}`" />
          </div>
          <div class="chart-date">{{ point.date.slice(5) }}</div>
        </div>
      </div>
      <div v-if="!trend.length" class="trend-empty">暂无趋势数据</div>
    </div>

    <el-alert
      class="hint"
      type="info"
      :closable="false"
      title="指标只用于观测；商品归因正确率以平台 reference-cart / reference-cart-auto 套件为准。"
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
.label {
  margin-top: 6px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}
.hint {
  margin-top: 18px;
}
.trend-panel {
  margin-top: 18px;
  background: var(--el-bg-color, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 10px;
  padding: 18px;
}
.trend-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.trend-title {
  font-weight: 600;
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
.dot--runs {
  background: #409eff;
}
.dot--ask {
  background: #e6a23c;
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
.bar--runs {
  background: #409eff;
}
.bar--ask {
  background: #e6a23c;
}
.chart-date {
  margin-top: 6px;
  font-size: 10px;
  color: var(--el-text-color-secondary, #909399);
  white-space: nowrap;
}
.trend-empty {
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
  padding: 12px 0;
}
</style>
