import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { VantResolver } from '@vant/auto-import-resolver'
import Components from 'unplugin-vue-components/vite'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    Components({
      resolvers: [VantResolver()],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
      '/admin/files': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
    },
  },
})
