import { existsSync, writeFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { defineConfig } from 'vite'

/**
 * 入口候选：dev/preview/build 时只要文件存在就会被 vite 处理。
 * 缺哪个不影响其它入口的构建（容错友好）。
 */
const ENTRY_CANDIDATES = [
  { key: 'input', file: 'input.html' },
  { key: 'input-first', file: 'input-first.html' },
  { key: 'face-result', file: 'face-result.html' },
]

/** 自动筛选出存在的入口文件 */
const entries = ENTRY_CANDIDATES
  .filter((e) => existsSync(resolve(__dirname, e.file)))
  .reduce((acc, e) => {
    acc[e.key] = resolve(__dirname, e.file)
    return acc
  }, {})

/**
 * 自定义插件：构建结束后把所有入口 HTML 的 content-hash
 * 汇总到一个 version.json，写入 dist/。
 *
 * 部署后，前端 JS 启动时 fetch version.json，
 * 与 sessionStorage 里上一次的 version 对比，变了就 location.reload()。
 */
function emitVersionJsonPlugin() {
  return {
    name: 'emit-version-json',
    closeBundle() {
      const outDir = resolve(__dirname, 'dist')
      const hashes = {}
      for (const f of Object.values(entries)) {
        const basename = f.split('/').pop()
        try {
          const buf = readFileSync(resolve(outDir, basename))
          hashes[basename] = createHash('sha256').update(buf).digest('hex').slice(0, 8)
        } catch (e) {
          hashes[basename] = ''
        }
      }
      const version = createHash('sha256')
        .update(JSON.stringify(hashes))
        .digest('hex')
        .slice(0, 12)
      writeFileSync(
        resolve(outDir, 'version.json'),
        JSON.stringify({ version, files: hashes, builtAt: new Date().toISOString() }, null, 2),
        'utf-8',
      )
      console.log(`\n[h5-oral-checkup] version.json → ${version}\n`)
    },
  }
}

export default defineConfig({
  root: '.',
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      input: entries,
    },
  },
  plugins: [emitVersionJsonPlugin()],
  server: {
    host: '0.0.0.0',
    port: 5180,
    open: false,
  },
})