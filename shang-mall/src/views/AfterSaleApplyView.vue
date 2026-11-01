<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast, type UploaderFileListItem } from 'vant'
import { applyAfterSale, fetchOrderDetail } from '@/api/order'
import { uploadFile } from '@/api/customer'
import { formatPrice } from '@/utils/format'
import { resolveMediaUrl } from '@/utils/media'
import StoreIcon from '@/components/StoreIcon.vue'

/**
 * 申请售后（PRD §8.9 / 图鉴 RD-04）
 * 售后类型（仅退款 / 退货退款）+ 售后原因（下拉选择，未选内联报错）+ 凭证 1~3 张 + 补充说明 ≤200 字。
 */
const route = useRoute()
const router = useRouter()
const submitting = ref(false)

/** 售后原因下拉（图鉴 RD-04 选项） */
const REASON_OPTIONS = ['商品与描述不符', '商品破损 / 有瑕疵', '少件 / 发错货', '不想要了']
const SUPPLEMENT_MAX = 200
const EVIDENCE_MAX = 3
const EVIDENCE_MAX_MB = 10
const EVIDENCE_TYPES = ['image/jpeg', 'image/png', 'image/webp']

const TYPE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'REFUND', label: '仅退款' },
  { value: 'RETURN_REFUND', label: '退货退款' },
]

const form = reactive({
  orderId: String(route.query.orderId || ''),
  type: 'REFUND',
  reason: '',
  supplement: '',
  evidenceKeys: [] as string[],
})

const reasonError = ref(false)
const files = ref<UploaderFileListItem[]>([])

/** 商品摘要（RD-04 顶部商品行：名称 / 规格 × 数量 · 实付） */
const order = ref<Record<string, unknown> | null>(null)
const items = ref<Array<Record<string, unknown>>>([])
const goods = computed(() => items.value[0] || null)
const payAmount = computed(() => Number(order.value?.payAmount ?? 0))

onMounted(async () => {
  if (!form.orderId) return
  try {
    const res = await fetchOrderDetail(form.orderId)
    const data = res.data as {
      order?: Record<string, unknown>
      items?: Array<Record<string, unknown>>
    }
    order.value = data.order || null
    items.value = data.items || []
  } catch {
    order.value = null
    items.value = []
  }
})

function specsText(item: Record<string, unknown>): string {
  const specs = item.specs as Record<string, unknown> | undefined
  const values = specs
    ? Object.values(specs).filter((v) => v !== null && v !== undefined && v !== '')
    : []
  return values.length ? values.map(String).join(' · ') : '默认规格'
}

function selectType(value: string) {
  form.type = value
}

function selectReason(e: Event) {
  form.reason = (e.target as HTMLSelectElement).value
  if (form.reason) reasonError.value = false
}

function onSupplementInput(e: Event) {
  form.supplement = (e.target as HTMLTextAreaElement).value.slice(0, SUPPLEMENT_MAX)
}

function dropFile(item: UploaderFileListItem, message: string) {
  const index = files.value.indexOf(item)
  if (index >= 0) files.value.splice(index, 1)
  showToast(message)
}

async function onUpload(item: UploaderFileListItem) {
  const file = item.file
  if (!file) return
  if (!EVIDENCE_TYPES.includes(file.type)) {
    dropFile(item, '仅支持 jpeg / png / webp 图片')
    return
  }
  if (file.size > EVIDENCE_MAX_MB * 1024 * 1024) {
    dropFile(item, `单张凭证不超过 ${EVIDENCE_MAX_MB}MB`)
    return
  }
  try {
    const res = await uploadFile(file, 'AFTER_SALE')
    form.evidenceKeys.push(res.data.objectKey)
  } catch {
    dropFile(item, '凭证上传失败，请重试')
  }
}

function afterRead(items: UploaderFileListItem | UploaderFileListItem[]) {
  const list = Array.isArray(items) ? items : [items]
  for (const item of list) void onUpload(item)
}

function onDeletePic(_file: UploaderFileListItem, detail: { index: number }) {
  form.evidenceKeys.splice(detail.index, 1)
}

