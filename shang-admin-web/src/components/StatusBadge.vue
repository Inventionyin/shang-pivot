<script setup lang="ts">
withDefaults(
  defineProps<{
    text: string
    tone?: 'amber' | 'coral' | 'forest' | 'brick' | 'soft-forest'
  }>(),
  { tone: 'amber' },
)

const classMap: Record<string, string> = {
  amber: 'badge badge-amber',
  coral: 'badge badge-coral',
  forest: 'badge badge-forest',
  brick: 'badge badge-brick',
  'soft-forest': 'badge',
}
</script>

<template>
  <span
    :class="classMap[tone]"
    :style="tone === 'soft-forest' ? { background: 'var(--forest-soft)', color: '#2F5230' } : undefined"
  >
    {{ text }}
  </span>
</template>
