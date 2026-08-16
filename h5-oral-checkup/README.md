# h5-oral-checkup

儿童口腔-面容变化 H5 独立子项目（输入信息 + AI 结果页，单页 React 应用）。

## 与主项目的关系

```
children-doctor/                  ← Vue + Capacitor 主项目（iOS/Android App）
├── src/                          ← Vue 源码
├── android/ ios/                 ← Capacitor 原生工程
└── h5-oral-checkup/              ← 本目录：独立的 H5 子项目（React + Vite）
    ├── input.html                ← 唯一 SPA 入口
    ├── src/
    │   ├── main.tsx              ← React 挂载
    │   ├── App.tsx               ← <BrowserRouter> 路由表
    │   ├── pages/                ← PhoneVerifyPage / NamePhonePage / FaceResultPage
    │   ├── components/           ← PageShell / Footer / useToast
    │   ├── lib/                  ← smsApi / dataSource / diagnosisCopy / phoneValidation / splitImage / useCountdown
    │   └── styles/               ← base.css / phone-verify.css / name-phone.css / face-result.css（全局 CSS，vw 体系原封不动）
    ├── assets/                   ← logo + html-icon.png
    ├── public/                   ← （空）
    ├── scripts/zip-dist.mjs      ← dist → zip，取 assets hash 作为版本号
    ├── package.json              ← 独立依赖（只装 vite，react 相关装在主项目）
    ├── tsconfig.json
    ├── vite.config.ts            ← @vitejs/plugin-react + redirectIndexToInputPlugin（dev fallback 目标改 /input.html）
    └── dist/                     ← 构建产物（被主项目打包进 App）
```

H5 是**完全独立**的 Vite 项目：
- 自己的 `package.json`，只装 `vite`，react/react-dom/react-router-dom 装在主项目（`node_modules` 是软链）
- 自己的 `node_modules`，构建产物在 `dist/`
- 主项目 Capacitor 打包时，`dist/` 内容会拷贝到主项目的 `public/h5-oral-checkup/` 一并进 App

## 路由表

| 路径 | 组件 | 说明 |
|---|---|---|
| `/` | `<Navigate to="/name" />` | 默认跳 first 模式 |
| `/phone` | `<PhoneVerifyPage />` | default 模式：手机号 + 验证码 |
| `/name` | `<NamePhonePage />` | first 模式：姓名 + 手机号 |
| `/face-result` | `<FaceResultPage />` | 结果展示 |
| `*` | `<Navigate to="/name" />` | 兜底跳 `/name` |

### 扫码链接兼容

旧 QR URL 形如 `<base>/follow?id=<llmAnalysisId>&first=1`，**保持不变**。

部署侧需配置 SPA fallback：把 `/follow`（以及 `/follow/*` 所有未命中静态文件的请求）rewrite 到 `/input.html`。
QR 始终带 `first=1`，按业务设计永远走 first 模式（`/name`）。

> **重要**：部署在公众号服务器时，**必须**有 SPA fallback（nginx try_files / Node express sendFile / Tomcat RewriteValve 等）。
> 若服务器不支持 fallback，可以临时把 `BrowserRouter` 切换为 `HashRouter`（仅需改 `App.tsx` 一行）。

## 输入页模式

| 扫码链接 | 进入路径 | 表单 | 提交流程 |
|---|---|---|---|
| `<base>/follow?id=...&first=1` | `/name` | 姓名 + 手机号 | 不调接口，直接跳 `/face-result`（TODO 后端验证接口待补） |
| `<base>/follow?id=...` | `/name` | 同上 | 同上（兜底） |
| `/phone`（直接访问） | `/phone` | 手机号 + 验证码 | 完整 SMS 验证码流程 |

主项目 `src/composables/useQrcode.ts` 输出的 URL 始终带 `&first=1`，所以默认走 first 模式。
`/phone` 留给直接访问 / 调试（无 `?first=1` 时也会被重定向到 `/name`，所以要走 `/phone` 必须显式访问 `/phone`）。

## 开发

```bash
cd h5-oral-checkup
pnpm dev            # 启动 dev server: http://localhost:5182
```

dev server 已包含 SPA fallback，`/phone` `/name` `/face-result` 直接访问都能渲染对应组件。

## 类型检查

```bash
cd h5-oral-checkup
npx tsc --noEmit    # TS 类型检查（不构建）
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
├── assets/
│   ├── input-[hash].css         ← content-hash 缓存（全部样式打包到一个文件）
│   ├── input-[hash].js          ← content-hash 缓存（React + 全部业务代码）
│   ├── html-icon-[hash].png     ← sprite
│   ├── logo-left-[hash].png
│   └── logo-right-[hash].png
```

### Cache-Bust 机制

vite 自动给 `assets/input-[hash].css|js` 加 content-hash：
- 业务代码变了 → 文件名变 → 浏览器拉到新文件
- 业务代码没变 → 文件名不变 → 浏览器 304 命中缓存

部署侧需保证 `input.html` 不强缓存（或者走 SPA fallback 返同一份 input.html），
否则 HTML 被缓存时新发版的 `assets/input-[hash].js` 用户拿不到。

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
`public/release/h5-oral-checkup-<version>.zip`，其中 `<version>` 取自 `dist/assets/input-[hash].js` 的 hash 前 8 位（vite 自带）。

- 不带时间戳；业务代码变了 → hash 自动变 → 包名变 → 不会出现"重名但内容不一样"的歧义
- 同 version 重跑是**原地覆盖**（幂等），不会累积同名文件
- Windows 上脚本会走 PowerShell 的 `Compress-Archive`

## 部署侧要求

部署到公众号服务器时，**必须**配置 SPA fallback（因为用了 BrowserRouter），例：

**nginx**
```nginx
location /follow {
  try_files $uri /input.html;
}
```

**Express**
```js
app.get('/follow{,/(*)?}', (req, res) => res.sendFile('dist/input.html', { root: '.' }))
```

若服务器不支持 fallback，把 `src/App.tsx` 里的 `<BrowserRouter>` 改成 `<HashRouter>`，路径变成 `/#/phone` `/#/name` `/#/face-result`，无需 fallback，但 QR URL 也要同步改。
