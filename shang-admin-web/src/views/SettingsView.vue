<script setup lang="ts">
/**
 * 系统设置 —— 对齐原型 .stitch/原型/admin/settings-trade.html 与 dialogs-v2.html。
 * 弹窗：dlg-trade-save（D2V-15 保存交易设置，保存即对新单生效）、
 *       dlg-trade-invalid（D2V-16 保存校验失败，逐条列原因）。
 * 校验口径：待付款超时 5～120 的整数；免运门槛与默认运费 ≥ 0；补位排序取 sold / new；
 *          沙箱成功延时 ≥ 0 的整数。校验不通过时不写库，本页已填内容保留。
 * V1 的 KV 列表（逐条保存）保持现状。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { fetchSystemConfig, updateSystemConfig } from '@/api/settings'
import { unwrapList } from '@/utils/page'

type CfgTab = 'TRADE' | 'PAYMENT' | 'all' | string
type CfgRow = {
  id: number
  configKey: string
  category: string
  configValue: string
  remark: string
  isSecret: boolean
  dirty?: boolean
}

/** 校验失败原因：head + <b>bold</b> + mid + <b>tailBold</b> + tail，与图鉴逐字一致 */
type Problem = { head: string; bold: string; mid?: string; tailBold?: string; tail: string }

const TRADE_KEYS = [
  'order.pay-timeout-minutes',
  'freight.free-threshold',
  'freight.default-amount',
  'home.feed-sort',
] as const

const SANDBOX_KEYS = [
  'payment.sandbox.enabled',
  'payment.sandbox.channel',
  'payment.sandbox.merchant',
  'payment.sandbox.delay-ms',
  'payment.sandbox.notify-url',
] as const

const TRADE_LABEL: Record<string, string> = {
  'order.pay-timeout-minutes': '待付款超时（分钟）',
  'freight.free-threshold': '免运门槛（元）',
  'freight.default-amount': '默认运费（元）',
  'home.feed-sort': '推荐补位排序',
}

const TRADE_HINT: Record<string, string> = {
  'order.pay-timeout-minutes': '5～120 的整数，默认 15；超时自动关单并释放库存',
  'freight.free-threshold': '≥ 0，默认 99；商品金额达到该值即免运费',
  'freight.default-amount': '≥ 0，默认 8；未达免运门槛时按此金额收取',
  'home.feed-sort': '仅 sold / new；运营位不足时按此策略回填商品',
}

const SANDBOX_LABEL: Record<string, string> = {
  'payment.sandbox.enabled': '沙箱渠道开关',
  'payment.sandbox.channel': '渠道标识',
  'payment.sandbox.merchant': '模拟支付商户号',
  'payment.sandbox.delay-ms': '默认成功延时（ms）',
  'payment.sandbox.notify-url': '回调地址',
}

/** D2V-16 逐条原因里的字段名（不含单位括号，与图鉴逐字一致） */
const PROBLEM_LABEL: Record<string, string> = {
  'freight.free-threshold': '免运门槛',
  'freight.default-amount': '默认运费',
}

const SANDBOX_HINT: Record<string, string> = {
  'payment.sandbox.enabled': '开启后购物端调「模拟支付」接口完成订单流转',
  'payment.sandbox.channel': '下单时写入支付单的渠道字段',
  'payment.sandbox.delay-ms': '≥ 0，默认 800；用于演示支付成功与失败的中间态',
}

const tab = ref<CfgTab>('TRADE')
const rows = ref<CfgRow[]>([])
const tradeSaving = ref(false)
/** 字段级校验错误（内联 .error-text）+ D2V-16 逐条列出的原因 */
const errors = ref<Record<string, string>>({})
const problems = ref<Problem[]>([])
const saveDialog = ref(false)
const saveError = ref('')
const invalidDialog = ref(false)

const categories = computed(() => {
  const set = new Set(rows.value.map((r) => r.category).filter(Boolean))
  // 仅列出本版本已落地的配置分组；后续版本的配置分组在该版本内追加
  const preferred = ['TRADE', 'PAYMENT', 'SYS']
  const ordered = preferred.filter((c) => set.has(c) || c === 'TRADE')
  const rest = [...set].filter((c) => !ordered.includes(c))
  return [...ordered, ...rest, 'all'] as CfgTab[]
})

const visibleRows = computed(() => {
  if (tab.value === 'TRADE') return []
  if (tab.value === 'all') return rows.value
  return rows.value.filter((r) => r.category === tab.value)
})

