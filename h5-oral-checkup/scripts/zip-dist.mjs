#!/usr/bin/env node
/**
 * 把 h5-oral-checkup/dist/ 打成 zip，输出到 public/release/。
 *
 * 版本号取自 `dist/assets/index-[hash].js` 的 content-hash 前 8 位
 * （由 vite 自动注入，业务代码变了 hash 就变）。
 *
 * 流程：
 *   1. 扫 dist/assets/index-*.js，匹配 `^index-([0-9a-z]+)\.js$` 取 hash
 *   2. 包名固定为 h5-oral-checkup-<hash>.zip（不带时间戳）
 *   3. 走系统 `zip` 命令（macOS / Linux 自带）。Windows 走 PowerShell 的 Compress-Archive
 *
 * 调用方：主项目 package.json 的 `h5:zip` script。
 *   设计上 h5:zip = h5:sync + zip，确保 dist 已经同步到 public/ 之后立刻出包。
 */

import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, mkdirSync, rmSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(__dirname, '..', '..') // h5-oral-checkup/scripts → 项目根

const distDir = resolve(projectRoot, 'h5-oral-checkup', 'dist')
const releaseDir = resolve(projectRoot, 'public', 'release')

if (!existsSync(distDir)) {
  console.error(`[h5:zip] 找不到 dist 目录：${distDir}`)
  console.error('[h5:zip] 请先运行 `pnpm h5:sync`（会触发 h5:build）')
  process.exit(1)
}

const hash = readAssetHash(distDir)
if (!hash) {
  console.error('[h5:zip] 没在 dist/assets/ 里找到 index-[hash].js，无法取版本号')
  process.exit(1)
}

mkdirSync(releaseDir, { recursive: true })

const zipName = `h5-oral-checkup-${hash}.zip`
const zipPath = resolve(releaseDir, zipName)

const distZipName = resolve(distDir, '..', zipName)
if (existsSync(distZipName)) rmSync(distZipName)

let zipCmd, zipArgs
if (process.platform === 'win32') {
  // PowerShell Compress-Archive：标准 Windows 自带
  zipCmd = 'powershell'
  zipArgs = [
    '-NoProfile',
    '-Command',
    `Compress-Archive -Path "${distDir}\\*" -DestinationPath "${zipPath}" -Force`,
  ]
} else {
  zipCmd = 'zip'
  zipArgs = ['-r', zipPath, 'dist']
}

console.log(`[h5:zip] version (assets hash) = ${hash}`)
console.log(`[h5:zip] → ${zipPath}`)
console.log(`[h5:zip] cmd: ${zipCmd} ${zipArgs.join(' ')}`)

// 从 h5-oral-checkup 目录跑 zip，让包内顶层目录是 dist/
execFileSync(zipCmd, zipArgs, {
  cwd: resolve(projectRoot, 'h5-oral-checkup'),
  stdio: 'inherit',
})

console.log(`[h5:zip] ✓ ${zipName}`)

/**
 * 从 dist/assets/input-XXXXXXXX.js 取 8 位 hash。
 * 只取首个匹配项；hash 变化保证只有一个文件。
 * Rollup content-hash 是 base36（含 A-Z），不是纯小写。
 */
function readAssetHash(dir) {
  const assetsDir = resolve(dir, 'assets')
  if (!existsSync(assetsDir)) return null
  const m = readdirSync(assetsDir)
    .sort()
    .find((name) => /^input-([0-9A-Za-z]+)\.js$/.test(name))
  return m ? /^input-([0-9A-Za-z]+)\.js$/.exec(m)[1].slice(0, 8) : null
}
