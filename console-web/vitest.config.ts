import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      include: [
        'src/api/http.ts',
        'src/stores/**/*.ts',
        'src/composables/**/*.ts',
        'src/utils/**/*.ts',
        'src/components/**/*.vue',
      ],
      exclude: [
        'src/**/*.test.ts',
        'src/**/*.d.ts',
        'src/api/schema.d.ts',
        'src/api/types.ts',
      ],
      thresholds: {
        lines: 75,
        branches: 65,
      },
    },
  },
})