const tradeRows = computed(() =>
  TRADE_KEYS.map((key) => rows.value.find((r) => r.configKey === key)).filter(
    (r): r is CfgRow => Boolean(r),
  ),
)

const sandboxRows = computed(() =>
  SANDBOX_KEYS.map((key) => rows.value.find((r) => r.configKey === key)).filter(
    (r): r is CfgRow => Boolean(r),
  ),
)

const sandboxEnabled = computed({
  get() {
    const row = rows.value.find((r) => r.configKey === 'payment.sandbox.enabled')
    return row ? row.configValue === 'true' : true
  },
  set(v: boolean) {
    const row = rows.value.find((r) => r.configKey === 'payment.sandbox.enabled')
    if (row) {
      row.configValue = v ? 'true' : 'false'
      row.dirty = true
      clearError(row.configKey)
    }
  },
})

function cfgValue(key: string, fallback = '—'): string {
  const v = rows.value.find((r) => r.configKey === key)?.configValue.trim()
  return v ? v : fallback
}

/** D2V-15 保存确认回显的当前值 */
const feedSortText = computed(() =>
  cfgValue('home.feed-sort') === 'new' ? 'new · 上新优先' : 'sold · 销量优先',
)
const sandboxText = computed(() => (sandboxEnabled.value ? '开启' : '关闭'))

function tabLabel(c: CfgTab) {
  if (c === 'TRADE') return '交易'
  if (c === 'PAYMENT') return '沙箱支付'
  if (c === 'SYS') return '系统参数'
  if (c === 'all') return '全部 KV'
  return c
}

/** 负数用「−」显示，与图鉴的 −8 一致 */
function numText(v: string): string {
  return v.startsWith('-') ? `−${v.slice(1)}` : v
}

function clearError(key: string) {
  if (!errors.value[key]) return
  const next = { ...errors.value }
  delete next[key]
  errors.value = next
}

async function load() {
  try {
    const res = await fetchSystemConfig()
    rows.value = unwrapList<Record<string, unknown>>(res.data).map((c) => ({
      id: Number(c.id),
      configKey: String(c.configKey ?? ''),
      category: String(c.category ?? 'default'),
      configValue: String(c.configValue ?? ''),
      remark: String(c.remark ?? ''),
      isSecret: Boolean(c.isSecret),
      dirty: false,
    }))
  } catch {
    rows.value = []
  }
}

function markDirty(row: CfgRow) {
  row.dirty = true
  clearError(row.configKey)
}

async function saveRow(row: CfgRow) {
  if (row.isSecret && row.configValue === '******') {
    ElMessage.warning('敏感配置请输入新值后再保存')
    return
  }
  try {
    await updateSystemConfig(row.id, {
      configValue: row.configValue,
      remark: row.remark,
    })
    row.dirty = false
    ElMessage.success(`${row.configKey} 已保存`)
  } catch {
    /* toasted */
  }
}

/** 逐条校验：把「所有」不符合项都列出来，只弹第一条是不够的 */
function validate(): boolean {
  const errs: Record<string, string> = {}
  const list: Problem[] = []

  for (const row of tradeRows.value) {
    const v = row.configValue.trim()
    const label = TRADE_LABEL[row.configKey] || row.configKey
    if (row.configKey === 'order.pay-timeout-minutes') {
      const n = Number(v)
      if (!/^\d+$/.test(v) || n < 5 || n > 120) {
        errs[row.configKey] = '待付款超时为 5～120 的整数'
        list.push({
          head: '待付款超时 ',
          bold: v || '空',
          mid: ' 分钟，超出 ',
          tailBold: '5～120',
          tail: ' 分钟范围',
        })
      }
    } else if (row.configKey === 'home.feed-sort') {
      if (v !== 'sold' && v !== 'new') {
        errs[row.configKey] = '推荐补位排序仅支持 sold / new'
        list.push({
          head: '推荐补位排序 ',
          bold: v || '空',
          tail: '，仅支持 sold / new',
        })
      }
    } else {
      const n = Number(v)
      if (v === '' || !Number.isFinite(n) || n < 0) {
        errs[row.configKey] = `${label}须为 ≥0 的数字`
        const problemLabel = PROBLEM_LABEL[row.configKey] || label
        list.push({
          head: `${problemLabel} `,
          bold: v ? numText(v) : '空',
          tail: ' 元，金额不能为负数',
        })
      }
    }
  }

  const delay = sandboxRows.value.find((r) => r.configKey === 'payment.sandbox.delay-ms')
  if (delay) {
    const v = delay.configValue.trim()
    const n = Number(v)
    if (v === '' || !/^\d+$/.test(v) || !Number.isFinite(n) || n < 0) {
      errs['payment.sandbox.delay-ms'] = '成功延时须为 ≥ 0 的整数'
      list.push({
        head: '沙箱成功延时 ',
        bold: v || '空',
        tail: ' ms，须为 ≥ 0 的整数',
      })
    }
  }

  errors.value = errs
  problems.value = list
  return list.length === 0
}

