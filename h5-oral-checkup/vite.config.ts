import { existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 单页 React 应用，入口 HTML 为标准 `index.html`。
 *
 * 部署目标：https://m.hangxiaoya.com/zt/input/
 *  - `base: '/zt/input/'` 让 vite 构建产物的所有相对路径都基于该子路径
 *    （dist/assets/*.js 的引用、动态导入都从 /zt/input/ 算起）
 *  - React Router 的 basename 见 src/App.tsx，必须与 vite base 一致
 *
 * 旧扫码链接：<base>/follow?id=<llmAnalysisId>&first=1
 * 全部走 SPA 路由 /name /phone /face-result。
 */

export default defineConfig({
  root: '.',
  // 部署到 https://m.hangxiaoya.com/zt/input/ —— 静态资源路径必须从该子路径出发
  base: '/zt/input/',
  plugins: [react()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5182,
    open: '/',
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: resolve(__dirname, 'index.html'),
    },
  },
})

// silence "existsSync unused" lint under tsconfig noUnusedLocals
void existsSync