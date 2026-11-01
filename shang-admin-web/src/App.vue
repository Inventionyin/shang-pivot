<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

onMounted(() => {
  auth.hydrate()
  if (auth.token) {
    void auth.loadProfile()
  }
})
</script>

<template>
  <RouterView />
</template>