function openSave() {
  const targets = [...tradeRows.value, ...sandboxRows.value]
  if (!targets.length) {
    ElMessage.warning('未找到 TRADE/PAYMENT 配置，请确认 Flyway V11/V12 已执行')
    return
  }
  saveError.value = ''
  if (!validate()) {
    invalidDialog.value = true
    return
  }
  saveDialog.value = true
}

async function confirmSave() {
  const targets = [...tradeRows.value, ...sandboxRows.value]
  saveError.value = ''
  tradeSaving.value = true
  try {
    for (const row of targets) {
      await updateSystemConfig(row.id, {
        configValue: row.configValue.trim(),
        remark: row.remark,
      })
      row.dirty = false
    }
    ElMessage.success('已保存，对新单立即生效')
    saveDialog.value = false
  } catch (e: unknown) {
    const msg =
      (e as { msg?: unknown })?.msg ??
      (e as { response?: { data?: { msg?: unknown } } })?.response?.data?.msg
    saveError.value = typeof msg === 'string' && msg ? msg : '保存失败，请稍后重试'
  } finally {
    tradeSaving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead
    title="系统设置"
    sub="交易分组表单：待付款超时、免运门槛、默认运费、推荐补位排序与沙箱支付参数 · 保存即对新订单生效"
  >
    <template #actions>
      <button
        v-if="tab === 'TRADE' || tab === 'PAYMENT'"
        class="btn btn-primary btn-sm"
        type="button"
        :disabled="tradeSaving"
        @click="openSave"
      >
        保存
      </button>
    </template>
  </PageHead>

  <div class="cfg-tabs">
    <div
      v-for="c in categories"
      :key="c"
      class="cfg-tab"
      :class="{ active: tab === c }"
      @click="tab = c"
    >
      {{ tabLabel(c) }}
    </div>
  </div>

  <div v-if="tab === 'TRADE' || tab === 'PAYMENT'" class="card card-pad cfg-card">
    <template v-if="tab === 'TRADE'">
      <div class="page-title cfg-section-title">交易规则</div>
      <p v-if="!tradeRows.length" class="hint">暂无 TRADE 配置，请执行 Flyway V11 后刷新</p>
      <div v-else class="form-grid">
        <div v-for="row in tradeRows" :key="row.id" class="field">
          <label class="label">{{ TRADE_LABEL[row.configKey] || row.configKey }}</label>
          <select
            v-if="row.configKey === 'home.feed-sort'"
            v-model="row.configValue"
            class="select"
            @change="markDirty(row)"
          >
            <option value="sold">sold · 销量优先</option>
            <option value="new">new · 上新优先</option>
          </select>
          <input
            v-else
            v-model="row.configValue"
            class="input"
            type="number"
            :min="row.configKey === 'order.pay-timeout-minutes' ? 5 : 0"
            :max="row.configKey === 'order.pay-timeout-minutes' ? 120 : undefined"
            step="1"
            @input="markDirty(row)"
          />
          <span class="hint">{{ TRADE_HINT[row.configKey] || row.remark }}</span>
          <span v-if="errors[row.configKey]" class="error-text">{{ errors[row.configKey] }}</span>
        </div>
      </div>
      <hr class="sep" />
    </template>

    <div class="page-title cfg-section-title">沙箱支付</div>
    <p v-if="!sandboxRows.length" class="hint">暂无沙箱配置，请执行 Flyway V12 后刷新</p>
    <div v-else class="form-grid">
      <div
        v-for="row in sandboxRows"
        :key="row.id"
        class="field"
        :class="{ full: row.configKey === 'payment.sandbox.notify-url' }"
      >
        <label class="label">{{ SANDBOX_LABEL[row.configKey] || row.configKey }}</label>
        <template v-if="row.configKey === 'payment.sandbox.enabled'">
          <label class="switch">
            <input v-model="sandboxEnabled" type="checkbox" />
            <span class="track" />
          </label>
        </template>
        <input
          v-else
          v-model="row.configValue"
          class="input"
          :type="row.configKey === 'payment.sandbox.delay-ms' ? 'number' : 'text'"
          @input="markDirty(row)"
        />
        <span v-if="SANDBOX_HINT[row.configKey]" class="hint">{{ SANDBOX_HINT[row.configKey] }}</span>
        <span v-if="errors[row.configKey]" class="error-text">{{ errors[row.configKey] }}</span>
      </div>
    </div>
  </div>

  <div v-else class="card card-pad">
    <p v-if="!visibleRows.length" class="hint">暂无系统配置</p>
    <table v-else class="table">
      <thead>
        <tr>
          <th>分类</th>
          <th class="code">键</th>
          <th>值</th>
          <th>备注</th>
          <th class="col-op">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in visibleRows" :key="row.id">
          <td>{{ row.category }}</td>
          <td class="code">{{ row.configKey }}</td>
          <td>
            <input
              v-model="row.configValue"
              class="input kv-input"
              :type="row.isSecret ? 'password' : 'text'"
              @input="markDirty(row)"
            />
          </td>
          <td>
            <input
              v-model="row.remark"
              class="input kv-input kv-input-sm"
              @input="markDirty(row)"
            />
          </td>
          <td class="col-op">
            <button
              class="btn btn-sm"
              :class="row.dirty ? 'btn-primary' : 'btn-ghost'"
              type="button"
              @click="saveRow(row)"
            >
              保存
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- 保存交易设置（D2V-15 · dlg-trade-save）：保存即对新单生效 -->
  <FormDialog
    v-model="saveDialog"
    title="保存交易设置"
    icon="info"
    icon-text="✓"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认保存"
    :loading="tradeSaving"
    @submit="confirmSave"
  >
    <p v-if="saveError" class="error-text">{{ saveError }}</p>
    <p>将保存以下交易参数：</p>
    <ul class="impact">
      <li>待付款超时：<b>{{ cfgValue('order.pay-timeout-minutes') }}</b> 分钟</li>
      <li>
        免运门槛：<b>¥{{ cfgValue('freight.free-threshold') }}</b> · 默认运费：<b>¥{{
          cfgValue('freight.default-amount')
        }}</b>
      </li>
      <li>推荐补位排序：<b>{{ feedSortText }}</b></li>
      <li v-if="sandboxRows.length">
        沙箱渠道：<b>{{ sandboxText }}</b> · 成功延时
        <b>{{ cfgValue('payment.sandbox.delay-ms') }}</b> ms
      </li>
    </ul>
    <div class="alert alert-info impact-alert">
      保存后<b>立即对新订单生效</b>：新单按新门槛计算运费、按下新的超时时间关单；已生成订单的金额与超时时间不变。修改会写入操作日志。
    </div>
  </FormDialog>

  <!-- 保存校验失败（D2V-16 · dlg-trade-invalid）：逐条列原因 -->
  <NoticeDialog
    v-model="invalidDialog"
    title="无法保存"
    icon="warn"
    width="is-md"
    confirm-text="去修正"
  >
    <p>交易设置有 <b>{{ problems.length }}</b> 处不符合要求，请修正后重试：</p>
    <ul class="impact">
      <li v-for="(p, i) in problems" :key="i">
        <span>{{ p.head }}<b>{{ p.bold }}</b>{{ p.mid }}<b v-if="p.tailBold">{{ p.tailBold }}</b>{{ p.tail }}</span>
      </li>
    </ul>
    <div class="alert alert-info impact-alert">
      校验口径：待付款超时 5～120 的整数；免运门槛与默认运费 ≥ 0；补位排序取 sold（销量优先）或 new（上新优先）；
      沙箱成功延时 ≥ 0 的整数。校验不通过时<b>不写库</b>，本页已填内容保留。
    </div>
  </NoticeDialog>
</template>

<style scoped>
.cfg-card {
  max-width: 760px;
}
.cfg-section-title {
  font-size: 14px;
  margin-bottom: 12px;
}
.sep {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 18px 0;
}
.kv-input {
  min-width: 200px;
}
.kv-input-sm {
  min-width: 120px;
}
.impact-alert {
  margin-top: 12px;
}
</style>