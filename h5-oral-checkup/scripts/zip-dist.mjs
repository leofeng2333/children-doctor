#!/usr/bin/env node
/**
 * 把 h5-oral-checkup/dist/ 打成 zip，输出到 public/release/。
 *
 * 流程：
 *   1. 读取 h5-oral-checkup/dist/version.json 的 version 字段
 *   2. 包名固定为 h5-oral-checkup-<version>.zip（不带时间戳）
 *   3. 走系统 `zip` 命令（macOS / Linux 自带）。Windows 走 PowerShell 的 Compress-Archive
 *
 * 调用方：主项目 package.json 的 `h5:zip` script。
 *   设计上 h5:zip = h5:sync + zip，确保 dist 已经同步到 public/ 之后立刻出包。
 */

import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, mkdirSync, rmSync } from 'node:fs'
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

const versionJson = JSON.parse(
  readFileSync(resolve(distDir, 'version.json'), 'utf-8'),
)
const version = versionJson.version
if (!version) {
  console.error('[h5:zip] version.json 里没有 version 字段')
  process.exit(1)
}

mkdirSync(releaseDir, { recursive: true })

// 清理旧包，避免版本号相同时残留旧文件
const zipName = `h5-oral-checkup-${version}.zip`
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

console.log(`[h5:zip] → ${zipPath}`)
console.log(`[h5:zip] cmd: ${zipCmd} ${zipArgs.join(' ')}`)

// 从 h5-oral-checkup 目录跑 zip，让包内顶层目录是 dist/
execFileSync(zipCmd, zipArgs, {
  cwd: resolve(projectRoot, 'h5-oral-checkup'),
  stdio: 'inherit',
})

console.log(`[h5:zip] ✓ ${zipName}`)
