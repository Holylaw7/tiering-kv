import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发期把 /api 代理到控制面后端，避免 CORS 与硬编码地址
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 显式绑定 IPv4，避免 Windows 上 localhost 只解析到 ::1 导致 127.0.0.1 拒绝连接
    host: '127.0.0.1',
    port: 5173,
    // 避免 watcher 监视截图/脚本目录导致 Windows 下 EBUSY 崩溃
    watch: {
      // 编辑器/工具会以 ".xxx.<uuid>.tmpdir" 形式原子写文件，Windows 下
      // chokidar 会抛 EBUSY 直接杀死 dev server，必须显式忽略
      ignored: [
        '**/scripts/**',
        '**/screenshots/**',
        '**/dist/**',
        '**/.vite/**',
        '**/.*.tmpdir/**',
        '**/*.tmp'
      ]
    },
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
          element: ['element-plus']
        }
      }
    }
  }
})