async function onSubmit() {
  if (submitting.value) return
  if (!form.orderId) {
    showToast('缺少订单信息，请从订单详情发起申请')
    return
  }
  if (!form.reason) {
    reasonError.value = true
    return
  }
  submitting.value = true
  try {
    // 后端 after_sale.reason 为单字段文本（VARCHAR），将「下拉值 + 补充说明」合并提交
    const supplement = form.supplement.trim()
    const res = await applyAfterSale({
      orderId: form.orderId,
      type: form.type,
      reason: supplement ? `${form.reason}（${supplement}）` : form.reason,
      evidenceKeys: form.evidenceKeys,
    })
    showToast('已提交')
    await router.replace(`/after-sales/${res.data}`)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page apply-page">
    <h1 class="page-title">申请售后</h1>

    <div class="panel">
      <div v-if="goods" class="dlg-goods">
        <div class="pic">
          <img v-if="goods.pic" :src="resolveMediaUrl(String(goods.pic))" alt="" />
        </div>
        <div class="meta">
          <div class="t">{{ goods.title }}</div>
          <div class="s">
            {{ specsText(goods) }} × {{ goods.quantity }} · 实付 ¥{{ formatPrice(payAmount) }}
          </div>
        </div>
      </div>
      <p v-else class="hint">订单 {{ form.orderId || '—' }}</p>
      <hr class="divider" />

      <div class="field">
        <span class="field-label">
          售后类型<span class="req">*</span>
        </span>
        <div class="chip-row">
          <button
            v-for="t in TYPE_OPTIONS"
            :key="t.value"
            type="button"
            class="chip"
            :class="{ active: form.type === t.value }"
            :aria-pressed="form.type === t.value"
            @click="selectType(t.value)"
          >
            {{ t.label }}
          </button>
        </div>
      </div>

      <div class="field">
        <span class="field-label" :class="{ 'is-err': reasonError }">
          售后原因<span class="req">*</span>
        </span>
        <select
          class="input select"
          :class="{ 'is-err': reasonError }"
          :value="form.reason"
          @change="selectReason"
        >
          <option value="">请选择售后原因</option>
          <option v-for="r in REASON_OPTIONS" :key="r" :value="r">{{ r }}</option>
        </select>
        <span v-if="reasonError" class="error-text">请选择售后原因</span>
      </div>

      <div class="field">
        <span class="field-label">凭证图片（选填，最多 {{ EVIDENCE_MAX }} 张）</span>
        <van-uploader
          v-model="files"
          :max-count="EVIDENCE_MAX"
          accept="image/jpeg,image/png,image/webp"
          :after-read="afterRead"
          @delete="onDeletePic"
          @oversize="showToast(`凭证最多 ${EVIDENCE_MAX} 张`)"
        >
          <div class="up-box">
            <StoreIcon name="plus" :size="20" />
            <span>上传凭证</span>
          </div>
        </van-uploader>
        <span class="hint">
          jpeg / png / webp，单张 ≤{{ EVIDENCE_MAX_MB }}MB；凭证仅你与售后审核人员可见
        </span>
      </div>

      <div class="field">
        <span class="field-label">补充说明（选填）</span>
        <textarea
          class="input"
          :value="form.supplement"
          :maxlength="SUPPLEMENT_MAX"
          placeholder="请补充说明问题，最多 200 字"
          @input="onSupplementInput"
        />
        <span class="hint">{{ form.supplement.length }} / {{ SUPPLEMENT_MAX }}</span>
      </div>

      <div class="pop-alert info">
        <StoreIcon name="alert" :size="16" />
        仅待收货、已完成订单可申请售后；同一订单同时只允许一个进行中的售后单。退款为模拟退款，无真实资金流。
      </div>
    </div>

    <van-button type="primary" block :loading="submitting" @click="onSubmit">提交申请</van-button>
  </div>
</template>

<style scoped>
.panel {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  padding: 16px;
  margin-bottom: 16px;
}

.dlg-goods {
  display: flex;
  gap: 12px;
  align-items: center;
}

.dlg-goods .pic {
  width: 64px;
  height: 64px;
  flex-shrink: 0;
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--sand);
}

.dlg-goods .pic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.dlg-goods .meta {
  flex: 1;
  min-width: 0;
}

.dlg-goods .t {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.4;
}

.dlg-goods .s {
  margin-top: 2px;
  font-size: 12px;
  color: var(--slate);
}

.divider {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 14px 0;
}

.field {
  margin-bottom: 16px;
}

.chip-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.select {
  appearance: none;
  cursor: pointer;
}

.field textarea.input {
  min-height: 84px;
  line-height: 1.6;
  resize: vertical;
}

.up-box {
  width: 76px;
  height: 76px;
  border-radius: var(--r-md);
  border: 1px dashed var(--border);
  background: var(--cream);
  display: grid;
  place-items: center;
  gap: 2px;
  padding: 6px;
  text-align: center;
  font-size: 11px;
  color: var(--slate);
  cursor: pointer;
}

.field .hint {
  display: block;
  margin-top: 6px;
}
</style>