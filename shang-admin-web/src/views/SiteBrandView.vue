<script setup lang="ts">
/**
 * 站点品牌 —— 对齐原型 .stitch/原型/admin/site-brand.html 与 dialogs-v2.html。
 * 弹窗：dlg-brand-save（D2V-03 保存站点品牌）、dlg-brand-invalid（D2V-04 保存校验失败，逐条列原因）、
 *       dlg-brand-logo-bad（D2V-05 上传新 Logo 不符合要求）。
 * 校验口径：Logo 1 张、jpeg / png / webp、≤10MB、建议 256×256；站点名 1～30 字符；Slogan ≤50 字符。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHead from '@/components/PageHead.vue'
import FormDialog from '@/components/FormDialog.vue'
import NoticeDialog from '@/components/NoticeDialog.vue'
import { fetchSiteBrand, updateSiteBrand } from '@/api/content'
import { uploadFile } from '@/api/files'
import { resolveMediaUrl } from '@/utils/media'
import { usePermission } from '@/utils/permission'

type Problem = { head: string; bold: string; tail: string }

const ALLOWED_LOGO_EXT = ['jpeg', 'jpg', 'png', 'webp']
const MAX_LOGO_BYTES = 10 * 1024 * 1024

const { hasPermission } = usePermission()
const canEdit = computed(() => hasPermission('content:brand'))

const loading = ref(false)
const saving = ref(false)
const logoKey = ref('')
const name = ref('')
const slogan = ref('')
const faviconKey = ref('')

const logoInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

/** 字段级校验错误：内联在控件下方，弹窗不关 */
const errors = ref<{ name?: string; slogan?: string; logo?: string }>({})
/** D2V-04 逐条列出的失败原因 */
const problems = ref<Problem[]>([])

const saveDialog = ref(false)
const saveError = ref('')
const invalidDialog = ref(false)
const logoBadDialog = ref(false)

const previewLogo = computed(() => resolveMediaUrl(logoKey.value) || '')
/** 站点 Logo 文件名（保存确认弹窗按原型回显文件名） */
const logoName = computed(() => {
  const seg = (logoKey.value.split('?')[0] ?? '').split('/').pop() ?? ''
  return seg || ''
})

function readErrMsg(e: unknown): string {
  if (e && typeof e === 'object') {
    const direct = (e as { msg?: unknown }).msg
    if (typeof direct === 'string' && direct) return direct
    const nested = (e as { response?: { data?: { msg?: unknown } } }).response?.data?.msg
    if (typeof nested === 'string' && nested) return nested
  }
  return ''
}

function extOf(key: string): string {
  const seg = (key.split('?')[0] ?? '').split('/').pop() ?? ''
  const dot = seg.lastIndexOf('.')
  return dot >= 0 ? seg.slice(dot + 1).toLowerCase() : ''
}

async function load() {
  loading.value = true
  try {
    const res = await fetchSiteBrand()
    const d = res.data
    logoKey.value = String(d?.logoKey || d?.logoUrl || '')
    name.value = String(d?.name || '尚品汇')
    slogan.value = String(d?.slogan || '')
    faviconKey.value = String(d?.faviconKey || d?.faviconUrl || '')
  } catch {
    /* toasted */
  } finally {
    loading.value = false
  }
}

/** 逐条收集不符合项：字数超限与 Logo 格式都列出来，只弹第一条是不够的 */
function validate(): boolean {
  const errs: { name?: string; slogan?: string; logo?: string } = {}
  const list: Problem[] = []

  const trimmed = name.value.trim()
  if (!trimmed) {
    errs.name = '站点名称未填写，须为 1～30 字符'
    list.push({ head: '站点名称未填写，须为 ', bold: '1～30', tail: ' 字符' })
  } else if (trimmed.length > 30) {
    errs.name = `站点名称为 1～30 字符，当前 ${trimmed.length} 字符`
    list.push({
      head: '站点名称 ',
      bold: String(trimmed.length),
      tail: ' 字符，超出 1～30 字符上限',
    })
  }

  const sl = slogan.value.trim()
  if (sl.length > 50) {
    errs.slogan = `Slogan 不超过 50 字符，当前 ${sl.length} 字符`
    list.push({ head: 'Slogan ', bold: String(sl.length), tail: ' 字符，超出 50 字符上限' })
  }

  const ext = extOf(logoKey.value)
  if (logoKey.value && !ALLOWED_LOGO_EXT.includes(ext)) {
    errs.logo = `Logo 格式为 ${ext || '未知'}，仅支持 jpeg / png / webp`
    list.push({
      head: 'Logo 格式为 ',
      bold: ext || '未知',
      tail: '，仅支持 jpeg / png / webp',
    })
  }

  errors.value = errs
  problems.value = list
  return list.length === 0
}

