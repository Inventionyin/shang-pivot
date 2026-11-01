<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadFile } from '@/api/files'
import { resolveMediaUrl } from '@/utils/media'

const model = defineModel<string>({ default: '' })

const props = withDefaults(
  defineProps<{
    bizType?: string
    accept?: string
  }>(),
  {
    bizType: 'PRODUCT',
    accept: 'image/jpeg,image/png,image/webp',
  },
)

const uploading = ref(false)
const inputRef = ref<HTMLInputElement | null>(null)

function pick() {
  if (uploading.value) return
  inputRef.value?.click()
}

function clear(e: Event) {
  e.stopPropagation()
  model.value = ''
}

async function onChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!props.accept.split(',').some((t) => file.type === t.trim())) {
    ElMessage.warning('仅支持 JPG / PNG / WEBP')
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('图片不能超过 10MB')
    return
  }
  uploading.value = true
  try {
    const res = await uploadFile(file, props.bizType)
    const url = res.data?.url
    if (!url) {
      ElMessage.error('上传成功但未返回地址')
      return
    }
    model.value = url
    ElMessage.success('上传成功')
  } catch {
    /* interceptor toasted */
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="oss-up">
    <button
      type="button"
      class="oss-tile"
      :class="{ filled: !!model, busy: uploading }"
      :disabled="uploading"
      :title="model ? '点击更换图片' : '点击上传图片'"
      @click="pick"
    >
      <img v-if="model && !uploading" :src="resolveMediaUrl(model)" alt="" />
      <span v-else class="oss-tile-hint">{{ uploading ? '上传中…' : '点击上传' }}</span>
      <span
        v-if="model && !uploading"
        class="oss-tile-x"
        title="移除"
        @click="clear"
      >×</span>
    </button>

    <div v-if="model" class="oss-url" :title="model">
      <span class="oss-url-label">OSS</span>
      <span class="oss-url-text">{{ model }}</span>
    </div>
    <p v-else class="oss-empty-hint">支持 JPG / PNG / WEBP，最大 10MB</p>

    <input
      ref="inputRef"
      type="file"
      class="oss-file"
      :accept="accept"
      @change="onChange"
    />
  </div>
</template>

<style scoped>
.oss-up {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}
.oss-tile {
  position: relative;
  width: 104px;
  height: 104px;
  padding: 0;
  border: 1px dashed var(--border);
  border-radius: 10px;
  background: var(--sand);
  cursor: pointer;
  overflow: hidden;
  display: grid;
  place-items: center;
  font-family: inherit;
  color: var(--slate);
  font-size: 12px;
  transition: border-color 0.15s, color 0.15s;
}
.oss-tile:hover:not(:disabled) {
  border-color: var(--amber);
  color: var(--ink);
}
.oss-tile.filled {
  border-style: solid;
  background: var(--surface);
}
.oss-tile.busy {
  cursor: wait;
  opacity: 0.75;
}
.oss-tile img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.oss-tile-hint {
  pointer-events: none;
}
.oss-tile-x {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 14px;
  line-height: 20px;
  text-align: center;
  z-index: 1;
}
.oss-tile-x:hover {
  background: var(--brick, #c44);
}
.oss-url {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  min-width: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--sand);
  border: 1px solid var(--border);
  font-size: 12px;
  user-select: all;
}
.oss-url-label {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--amber-soft, #f5e6c8);
  color: #9a6a12;
  font-weight: 600;
  font-size: 11px;
}
.oss-url-text {
  color: var(--stone);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono, ui-monospace, monospace);
}
.oss-empty-hint {
  margin: 0;
  font-size: 12px;
  color: var(--slate);
}
.oss-file {
  display: none;
}
</style>
