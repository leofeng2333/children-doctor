# h5-oral-checkup

儿童口腔-面容变化 H5 独立子项目（输入手机号 + AI 结果页）。

## 与主项目的关系

```
children-doctor/                  ← Vue + Capacitor 主项目（iOS/Android App）
├── src/                          ← Vue 源码
├── android/ ios/                 ← Capacitor 原生工程
└── h5-oral-checkup/              ← 本目录：独立的 H5 子项目
    ├── input.html                 ← 入口：输入手机号 + 验证码
    ├── face-result.html          ← 入口：AI 结果展示
    ├── input/                     ← ES Module：main.js / api.js / validation.js / countdown.js / toast.js
    ├── face-result/              ← ES Module：main.js / data-source.js / diagnosis-copy.js / injector.js / image-splitter.js
    ├── base.css                   ← 两页共享的公共样式
    ├── input.css                  ← input 页特有样式
    ├── face-result.css
    ├── package.json              ← 独立依赖（只装 vite）
    ├── vite.config.ts
    └── dist/                     ← 构建产物（被主项目打包进 App）
```

H5 是**完全独立**的 Vite 项目：
- 自己的 `package.json`，只装 `vite`，不污染主项目依赖
- 自己的 `node_modules`，构建产物在 `dist/`
- 主项目 Capacitor 打包时，`dist/` 内容会拷贝到主项目的 `public/h5-oral-checkup/` 一并进 App

## 开发

```bash
cd h5-oral-checkup
pnpm install        # 首次安装依赖（只装 vite）
pnpm dev            # 启动 dev server: http://localhost:5180
```

## 构建

```bash
cd h5-oral-checkup
pnpm build          # 产出到 dist/
```

构建产物：

```
dist/
├── input.html                   ← 业务入口（强缓存）
├── face-result.html
├── base-[hash].css              ← content-hash 缓存（两页共享）
├── input-[hash].css             ← content-hash 缓存（input 页特有）
├── face-result-[hash].css       ← content-hash 缓存（结果页特有）
├── input-[hash].js              ← content-hash 缓存（input 页 JS）
├── face-result-[hash].js        ← content-hash 缓存（结果页 JS）
├── assets/*.js                 ← 依赖模块（带 hash）
├── html-icon.png
└── version.json                ← 版本号（前端启动时检测）
```

### Cache-Bust 机制（不需要手写）

vite 自动给引用的 JS/CSS 加 content-hash：
- 业务代码变了 → 文件名 `face-result-AbCdEf.js` → 浏览器发现 URL 变了 → 拉新文件
- 业务代码没变 → 文件名不变 → 浏览器 304 命中缓存

`<script>` 启动时会 `fetch('./version.json')` 比对 `sessionStorage.__h5_version`，
变了就 `location.reload(true)` 强制重载（应对 HTML 强缓存命中 + 服务器只更新了 JS 的场景）。

## 与主项目的对接

主项目 `pnpm cap:sync` 会自动执行 `h5-oral-checkup/dist/*` → `public/h5-oral-checkup/` 的拷贝。
无需手动同步。

如果需要手动同步（例如调试）：

```bash
cd h5-oral-checkup
pnpm build
rm -rf ../public/h5-oral-checkup/*
cp -r dist/* ../public/h5-oral-checkup/
```

## 打包发布产物

需要把 dist 打成一个 zip 部署到 CDN / 后端资源站时，跑主项目的 `h5:zip`：

```bash
cd /path/to/children-doctor
pnpm h5:zip
```

等价于 `pnpm h5:sync && node h5-oral-checkup/scripts/zip-dist.mjs`，输出位置：
`public/release/h5-oral-checkup-<version>.zip`，其中 `<version>` 取自 `dist/version.json` 的 `version` 字段。

- 不带时间戳；业务代码变了 → `version` 自动变 → 包名变 → 不会出现"重名但内容不一样"的歧义
- 同 version 重跑是**原地覆盖**（幂等），不会累积同名文件
- Windows 上脚本会走 PowerShell 的 `Compress-Archive`