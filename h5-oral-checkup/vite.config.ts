import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 单页 React 应用，入口 HTML 名为 `input.html`（与历史部署保持一致）。
 *
 * 旧扫码链接：<base>/follow?id=<llmAnalysisId>&first=1
 *   - 旧多页产物（input.html / input-first.html / face-result.html）已废弃，
 *     全部走 SPA 路由 /name /phone /face-result。
 */

/**
 * 把 vite dev server 的 SPA fallback 重写目标从 `/index.html` 切到 `/input.html`，
 * 否则访问 `/`、`/name`、`/phone` 等都会被内部中间件硬编码重写到 `/index.html`
 * 然后 404（vite 8 没有 `server.indexFile` 选项）。
 *
 * 顺序上必须排在 vite 内置 HTML 中间件之后（return fn 形式），才能在 fallback
 * 已把 req.url 改写成 `/index.html` 后再做一次重写。
 */
function redirectIndexToInputPlugin(): Plugin {
  return {
    name: 'redirect-index-to-input',
    configureServer(server) {
      return () => {
        server.middlewares.use((req, _res, next) => {
          if (req.url === '/index.html') {
            req.url = '/input.html'
          }
          next()
        })
      }
    },
  }
}

export default defineConfig({
  root: '.',
  base: './',
  plugins: [react(), redirectIndexToInputPlugin()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5182,
    open: '/input.html',
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: resolve(__dirname, 'input.html'),
    },
  },
})

// silence "existsSync unused" lint under tsconfig noUnusedLocals
void existsSync