function pickLogo() {
  if (uploading.value) return
  logoInput.value?.click()
}

async function onLogoChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!ALLOWED_LOGO_EXT.includes(extOf(file.name)) || file.size > MAX_LOGO_BYTES) {
    logoBadDialog.value = true
    return
  }
  uploading.value = true
  try {
    const res = await uploadFile(file, 'SITE_LOGO')
    const url = res.data?.url
    if (!url) {
      ElMessage.error('上传成功但未返回地址')
      return
    }
    logoKey.value = url
    errors.value = { ...errors.value, logo: undefined }
    ElMessage.success('上传成功')
  } catch {
    /* toasted */
  } finally {
    uploading.value = false
  }
}

/** 「保存」：先逐条校验（失败弹 D2V-04），通过后再走 D2V-03 二次确认 */
function openSave() {
  if (!canEdit.value) {
    ElMessage.warning('无编辑权限')
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
  saveError.value = ''
  saving.value = true
  try {
    await updateSiteBrand({
      logoKey: logoKey.value || null,
      name: name.value.trim(),
      slogan: slogan.value.trim() || null,
      faviconKey: faviconKey.value.trim() || null,
    })
    ElMessage.success('已保存，顾客端刷新后生效')
    saveDialog.value = false
    await load()
  } catch (e: unknown) {
    saveError.value = readErrMsg(e) || '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageHead
    title="站点品牌"
    sub="站点 Logo、站点名与 Slogan 的统一维护入口 · 保存后顾客端刷新生效 · 供顶栏、登录页与页脚取用"
  >
    <template #actions>
      <button
        class="btn btn-primary"
        type="button"
        :disabled="saving || loading || !canEdit"
        @click="openSave"
      >
        {{ saving ? '保存中…' : '保存' }}
      </button>
    </template>
  </PageHead>

  <div class="brand-layout" v-loading="loading">
    <div class="card card-pad">
      <div class="form-grid">
        <div class="field full">
          <label class="label">站点 Logo</label>
          <div class="logo-row">
            <img v-if="previewLogo" class="logo-thumb" :src="previewLogo" alt="logo" />
            <div v-else class="logo-thumb logo-thumb-empty">Logo</div>
            <div>
              <button
                class="btn btn-secondary btn-sm"
                type="button"
                :disabled="!canEdit || uploading"
                @click="pickLogo"
              >
                {{ uploading ? '上传中…' : '上传新 Logo' }}
              </button>
              <p class="hint logo-hint">
                仅 1 张，jpeg / png / webp，≤10MB，建议 256×256（正方形）
              </p>
            </div>
          </div>
          <span v-if="errors.logo" class="error-text">{{ errors.logo }}</span>
          <input
            ref="logoInput"
            class="file-hidden"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            @change="onLogoChange"
          />
        </div>

        <div class="field">
          <label class="label">站点名称<span class="req">*</span></label>
          <input v-model="name" class="input" maxlength="30" placeholder="尚品汇" :disabled="!canEdit" />
          <span class="hint">1～30 字符，展示于顶栏与登录页标题</span>
          <span v-if="errors.name" class="error-text">{{ errors.name }}</span>
        </div>

        <div class="field">
          <label class="label">Favicon（可选）</label>
          <input
            v-model="faviconKey"
            class="input"
            placeholder="上传或填写 objectKey"
            :disabled="!canEdit"
          />
          <span class="hint">建议 32×32 或 64×64 的 ico / png</span>
        </div>

        <div class="field full">
          <label class="label">Slogan</label>
          <input
            v-model="slogan"
            class="input"
            maxlength="50"
            placeholder="选好物，享生活"
            :disabled="!canEdit"
          />
          <span class="hint">≤50 字符，展示于登录页与页脚</span>
          <span v-if="errors.slogan" class="error-text">{{ errors.slogan }}</span>
        </div>
      </div>

      <hr class="sep" />
      <dl class="kv">
        <dt>C 端接口</dt>
        <dd class="code">GET /api/site/brand</dd>
        <dt>生效方式</dt>
        <dd>保存后立即写库；顾客端刷新生效，允许 5～10 分钟缓存延迟</dd>
        <dt>权限</dt>
        <dd><span class="badge badge-amber">content:brand</span></dd>
      </dl>
    </div>

    <aside class="card card-pad brand-preview">
      <div class="page-title preview-title">C 端预览</div>
      <div class="preview-box">
        <div class="preview-bar">
          <img v-if="previewLogo" class="preview-logo" :src="previewLogo" alt="" />
          <div v-else class="preview-logo preview-logo-empty">Logo</div>
          <div>
            <div class="preview-name">{{ name.trim() || '尚品汇' }}</div>
            <div class="preview-slogan">{{ slogan.trim() || '选好物，享生活' }}</div>
          </div>
        </div>
        <div class="skeleton" />
        <div class="skeleton skeleton-short" />
      </div>
      <p class="hint preview-note">预览仅示意顶栏品牌区；真实布局见购物端原型。</p>
    </aside>
  </div>

  <!-- 保存站点品牌（D2V-03 · dlg-brand-save） -->
  <FormDialog
    v-model="saveDialog"
    title="保存站点品牌"
    icon="info"
    icon-text="✓"
    width="is-md"
    cancel-text="取消"
    confirm-text="确认保存"
    :loading="saving"
    @submit="confirmSave"
  >
    <p v-if="saveError" class="error-text">{{ saveError }}</p>
    <p>将保存以下品牌信息，并立即对顾客端生效：</p>
    <ul class="impact">
      <li>Logo：<b>{{ logoName || '—' }}</b>（256×256）</li>
      <li>站点名称：<b>{{ name.trim() }}</b></li>
      <li>Slogan：<b>{{ slogan.trim() || '—' }}</b></li>
    </ul>
    <div class="alert alert-info impact-alert">
      保存后顾客端<b>刷新生效</b>；客户端有 5～10 分钟短缓存，缓存期内可能仍显示旧品牌，属正常现象。本次修改会写入操作日志。
    </div>
  </FormDialog>

  <!-- 保存校验失败（D2V-04 · dlg-brand-invalid）：逐条列原因 -->
  <NoticeDialog
    v-model="invalidDialog"
    title="无法保存"
    icon="warn"
    width="is-md"
    confirm-text="去修正"
  >
    <p>站点品牌有 <b>{{ problems.length }}</b> 处不符合要求，请修正后重试：</p>
    <ul class="impact">
      <li v-for="(p, i) in problems" :key="i">
        <span>{{ p.head }}<b>{{ p.bold }}</b>{{ p.tail }}</span>
      </li>
    </ul>
    <div class="alert alert-info impact-alert">
      校验口径：Logo 1 张、jpeg / png / webp、≤10MB、建议 256×256；站点名 1～30 字符；Slogan ≤50
      字符。校验不通过时<b>不写库</b>，已填内容保留在本页。
    </div>
  </NoticeDialog>

  <!-- 上传新 Logo 不符合要求（D2V-05 · dlg-brand-logo-bad） -->
  <NoticeDialog
    v-model="logoBadDialog"
    title="Logo 不符合要求"
    icon="warn"
    width="is-sm"
    confirm-text="重新选择"
  >
    <p>仅支持 <b>jpeg / png / webp</b>，单张不超过 <b>10MB</b>，且只能上传 <b>1</b> 张。</p>
    <p class="hint">
      建议使用 256×256 的正方形图片。上传失败不影响已填写的站点名与 Slogan，可重新选择文件。
    </p>
  </NoticeDialog>
</template>

<style scoped>
.brand-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  align-items: start;
}
.req {
  color: var(--brick);
}
.logo-row {
  display: flex;
  align-items: center;
  gap: 16px;
}
.logo-thumb {
  width: 72px;
  height: 72px;
  border-radius: 16px;
  object-fit: cover;
  box-shadow: var(--sh-2);
  border: 1px solid var(--border);
  background: var(--cream);
  flex-shrink: 0;
}
.logo-thumb-empty {
  display: grid;
  place-items: center;
  font-size: 11px;
  color: var(--slate);
}
.logo-hint {
  margin-top: 8px;
}
.file-hidden {
  display: none;
}
.sep {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 8px 0 18px;
}
.brand-preview {
  background: var(--cream);
}
.preview-title {
  font-size: 14px;
  margin-bottom: 12px;
}
.preview-box {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  box-shadow: var(--sh-1);
}
.preview-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.preview-logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  object-fit: cover;
  background: var(--cream);
  flex-shrink: 0;
}
.preview-logo-empty {
  display: grid;
  place-items: center;
  font-size: 10px;
  color: var(--slate);
  border: 1px dashed var(--border);
}
.preview-name {
  font-weight: 700;
  font-size: 15px;
}
.preview-slogan {
  font-size: 12px;
  color: var(--slate);
}
.skeleton {
  height: 8px;
  background: var(--sand);
  border-radius: 4px;
  margin-bottom: 8px;
}
.skeleton-short {
  width: 70%;
  margin-bottom: 0;
}
.preview-note {
  margin-top: 12px;
}
.impact-alert {
  margin-top: 12px;
}
@media (max-width: 900px) {
  .brand-layout {
    grid-template-columns: 1fr;
  }
}
</style>