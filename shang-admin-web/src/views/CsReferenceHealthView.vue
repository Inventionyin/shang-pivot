<script setup lang="ts">
/**
 * 指代加购健康 —— V3.6。
 * 只读指标：运行失败/转人工、加购调用成功率、指代反问率、检索无命中率。
 */
import { onMounted, ref } from 'vue'
import PageHead from '@/components/PageHead.vue'
import { fetchReferenceHealth } from '@/api/customerService'

const days = ref(14)
const loading = ref(false)
const metrics = ref<Record<string, number | string>>({})

async function load() {
  loading.value = true
  try {
    const res = await fetchReferenceHealth(days.value)
    metrics.value = (res.data as Record<string, number | string>) || {}
  } finally {
    loading.value = false
  }
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
</style>
